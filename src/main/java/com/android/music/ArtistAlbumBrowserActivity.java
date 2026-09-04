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

import androidx.appcompat.widget.PopupMenu;
import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ImageView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;


public class ArtistAlbumBrowserActivity extends BaseActivity
        implements MusicUtils.Defs, ServiceConnection
{
    private String mCurrentArtistId;
    private String mCurrentArtistName;
    private String mCurrentAlbumId;
    private String mCurrentAlbumName;
    private String mCurrentArtistNameForAlbum;
    boolean mIsUnknownArtist;
    boolean mIsUnknownAlbum;
    private ArtistAlbumListAdapter mAdapter;
    private boolean mAdapterSent;
    private final static int SEARCH = CHILD_MENU_BASE;
    private static int mLastListPosCourse = -1;
    private static int mLastListPosFine = -1;
    private ServiceToken mToken;
    private RecyclerView mArtistList;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (icicle != null) {
            mCurrentAlbumId = icicle.getString("selectedalbum");
            mCurrentAlbumName = icicle.getString("selectedalbumname");
            mCurrentArtistId = icicle.getString("selectedartist");
            mCurrentArtistName = icicle.getString("selectedartistname");
        }
        mToken = MusicUtils.bindToService(this, this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        ContextCompat.registerReceiver(this, mScanListener, f, ContextCompat.RECEIVER_EXPORTED);

        setContentView(R.layout.media_picker_activity);
        MusicUtils.updateButtonBar(this, R.id.artisttab);
        mArtistList = findViewById(R.id.list);
        mArtistList.setLayoutManager(new LinearLayoutManager(this));

        mAdapter = (ArtistAlbumListAdapter) getLastCustomNonConfigurationInstance();
        if (mAdapter == null) {
            //Log.i("@@@", "starting query");
            mAdapter = new ArtistAlbumListAdapter(
                    getApplication(),
                    this,
                    null, // cursor
                    R.layout.track_list_item_group,
                    new String[] {},
                    new int[] {},
                    R.layout.track_list_item_child,
                    new String[] {},
                    new int[] {});
            mArtistList.setAdapter(mAdapter);
            setTitle(R.string.working_artists);
            getArtistCursor(mAdapter.getQueryHandler(), null);
        } else {
            mAdapter.setActivity(this);
            mArtistList.setAdapter(mAdapter);
            mArtistCursor = mAdapter.getCursor();
            if (mArtistCursor != null) {
                init(mArtistCursor);
            } else {
                getArtistCursor(mAdapter.getQueryHandler(), null);
            }
        }
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        mAdapterSent = true;
        return mAdapter;
    }
    
    @Override
    public void onSaveInstanceState(Bundle outcicle) {
        // need to store the selected item so we don't lose it in case
        // of an orientation switch. Otherwise we could lose it while
        // in the middle of specifying a playlist to add the item to.
        outcicle.putString("selectedalbum", mCurrentAlbumId);
        outcicle.putString("selectedalbumname", mCurrentAlbumName);
        outcicle.putString("selectedartist", mCurrentArtistId);
        outcicle.putString("selectedartistname", mCurrentArtistName);
        super.onSaveInstanceState(outcicle);
    }

    @Override
    public void onDestroy() {
        if (mArtistList != null) {
            LinearLayoutManager lm = (LinearLayoutManager) mArtistList.getLayoutManager();
            if (lm != null) {
                mLastListPosCourse = lm.findFirstVisibleItemPosition();
                View cv = lm.findViewByPosition(mLastListPosCourse);
                if (cv != null) {
                    mLastListPosFine = cv.getTop();
                }
            }
        }
        
        MusicUtils.unbindFromService(mToken);
        // If we have an adapter and didn't send it off to another activity yet, we should
        // close its cursor, which we do by assigning a null cursor to it. Doing this
        // instead of closing the cursor directly keeps the framework from accessing
        // the closed cursor later.
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        // Because we pass the adapter to the next activity, we need to make
        // sure it doesn't keep a reference to this activity. We can do this
        // by clearing its DatasetObservers, which setListAdapter(null) does.
        if (mArtistList != null) {
            mArtistList.setAdapter(null);
        }
        mAdapter = null;
        unregisterReceiver(mScanListener);
        super.onDestroy();
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
            MusicUtils.updateNowPlaying(ArtistAlbumBrowserActivity.this);
        }
    };
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(ArtistAlbumBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                MusicUtils.clearAlbumArtCache();
            }
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getArtistCursor(mAdapter.getQueryHandler(), null);
            }
        }
    };

    @Override
    public void onPause() {
        unregisterReceiver(mTrackListListener);
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }
    
    public void init(Cursor c) {

        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(c); // also sets mArtistCursor

        if (mArtistCursor == null) {
            MusicUtils.displayDatabaseError(this);
            closeContextMenu();
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }

        // restore previous position
        if (mLastListPosCourse >= 0) {
            mArtistList.scrollToPosition(mLastListPosCourse);
            mLastListPosCourse = -1;
        }

        MusicUtils.hideDatabaseError(this);
        MusicUtils.updateButtonBar(this, R.id.artisttab);
        setTitle();
    }

    private void setTitle() {
        setTitle(R.string.artists_title);
    }
    
    public void onListItemClick(int position, long id) {

        mCurrentArtistId = Long.valueOf(id).toString();
        
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setPackage(getPackageName());
        intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
        intent.putExtra("artist", mCurrentArtistId);
        startActivity(intent);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, SORT, 0, R.string.sort_menu).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle); // icon will be set in onPrepareOptionsMenu()
        menu.add(0, SHUFFLE_ALL, 0, R.string.shuffle_all).setIcon(R.drawable.ic_menu_shuffle);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MusicUtils.setPartyShuffleMenuIcon(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        Cursor cursor;
        switch (item.getItemId()) {
            case PARTY_SHUFFLE:
                MusicUtils.togglePartyShuffle();
                break;
                
            case SHUFFLE_ALL:
                cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String [] { MediaStore.Audio.Media._ID}, 
                        MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                        MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
                if (cursor != null) {
                    MusicUtils.shuffleAll(this, cursor);
                    cursor.close();
                }
                return true;

            case SORT:
                showSortMenu();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortMenu() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        PopupMenu popup = new PopupMenu(this, toolbar != null ? toolbar : mArtistList);
        popup.getMenuInflater().inflate(R.menu.sort_options, popup.getMenu());

        // Unsupported sorts for Artists list
        popup.getMenu().findItem(R.id.sort_date_asc).setVisible(false);
        popup.getMenu().findItem(R.id.sort_date_desc).setVisible(false);
        popup.getMenu().findItem(R.id.sort_artist_asc).setVisible(false);
        popup.getMenu().findItem(R.id.sort_artist_desc).setVisible(false);

        popup.setOnMenuItemClickListener(item -> {
            String sortOrder = MusicUtils.ARTIST_KEY;
            int itemId = item.getItemId();
            if (itemId == R.id.sort_name_asc) {
                sortOrder = MediaStore.Audio.Artists.ARTIST + " ASC";
            } else if (itemId == R.id.sort_name_desc) {
                sortOrder = MediaStore.Audio.Artists.ARTIST + " DESC";
            }
            MusicUtils.setStringPref(this, "artist_sort_order", sortOrder);
            getArtistCursor(mAdapter.getQueryHandler(), null);
            return true;
        });
        popup.show();
    }

    private void showPopupMenu(View view, final int position) {
        PopupMenu popup = new PopupMenu(this, view);
        Menu menu = popup.getMenu();

        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
        SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
        MusicUtils.makePlaylistMenu(this, sub);
        menu.add(0, DELETE_ITEM, 0, R.string.delete_item);

        mArtistCursor.moveToPosition(position);
        mCurrentArtistId = mArtistCursor.getString(mArtistCursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID));
        mCurrentArtistName = mArtistCursor.getString(mArtistCursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST));
        mCurrentAlbumId = null;
        mIsUnknownArtist = mCurrentArtistName == null ||
                mCurrentArtistName.equals(MediaStore.UNKNOWN_STRING);
        mIsUnknownAlbum = true;

        if (!mIsUnknownArtist) {
            menu.add(0, SEARCH, 0, R.string.search_title);
        }

        popup.setOnMenuItemClickListener(this::handleContextItemSelected);
        popup.show();
    }

    private boolean handleContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_SELECTION: {
                // play everything by the selected artist
                long [] list = MusicUtils.getSongListForArtist(this, Long.parseLong(mCurrentArtistId));
                MusicUtils.playAll(this, list, 0);
                return true;
            }

            case QUEUE: {
                long [] list = MusicUtils.getSongListForArtist(this, Long.parseLong(mCurrentArtistId));
                MusicUtils.addToCurrentPlaylist(this, list);
                return true;
            }

            case NEW_PLAYLIST: {
                Intent intent = new Intent();
                intent.setClass(this, CreatePlaylist.class);
                startActivityForResult(intent, NEW_PLAYLIST);
                return true;
            }

            case PLAYLIST_SELECTED: {
                long [] list = MusicUtils.getSongListForArtist(this, Long.parseLong(mCurrentArtistId));
                long playlist = item.getIntent().getLongExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }
            
            case DELETE_ITEM: {
                long [] list;
                String desc;
                list = MusicUtils.getSongListForArtist(this, Long.parseLong(mCurrentArtistId));
                String f;
                if (android.os.Environment.isExternalStorageRemovable()) {
                    f = getString(R.string.delete_artist_desc);
                } else {
                    f = getString(R.string.delete_artist_desc_nosdcard);
                }
                desc = String.format(f, mCurrentArtistName);
                Bundle b = new Bundle();
                b.putString("description", desc);
                b.putLongArray("items", list);
                Intent intent = new Intent();
                intent.setClass(this, DeleteItems.class);
                intent.putExtras(b);
                startActivityForResult(intent, -1);
                return true;
            }
            
            case SEARCH:
                doSearch();
                return true;
        }
        return true;
    }

    void doSearch() {
        CharSequence title = null;
        String query = null;
        
        Intent i = new Intent();
        i.setAction(MediaStore.INTENT_ACTION_MEDIA_SEARCH);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        title = mCurrentArtistName;
        query = mCurrentArtistName;
        i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, mCurrentArtistName);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE);
        title = getString(R.string.mediasearch, title);
        i.putExtra(SearchManager.QUERY, query);

        startActivity(Intent.createChooser(i, title));
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else {
                    getArtistCursor(mAdapter.getQueryHandler(), null);
                }
                break;

            case NEW_PLAYLIST:
                if (resultCode == RESULT_OK) {
                    Uri uri = intent.getData();
                    if (uri != null) {
                        long [] list = MusicUtils.getSongListForArtist(this, Long.parseLong(mCurrentArtistId));
                        MusicUtils.addToPlaylist(this, list, Long.parseLong(uri.getLastPathSegment()));
                    }
                }
                break;
        }
    }

    private Cursor getArtistCursor(AsyncQueryHandler async, String filter) {

        String[] cols = new String[] {
                MediaStore.Audio.Artists._ID,
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
                MediaStore.Audio.Artists.NUMBER_OF_TRACKS
        };

        Uri uri = MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI;
        if (!TextUtils.isEmpty(filter)) {
            uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
        }

        String sortOrder = MusicUtils.getStringPref(this, "artist_sort_order", MusicUtils.ARTIST_KEY);

        Cursor ret = null;
        if (async != null) {
            async.startQuery(0, null, uri,
                    cols, null , null, sortOrder);
        } else {
            ret = MusicUtils.query(this, uri,
                    cols, null , null, sortOrder);
        }
        return ret;
    }
    
    static class ArtistAlbumListAdapter extends CursorRecyclerViewAdapter<ArtistAlbumListAdapter.ViewHolder> implements SectionIndexer {
        
        private final Drawable mNowPlayingOverlay;
        private int mGroupArtistIdIdx;
        private int mGroupArtistIdx;
        private int mGroupAlbumIdx;
        private int mGroupSongIdx;
        private final Resources mResources;
        private final String mUnknownArtist;
        private MusicAlphabetIndexer mIndexer;
        private ArtistAlbumBrowserActivity mActivity;
        private AsyncQueryHandler mQueryHandler;
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView line1;
            TextView line2;
            ImageView play_indicator;
            ImageView icon;

            ViewHolder(View v) {
                super(v);
                line1 = (TextView) v.findViewById(R.id.line1);
                line2 = (TextView) v.findViewById(R.id.line2);
                play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
                icon = (ImageView) v.findViewById(R.id.icon);
            }
        }

        class QueryHandler extends AsyncQueryHandler {
            QueryHandler(ContentResolver res) {
                super(res);
            }
            
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                //Log.i("@@@", "query complete");
                mActivity.init(cursor);
            }
        }

        ArtistAlbumListAdapter(Context context, ArtistAlbumBrowserActivity currentactivity,
                Cursor cursor, int glayout, String[] gfrom, int[] gto, 
                int clayout, String[] cfrom, int[] cto) {
            super(context, cursor);
            mActivity = currentactivity;
            mQueryHandler = new QueryHandler(context.getContentResolver());

            Resources r = context.getResources();
            mNowPlayingOverlay = ContextCompat.getDrawable(context, R.drawable.indicator_ic_mp_playing_list);
            
            getColumnIndices(cursor);
            mResources = context.getResources();
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
        }
        
        private void getColumnIndices(Cursor cursor) {
            if (cursor != null) {
                mGroupArtistIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID);
                mGroupArtistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST);
                mGroupAlbumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS);
                mGroupSongIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.NUMBER_OF_TRACKS);
                if (mIndexer != null) {
                    mIndexer.setCursor(cursor);
                } else {
                    mIndexer = new MusicAlphabetIndexer(cursor, mGroupArtistIdx, 
                            mResources.getString(R.string.fast_scroll_alphabet));
                }
            }
        }
        
        public void setActivity(ArtistAlbumBrowserActivity newactivity) {
            mActivity = newactivity;
        }
        
        public AsyncQueryHandler getQueryHandler() {
            return mQueryHandler;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.track_list_item, parent, false);
            ViewHolder vh = new ViewHolder(v);
            vh.icon.setVisibility(View.GONE);
            return vh;
        }

        @Override
        public void onBindViewHolder(ViewHolder vh, Cursor cursor) {
            String artist = cursor.getString(mGroupArtistIdx);
            String displayartist = artist;
            boolean unknown = artist == null || artist.equals(MediaStore.UNKNOWN_STRING);
            if (unknown) {
                displayartist = mUnknownArtist;
            }
            vh.line1.setText(displayartist);

            int numalbums = cursor.getInt(mGroupAlbumIdx);
            int numsongs = cursor.getInt(mGroupSongIdx);
            
            String songs_albums = MusicUtils.makeAlbumsLabel(vh.itemView.getContext(),
                    numalbums, numsongs, unknown);
            
            vh.line2.setText(songs_albums);
            
            long currentartistid = MusicUtils.getCurrentArtistId();
            long artistid = cursor.getLong(mGroupArtistIdIdx);
            if (currentartistid == artistid) {
                vh.play_indicator.setImageDrawable(mNowPlayingOverlay);
                vh.play_indicator.setVisibility(View.VISIBLE);
            } else {
                vh.play_indicator.setVisibility(View.GONE);
            }

            final int position = cursor.getPosition();
            vh.itemView.setOnClickListener(v -> {
                mActivity.onListItemClick(position, artistid);
            });
            vh.itemView.setOnLongClickListener(v -> {
                mActivity.showPopupMenu(v, position);
                return true;
            });
        }
        
        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mArtistCursor) {
                mActivity.mArtistCursor = cursor;
                getColumnIndices(cursor);
                super.changeCursor(cursor);
            }
        }
        
        public Object[] getSections() {
            return mIndexer.getSections();
        }
        
        public int getPositionForSection(int sectionIndex) {
            return mIndexer.getPositionForSection(sectionIndex);
        }
        
        public int getSectionForPosition(int position) {
            return 0;
        }
    }
    
    private Cursor mArtistCursor;

    public void onServiceConnected(ComponentName name, IBinder service) {
        MusicUtils.updateNowPlaying(this);
    }

    public void onServiceDisconnected(ComponentName name) {
        finish();
    }
}
