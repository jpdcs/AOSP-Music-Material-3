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
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;

public class PlaylistBrowserActivity extends BaseActivity
    implements MusicUtils.Defs
{
    private static final String TAG = "PlaylistBrowserActivity";
    private static final int DELETE_PLAYLIST = CHILD_MENU_BASE + 1;
    private static final int EDIT_PLAYLIST = CHILD_MENU_BASE + 2;
    private static final int RENAME_PLAYLIST = CHILD_MENU_BASE + 3;
    private static final int CHANGE_WEEKS = CHILD_MENU_BASE + 4;
    private static final long RECENTLY_ADDED_PLAYLIST = -1;
    private static final long ALL_SONGS_PLAYLIST = -2;
    private static final long PODCASTS_PLAYLIST = -3;
    private PlaylistListAdapter mAdapter;
    boolean mAdapterSent;
    private static int mLastListPosCourse = -1;
    private static int mLastListPosFine = -1;

    private boolean mCreateShortcut;
    private ServiceToken mToken;
    private RecyclerView mPlaylistList;

    public PlaylistBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_CREATE_SHORTCUT.equals(action)) {
            mCreateShortcut = true;
        }

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mToken = MusicUtils.bindToService(this, new ServiceConnection() {
            public void onServiceConnected(ComponentName classname, IBinder obj) {
                if (Intent.ACTION_VIEW.equals(action)) {
                    Bundle b = intent.getExtras();
                    if (b == null) {
                        Log.w(TAG, "Unexpected:getExtras() returns null.");
                    } else {
                        try {
                            long id = Long.parseLong(b.getString("playlist"));
                            if (id == RECENTLY_ADDED_PLAYLIST) {
                                playRecentlyAdded();
                            } else if (id == PODCASTS_PLAYLIST) {
                                playPodcasts();
                            } else if (id == ALL_SONGS_PLAYLIST) {
                                long[] list = MusicUtils.getAllSongs(PlaylistBrowserActivity.this);
                                if (list != null) {
                                    MusicUtils.playAll(PlaylistBrowserActivity.this, list, 0);
                                }
                            } else {
                                MusicUtils.playPlaylist(PlaylistBrowserActivity.this, id);
                            }
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Playlist id missing or broken");
                        }
                    }
                    finish();
                    return;
                }
                MusicUtils.updateNowPlaying(PlaylistBrowserActivity.this);
            }

            public void onServiceDisconnected(ComponentName classname) {
            }
        
        });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        ContextCompat.registerReceiver(this, mScanListener, f, ContextCompat.RECEIVER_EXPORTED);

        setContentView(R.layout.media_picker_activity);
        MusicUtils.updateButtonBar(this, R.id.playlisttab);
        mPlaylistList = findViewById(R.id.list);
        mPlaylistList.setLayoutManager(new LinearLayoutManager(this));

        mAdapter = (PlaylistListAdapter) getLastCustomNonConfigurationInstance();
        if (mAdapter == null) {
            //Log.i("@@@", "starting query");
            mAdapter = new PlaylistListAdapter(
                    getApplication(),
                    this,
                    R.layout.track_list_item,
                    mPlaylistCursor,
                    new String[] { MediaStore.Audio.Playlists.NAME},
                    new int[] { android.R.id.text1 });
            mPlaylistList.setAdapter(mAdapter);
            setTitle(R.string.working_playlists);
            getPlaylistCursor(mAdapter.getQueryHandler(), null);
        } else {
            mAdapter.setActivity(this);
            mPlaylistList.setAdapter(mAdapter);
            mPlaylistCursor = mAdapter.getCursor();
            if (mPlaylistCursor != null) {
                init(mPlaylistCursor);
            } else {
                setTitle(R.string.working_playlists);
                getPlaylistCursor(mAdapter.getQueryHandler(), null);
            }
        }
    }
    
    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        PlaylistListAdapter a = mAdapter;
        mAdapterSent = true;
        return a;
    }
    
    @Override
    public void onDestroy() {
        if (mPlaylistList != null) {
            LinearLayoutManager lm = (LinearLayoutManager) mPlaylistList.getLayoutManager();
            if (lm != null) {
                mLastListPosCourse = lm.findFirstVisibleItemPosition();
                View cv = lm.findViewByPosition(mLastListPosCourse);
                if (cv != null) {
                    mLastListPosFine = cv.getTop();
                }
            }
        }
        MusicUtils.unbindFromService(mToken);
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        if (mPlaylistList != null) {
            mPlaylistList.setAdapter(null);
        }
        mAdapter = null;
        unregisterReceiver(mScanListener);
        super.onDestroy();
    }
    
    @Override
    public void onResume() {
        super.onResume();

        MusicUtils.setSpinnerState(this);
        MusicUtils.updateNowPlaying(PlaylistBrowserActivity.this);
    }
    @Override
    public void onPause() {
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(PlaylistBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getPlaylistCursor(mAdapter.getQueryHandler(), null);
            }
        }
    };
    public void init(Cursor cursor) {

        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(cursor);

        if (mPlaylistCursor == null) {
            MusicUtils.displayDatabaseError(this);
            closeContextMenu();
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }

        if (mLastListPosCourse >= 0) {
            mPlaylistList.scrollToPosition(mLastListPosCourse);
            mLastListPosCourse = -1;
        }
        MusicUtils.hideDatabaseError(this);
        MusicUtils.updateButtonBar(this, R.id.playlisttab);
        setTitle();
    }

    private void setTitle() {
        setTitle(R.string.playlists_title);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!mCreateShortcut) {
            menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle); // icon will be set in onPrepareOptionsMenu()
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MusicUtils.setPartyShuffleMenuIcon(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PARTY_SHUFFLE:
                MusicUtils.togglePartyShuffle();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showPopupMenu(View view, final int position, final long id) {
        if (mCreateShortcut) {
            return;
        }

        PopupMenu popup = new PopupMenu(this, view);
        Menu menu = popup.getMenu();

        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);

        if (id >= 0) {
            menu.add(0, DELETE_PLAYLIST, 0, R.string.delete_playlist_menu);
        }

        if (id == RECENTLY_ADDED_PLAYLIST) {
            menu.add(0, EDIT_PLAYLIST, 0, R.string.edit_playlist_menu);
        }

        if (id >= 0) {
            menu.add(0, RENAME_PLAYLIST, 0, R.string.rename_playlist_menu);
        }

        popup.setOnMenuItemClickListener(item -> handleContextItemSelected(item, id));
        popup.show();
    }

    private boolean handleContextItemSelected(MenuItem item, long id) {
        switch (item.getItemId()) {
            case PLAY_SELECTION:
                if (id == RECENTLY_ADDED_PLAYLIST) {
                    playRecentlyAdded();
                } else if (id == PODCASTS_PLAYLIST) {
                    playPodcasts();
                } else {
                    MusicUtils.playPlaylist(this, id);
                }
                break;
            case DELETE_PLAYLIST:
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id);
                getContentResolver().delete(uri, null, null);
                Toast.makeText(this, R.string.playlist_deleted_message, Toast.LENGTH_SHORT).show();
                if (mPlaylistCursor.getCount() == 0) {
                    setTitle(R.string.no_playlists_title);
                }
                break;
            case EDIT_PLAYLIST:
                if (id == RECENTLY_ADDED_PLAYLIST) {
                    Intent intent = new Intent();
                    intent.setClass(this, WeekSelector.class);
                    startActivityForResult(intent, CHANGE_WEEKS);
                    return true;
                } else {
                    Log.e(TAG, "should not be here");
                }
                break;
            case RENAME_PLAYLIST:
                Intent intent = new Intent();
                intent.setClass(this, RenamePlaylist.class);
                intent.putExtra("rename", id);
                startActivityForResult(intent, RENAME_PLAYLIST);
                break;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else if (mAdapter != null) {
                    getPlaylistCursor(mAdapter.getQueryHandler(), null);
                }
                break;
        }
    }

    public void onListItemClick(View v, int position, long id)
    {
        if (mCreateShortcut) {
            final Intent shortcut = new Intent();
            shortcut.setAction(Intent.ACTION_VIEW);
            shortcut.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/playlist");
            shortcut.putExtra("playlist", String.valueOf(id));

            final Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcut);
            intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, ((TextView) v.findViewById(R.id.line1)).getText());
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(
                    this, R.drawable.ic_launcher_shortcut_music_playlist));

            setResult(RESULT_OK, intent);
            finish();
            return;
        }
        if (id == RECENTLY_ADDED_PLAYLIST) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setPackage(getPackageName());
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("playlist", "recentlyadded");
            startActivity(intent);
        } else if (id == PODCASTS_PLAYLIST) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setPackage(getPackageName());
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("playlist", "podcasts");
            startActivity(intent);
        } else {
            Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setPackage(getPackageName());
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("playlist", Long.valueOf(id).toString());
            startActivity(intent);
        }
    }

    private void playRecentlyAdded() {
        int X = MusicUtils.getIntPref(this, "numweeks", 2) * (3600 * 24 * 7);
        final String[] ccols = new String[] { MediaStore.Audio.Media._ID};
        String where = MediaStore.MediaColumns.DATE_ADDED + ">" + (System.currentTimeMillis() / 1000 - X);
        Cursor cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ccols, where, null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
        
        if (cursor == null) {
            return;
        }
        try {
            int len = cursor.getCount();
            long [] list = new long[len];
            for (int i = 0; i < len; i++) {
                cursor.moveToNext();
                list[i] = cursor.getLong(0);
            }
            MusicUtils.playAll(this, list, 0);
        } catch (SQLiteException ex) {
        } finally {
            cursor.close();
        }
    }

    private void playPodcasts() {
        final String[] ccols = new String[] { MediaStore.Audio.Media._ID};
        Cursor cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ccols, MediaStore.Audio.Media.IS_PODCAST + "=1",
                null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
        
        if (cursor == null) {
            return;
        }
        try {
            int len = cursor.getCount();
            long [] list = new long[len];
            for (int i = 0; i < len; i++) {
                cursor.moveToNext();
                list[i] = cursor.getLong(0);
            }
            MusicUtils.playAll(this, list, 0);
        } catch (SQLiteException ex) {
        } finally {
            cursor.close();
        }
    }

    
    String[] mCols = new String[] {
            MediaStore.Audio.Playlists._ID,
            MediaStore.Audio.Playlists.NAME
    };

    private Cursor getPlaylistCursor(AsyncQueryHandler async, String filterstring) {

        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Playlists.NAME + " != ''");
        
        String [] keywords = null;
        if (filterstring != null) {
            String [] searchWords = filterstring.split(" ");
            keywords = new String[searchWords.length];
            Collator col = Collator.getInstance();
            col.setStrength(Collator.PRIMARY);
            for (int i = 0; i < searchWords.length; i++) {
                keywords[i] = '%' + searchWords[i] + '%';
            }
            for (int i = 0; i < searchWords.length; i++) {
                where.append(" AND ");
                where.append(MediaStore.Audio.Playlists.NAME + " LIKE ?");
            }
        }
        
        String whereclause = where.toString();
        
        
        if (async != null) {
            async.startQuery(0, null, MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                    mCols, whereclause, keywords, MediaStore.Audio.Playlists.NAME);
            return null;
        }
        Cursor c = null;
        c = MusicUtils.query(this, MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                mCols, whereclause, keywords, MediaStore.Audio.Playlists.NAME);
        
        return mergedCursor(c);
    }
    
    private Cursor mergedCursor(Cursor c) {
        if (c == null) {
            return null;
        }
        if (c instanceof MergeCursor) {
            Log.d("PlaylistBrowserActivity", "Already wrapped");
            return c;
        }
        MatrixCursor autoplaylistscursor = new MatrixCursor(mCols);
        if (mCreateShortcut) {
            ArrayList<Object> all = new ArrayList<Object>(2);
            all.add(ALL_SONGS_PLAYLIST);
            all.add(getString(R.string.play_all));
            autoplaylistscursor.addRow(all);
        }
        ArrayList<Object> recent = new ArrayList<Object>(2);
        recent.add(RECENTLY_ADDED_PLAYLIST);
        recent.add(getString(R.string.recentlyadded));
        autoplaylistscursor.addRow(recent);
        
        Cursor counter = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null, "is_podcast=1", null, null);
        if (counter != null) {
            int numpodcasts = counter.getCount();
            counter.close();
            if (numpodcasts > 0) {
                ArrayList<Object> podcasts = new ArrayList<Object>(2);
                podcasts.add(PODCASTS_PLAYLIST);
                podcasts.add(getString(R.string.podcasts_listitem));
                autoplaylistscursor.addRow(podcasts);
            }
        }

        Cursor cc = new MergeCursor(new Cursor [] {autoplaylistscursor, c});
        return cc;
    }
    
    static class PlaylistListAdapter extends CursorRecyclerViewAdapter<PlaylistListAdapter.ViewHolder> {
        int mTitleIdx;
        int mIdIdx;
        private PlaylistBrowserActivity mActivity = null;
        private AsyncQueryHandler mQueryHandler;
        private String mConstraint = null;
        private boolean mConstraintIsValid = false;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView line1;
            TextView line2;
            ImageView icon;
            ImageView play_indicator;
            ImageView drag_handle;

            ViewHolder(View v) {
                super(v);
                line1 = (TextView) v.findViewById(R.id.line1);
                line2 = (TextView) v.findViewById(R.id.line2);
                icon = (ImageView) v.findViewById(R.id.icon);
                play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
                drag_handle = (ImageView) v.findViewById(R.id.drag_handle);
            }
        }

        class QueryHandler extends AsyncQueryHandler {
            QueryHandler(ContentResolver res) {
                super(res);
            }
            
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                if (cursor != null) {
                    cursor = mActivity.mergedCursor(cursor);
                }
                mActivity.init(cursor);
            }
        }

        PlaylistListAdapter(Context context, PlaylistBrowserActivity currentactivity,
                int layout, Cursor cursor, String[] from, int[] to) {
            super(context, cursor);
            mActivity = currentactivity;
            getColumnIndices(cursor);
            mQueryHandler = new QueryHandler(context.getContentResolver());
        }
        private void getColumnIndices(Cursor cursor) {
            if (cursor != null) {
                mTitleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME);
                mIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID);
            }
        }

        public void setActivity(PlaylistBrowserActivity newactivity) {
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
            String name = cursor.getString(mTitleIdx);
            vh.line1.setText(name);
            
            long id = cursor.getLong(mIdIdx);
            
            if (id == RECENTLY_ADDED_PLAYLIST) {
                vh.icon.setImageResource(R.drawable.ic_mp_playlist_recently_added_list);
            } else {
                vh.icon.setImageResource(R.drawable.ic_mp_playlist_list);
            }
            ViewGroup.LayoutParams p = vh.icon.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;

            vh.play_indicator.setVisibility(View.GONE);
            vh.line2.setVisibility(View.GONE);
            if (vh.drag_handle != null) {
                vh.drag_handle.setVisibility(View.GONE);
            }

            final int position = cursor.getPosition();
            final long playlistId = id;
            vh.itemView.setOnClickListener(v -> {
                mActivity.onListItemClick(v, position, playlistId);
            });
            vh.itemView.setOnLongClickListener(v -> {
                mActivity.showPopupMenu(v, position, playlistId);
                return true;
            });
        }

        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mPlaylistCursor) {
                mActivity.mPlaylistCursor = cursor;
                super.changeCursor(cursor);
                getColumnIndices(cursor);
            }
        }
    }

    
    private Cursor mPlaylistCursor;
}
