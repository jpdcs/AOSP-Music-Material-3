/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.music;

import com.android.music.MusicUtils.ServiceToken;

import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;

import java.util.ArrayList;

public class QueryBrowserActivity extends BaseActivity
implements MusicUtils.Defs, ServiceConnection
{
    private final static int PLAY_NOW = 0;
    private final static int ADD_TO_QUEUE = 1;
    private final static int PLAY_NEXT = 2;
    private final static int PLAY_ARTIST = 3;
    private final static int EXPLORE_ARTIST = 4;
    private final static int PLAY_ALBUM = 5;
    private final static int EXPLORE_ALBUM = 6;
    private final static int REQUERY = 3;
    private QueryListAdapter mAdapter;
    private boolean mAdapterSent;
    private String mFilterString = "";
    private ServiceToken mToken;
    private RecyclerView mTrackList;

    public QueryBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mAdapter = (QueryListAdapter) getLastCustomNonConfigurationInstance();
        mToken = MusicUtils.bindToService(this, this);
    }


    public void onServiceConnected(ComponentName name, IBinder service) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        ContextCompat.registerReceiver(this, mScanListener, f, ContextCompat.RECEIVER_EXPORTED);
        
        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;
        
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            String path = uri.toString();
            if (path.startsWith("content://media/external/audio/media/")) {
                String id = uri.getLastPathSegment();
                long [] list = new long[] { Long.valueOf(id) };
                MusicUtils.playAll(this, list, 0);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/albums/")) {
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setPackage(getPackageName());
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
                i.putExtra("album", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/artists/")) {
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setPackage(getPackageName());
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
                i.putExtra("artist", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            }
        }

        mFilterString = intent.getStringExtra(SearchManager.QUERY);
        if (MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)) {
            String focus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS);
            String artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST);
            String album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM);
            String title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE);
            if (focus != null) {
                if (focus.startsWith("audio/") && title != null) {
                    mFilterString = title;
                } else if (focus.equals(MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)) {
                    if (album != null) {
                        mFilterString = album;
                        if (artist != null) {
                            mFilterString = mFilterString + " " + artist;
                        }
                    }
                } else if (focus.equals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)) {
                    if (artist != null) {
                        mFilterString = artist;
                    }
                }
            }
        }

        setContentView(R.layout.media_picker_activity);
        MusicUtils.updateButtonBar(this, 0);
        mTrackList = findViewById(R.id.list);
        mTrackList.setLayoutManager(new LinearLayoutManager(this));
        MusicUtils.updateNowPlaying(this);
        if (mAdapter == null) {
            mAdapter = new QueryListAdapter(
                    getApplication(),
                    this,
                    R.layout.track_list_item,
                    null, // cursor
                    new String[] {},
                    new int[] {});
            mTrackList.setAdapter(mAdapter);
            if (TextUtils.isEmpty(mFilterString)) {
                getQueryCursor(mAdapter.getQueryHandler(), null);
            } else {
                getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
            }
        } else {
            mAdapter.setActivity(this);
            mTrackList.setAdapter(mAdapter);
            mQueryCursor = mAdapter.getCursor();
            if (mQueryCursor != null) {
                init(mQueryCursor);
            } else {
                getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
            }
        }
    }

    public void onServiceDisconnected(ComponentName name) {
        
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mFilterString = intent.getStringExtra(SearchManager.QUERY);
        if (mAdapter != null) {
            getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
        }
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        mAdapterSent = true;
        return mAdapter;
    }
    
    @Override
    public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        ContextCompat.registerReceiver(this, mTrackListListener, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        mTrackListListener.onReceive(null, null);

        MusicUtils.setSpinnerState(this);
    }

    private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            MusicUtils.updateNowPlaying(QueryBrowserActivity.this);
        }
    };

    @Override
    public void onPause() {
        unregisterReceiver(mTrackListListener);
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(mToken);
        unregisterReceiver(mScanListener);
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        if (mTrackList != null) {
            mTrackList.setAdapter(null);
        }
        mAdapter = null;
        super.onDestroy();
    }
    
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(QueryBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getQueryCursor(mAdapter.getQueryHandler(), null);
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else {
                    getQueryCursor(mAdapter.getQueryHandler(), null);
                }
                break;
        }
    }
    
    public void init(Cursor c) {
        Log.d("QueryBrowser", "init: cursor=" + (c == null ? "null" : c.getCount() + " rows"));
        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(c);

        if (mQueryCursor == null || mQueryCursor.getCount() == 0) {
            Log.d("QueryBrowser", "No results found");
            // Show empty view if you have one, or just leave it
        }

        if (mQueryCursor == null) {
            if (!MusicUtils.isMediaScannerScanning(this)) {
                // If it's not scanning and we have no cursor, it might be a permanent failure of the fancy search
                // Log the error but don't loop indefinitely
                Log.e("QueryBrowser", "Query failed and scanner not running");
                return;
            }
            MusicUtils.displayDatabaseError(this);
            if (mTrackList != null) {
                mTrackList.setAdapter(null);
            }
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }
        MusicUtils.hideDatabaseError(this);
    }
    
    public void onListItemClick(int position, long id)
    {
        mQueryCursor.moveToPosition(position);
        if (mQueryCursor.isBeforeFirst() || mQueryCursor.isAfterLast()) {
            return;
        }
        String selectedType = mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE));
        
        if ("artist".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setPackage(getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
            intent.putExtra("artist", Long.valueOf(id).toString());
            startActivity(intent);
        } else if ("album".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setPackage(getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("album", Long.valueOf(id).toString());
            startActivity(intent);
        } else if (position >= 0 && id >= 0){
            long [] list = new long[] { id };
            MusicUtils.playAll(this, list, 0);
        } else {
            Log.e("QueryBrowser", "invalid position/id: " + position + "/" + id);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem searchItem = menu.findItem(R.id.search);
        if (searchItem != null) {
            searchItem.expandActionView();
            SearchView searchView = (SearchView) searchItem.getActionView();
            if (searchView != null) {
                searchView.setQuery(mFilterString, false);
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        mFilterString = query;
                        getQueryCursor(mAdapter.getQueryHandler(), query);
                        searchView.clearFocus();
                        return true;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        mFilterString = newText;
                        getQueryCursor(mAdapter.getQueryHandler(), newText);
                        return true;
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case USE_AS_RINGTONE: {
                // RecyclerView selection is complex, this might not work as expected
                // MusicUtils.setRingtone(this, mTrackList.getSelectedItemId());
                return true;
            }

        }
        return super.onOptionsItemSelected(item);
    }

    private Cursor getQueryCursor(AsyncQueryHandler async, String filter) {
        if (filter == null) {
            filter = "";
        }
        Log.d("QueryBrowser", "getQueryCursor: filter=" + filter);
        
        // Try fancy search with minimal columns first
        String[] ccols = new String[] {
                BaseColumns._ID,
                MediaStore.Audio.Media.MIME_TYPE,
                "artist",
                "album",
                "title"
        };

        Uri search = Uri.parse("content://media/external/audio/search/fancy/" +
                Uri.encode(filter));
        
        Cursor ret = null;
        try {
            if (async != null) {
                async.startQuery(0, null, search, ccols, null, null, null);
                return null;
            } else {
                ret = MusicUtils.query(this, search, ccols, null, null, null);
            }
        } catch (Exception ex) {
            Log.e("QueryBrowser", "Fancy search failed, falling back", ex);
        }

        if (ret == null && async == null) {
            return getFallbackQueryCursor(async, filter);
        }
        return ret;
    }

    private Cursor getFallbackQueryCursor(AsyncQueryHandler async, String filter) {
        Log.d("QueryBrowser", "getFallbackQueryCursor: filter=" + filter);
        String[] ccols = new String[] {
                BaseColumns._ID,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE
        };

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection = "(" + MediaStore.Audio.Media.TITLE + " LIKE ? OR " +
                MediaStore.Audio.Media.ARTIST + " LIKE ? OR " +
                MediaStore.Audio.Media.ALBUM + " LIKE ?) AND " + 
                MediaStore.Audio.Media.IS_MUSIC + "=1";
        String[] selectionArgs = new String[] { "%" + filter + "%", "%" + filter + "%", "%" + filter + "%" };

        if (async != null) {
            async.startQuery(0, null, uri, ccols, selection, selectionArgs, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
            return null;
        } else {
            return MusicUtils.query(this, uri, ccols, selection, selectionArgs, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
        }
    }
    
    static class QueryListAdapter extends CursorRecyclerViewAdapter<QueryListAdapter.ViewHolder> {
        private QueryBrowserActivity mActivity = null;
        private AsyncQueryHandler mQueryHandler;
        private String mConstraint = null;
        private boolean mConstraintIsValid = false;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView line1;
            TextView line2;
            ImageView icon;

            ViewHolder(View v) {
                super(v);
                line1 = (TextView) v.findViewById(R.id.line1);
                line2 = (TextView) v.findViewById(R.id.line2);
                icon = (ImageView) v.findViewById(R.id.icon);
            }
        }

        class QueryHandler extends AsyncQueryHandler {
            QueryHandler(ContentResolver res) {
                super(res);
            }
            
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                if (cursor == null && token == 0) {
                    // Fancy search failed, try fallback
                    Log.d("QueryBrowser", "Fancy search returned null, trying fallback");
                    // We don't have the filter string here easily, but we can get it from mActivity
                    String filter = mActivity.mFilterString; 
                    mActivity.getFallbackQueryCursor(this, filter);
                    return;
                }
                mActivity.init(cursor);
            }
        }

        QueryListAdapter(Context context, QueryBrowserActivity currentactivity,
                int layout, Cursor cursor, String[] from, int[] to) {
            super(context, cursor);
            mActivity = currentactivity;
            mQueryHandler = new QueryHandler(context.getContentResolver());
        }

        public void setActivity(QueryBrowserActivity newactivity) {
            mActivity = newactivity;
        }
        
        public AsyncQueryHandler getQueryHandler() {
            return mQueryHandler;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.track_list_item, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder vh, Cursor cursor) {
            ImageView iv = vh.icon;
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            
            String mimetype = cursor.getString(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.MIME_TYPE));
            Log.d("QueryBrowser", "onBindViewHolder: pos=" + cursor.getPosition() + " mime=" + mimetype);
            
            if (mimetype == null) {
                mimetype = "audio/";
            }
            if (mimetype.equals("artist")) {
                iv.setImageResource(R.drawable.ic_mp_artist_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                String displayname = name;
                boolean isunknown = false;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = vh.itemView.getContext().getString(R.string.unknown_artist_name);
                    isunknown = true;
                }
                vh.line1.setText(displayname);

                int data1Idx = cursor.getColumnIndex("data1");
                int data2Idx = cursor.getColumnIndex("data2");
                int numalbums = data1Idx != -1 ? cursor.getInt(data1Idx) : 0;
                int numsongs = data2Idx != -1 ? cursor.getInt(data2Idx) : 0;
                
                String songs_albums = MusicUtils.makeAlbumsSongsLabel(vh.itemView.getContext(),
                        numalbums, numsongs, isunknown);
                
                vh.line2.setText(songs_albums);
            
            } else if (mimetype.equals("album")) {
                iv.setImageResource(R.drawable.albumart_mp_unknown_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                String displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = vh.itemView.getContext().getString(R.string.unknown_album_name);
                }
                vh.line1.setText(displayname);
                
                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = vh.itemView.getContext().getString(R.string.unknown_artist_name);
                }
                vh.line2.setText(displayname);
                
            } else if(mimetype.startsWith("audio/") ||
                    mimetype.equals("application/ogg") ||
                    mimetype.equals("application/x-ogg")) {
                iv.setImageResource(R.drawable.ic_mp_song_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE));
                vh.line1.setText(name);

                String displayname = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                if (displayname == null || displayname.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = vh.itemView.getContext().getString(R.string.unknown_artist_name);
                }
                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    name = vh.itemView.getContext().getString(R.string.unknown_album_name);
                }
                vh.line2.setText(displayname + " - " + name);
            }

            final int position = cursor.getPosition();
            final long id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
            vh.itemView.setOnClickListener(v -> {
                mActivity.onListItemClick(position, id);
            });
        }
        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mQueryCursor) {
                mActivity.mQueryCursor = cursor;
                super.changeCursor(cursor);
            }
        }
    }

    private Cursor mQueryCursor;
}
