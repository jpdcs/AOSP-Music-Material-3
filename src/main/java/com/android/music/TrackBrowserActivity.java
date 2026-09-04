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

import androidx.appcompat.widget.PopupMenu;
import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.AbstractCursor;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.Playlists;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AlphabetIndexer;
import android.widget.ImageView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.google.android.material.color.MaterialColors;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.music.MusicUtils.ServiceToken;

import java.text.Collator;
import java.util.Arrays;

public class TrackBrowserActivity extends BaseActivity
        implements MusicUtils.Defs, ServiceConnection
{
    private static final int Q_SELECTED = CHILD_MENU_BASE;
    private static final int Q_ALL = CHILD_MENU_BASE + 1;
    private static final int SAVE_AS_PLAYLIST = CHILD_MENU_BASE + 2;
    private static final int PLAY_ALL = CHILD_MENU_BASE + 3;
    private static final int CLEAR_PLAYLIST = CHILD_MENU_BASE + 4;
    private static final int REMOVE = CHILD_MENU_BASE + 5;
    private static final int SEARCH = CHILD_MENU_BASE + 6;

    private static final String LOGTAG = "TrackBrowser";

    private String[] mCursorCols;
    private String[] mPlaylistMemberCols;
    private boolean mDeletedOneRow = false;
    private boolean mEditMode = false;
    private String mCurrentTrackName;
    private String mCurrentAlbumName;
    private String mCurrentArtistNameForAlbum;
    private RecyclerView mTrackList;
    private ItemTouchHelper mItemTouchHelper;
    private boolean mIsDragging = false;
    private Cursor mTrackCursor;
    private TrackListAdapter mAdapter;
    private boolean mAdapterSent = false;
    private String mAlbumId;
    private String mArtistId;
    private String mPlaylist;
    private String mGenre;
    private String mSortOrder;
    private int mSelectedPosition;
    private long mSelectedId;
    private static int mLastListPosCourse = -1;
    private static int mLastListPosFine = -1;
    private boolean mUseLastListPos = false;
    private ServiceToken mToken;

    public TrackBrowserActivity()
    {
    }

    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.getBooleanExtra("withtabs", false)) {
                requestWindowFeature(Window.FEATURE_NO_TITLE);
            }
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (icicle != null) {
            mSelectedId = icicle.getLong("selectedtrack");
            mAlbumId = icicle.getString("album");
            mArtistId = icicle.getString("artist");
            mPlaylist = icicle.getString("playlist");
            mGenre = icicle.getString("genre");
            mEditMode = icicle.getBoolean("editmode", false);
        } else {
            mAlbumId = intent.getStringExtra("album");
            mArtistId = intent.getStringExtra("artist");
            mPlaylist = intent.getStringExtra("playlist");
            mGenre = intent.getStringExtra("genre");
            mEditMode = Intent.ACTION_EDIT.equals(intent.getAction());
        }

        mCursorCols = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION
        };
        mPlaylistMemberCols = new String[] {
                MediaStore.Audio.Playlists.Members._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                MediaStore.Audio.Playlists.Members.AUDIO_ID,
                MediaStore.Audio.Media.IS_MUSIC
        };

        setContentView(R.layout.media_picker_activity);
        mUseLastListPos = MusicUtils.updateButtonBar(this, R.id.songtab);
        mTrackList = findViewById(R.id.list);
        mTrackList.setLayoutManager(new LinearLayoutManager(this));

        if (mPlaylist != null && mPlaylist.equals("nowplaying")) {
            mItemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean isLongPressDragEnabled() {
                    return false;
                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                    super.onSelectedChanged(viewHolder, actionState);
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        mIsDragging = true;
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    mIsDragging = false;
                    // Trigger a final sync just in case
                    if (mAdapter != null && MusicUtils.sService != null) {
                        Cursor c = new NowPlayingCursor(MusicUtils.sService, mCursorCols);
                        mAdapter.changeCursor(c);
                    }
                }

                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                    int from = viewHolder.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    if (mTrackCursor instanceof NowPlayingCursor) {
                        ((NowPlayingCursor) mTrackCursor).moveItem(from, to);
                        mAdapter.notifyItemMoved(from, to);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                }
            });
            mItemTouchHelper.attachToRecyclerView(mTrackList);
        }

        mAdapter = (TrackListAdapter) getLastCustomNonConfigurationInstance();
        
        if (mAdapter != null) {
            mAdapter.setActivity(this);
            mTrackList.setAdapter(mAdapter);
        }
        mToken = MusicUtils.bindToService(this, this);

        mTrackList.post(new Runnable() {
            public void run() {
                setAlbumArtBackground();
            }
        });
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service)
    {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        ContextCompat.registerReceiver(this, mScanListener, f, ContextCompat.RECEIVER_EXPORTED);

        if (mAdapter == null) {
            mAdapter = new TrackListAdapter(
                    this,
                    this,
                    null,
                    "nowplaying".equals(mPlaylist),
                    mPlaylist != null &&
                    !(mPlaylist.equals("podcasts") || mPlaylist.equals("recentlyadded")));
            mTrackList.setAdapter(mAdapter);
            setTitle(R.string.working_songs);
            getTrackCursor(mAdapter.getQueryHandler(), null, true);
        } else {
            mTrackCursor = mAdapter.getCursor();
            if (mTrackCursor != null) {
                init(mTrackCursor, false);
            } else {
                setTitle(R.string.working_songs);
                getTrackCursor(mAdapter.getQueryHandler(), null, true);
            }
        }
        if (!mEditMode) {
            MusicUtils.updateNowPlaying(this);
        }
    }
    
    @Override
    public void onServiceDisconnected(ComponentName name) {
        finish();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        mAdapterSent = true;
        return mAdapter;
    }
    
    @Override
    public void onDestroy() {
        if (mTrackList != null) {
            if (mUseLastListPos) {
                LinearLayoutManager lm = (LinearLayoutManager) mTrackList.getLayoutManager();
                if (lm != null) {
                    mLastListPosCourse = lm.findFirstVisibleItemPosition();
                    View cv = lm.findViewByPosition(mLastListPosCourse);
                    if (cv != null) {
                        mLastListPosFine = cv.getTop();
                    }
                }
            }
        }

        MusicUtils.unbindFromService(mToken);
        try {
            if ("nowplaying".equals(mPlaylist)) {
                unregisterReceiverSafe(mNowPlayingListener);
            } else {
                unregisterReceiverSafe(mTrackListListener);
            }
        } catch (IllegalArgumentException ex) {
        }
        
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        mTrackList.setAdapter(null);
        mAdapter = null;
        unregisterReceiverSafe(mScanListener);
        super.onDestroy();
    }
    
    private void unregisterReceiverSafe(BroadcastReceiver receiver) {
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        if (mTrackCursor != null && mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        MusicUtils.setSpinnerState(this);
    }
    @Override
    public void onPause() {
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }
    
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_SCANNER_STARTED.equals(action) ||
                    Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
                MusicUtils.setSpinnerState(TrackBrowserActivity.this);
            }
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getTrackCursor(mAdapter.getQueryHandler(), null, true);
            }
        }
    };
    
    public void onSaveInstanceState(Bundle outcicle) {
        outcicle.putLong("selectedtrack", mSelectedId);
        outcicle.putString("artist", mArtistId);
        outcicle.putString("album", mAlbumId);
        outcicle.putString("playlist", mPlaylist);
        outcicle.putString("genre", mGenre);
        outcicle.putBoolean("editmode", mEditMode);
        super.onSaveInstanceState(outcicle);
    }
    
    public void init(Cursor newCursor, boolean isLimited) {

        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(newCursor); 
        
        if (mTrackCursor == null) {
            MusicUtils.displayDatabaseError(this);
            closeContextMenu();
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }

        MusicUtils.hideDatabaseError(this);
        mUseLastListPos = MusicUtils.updateButtonBar(this, R.id.songtab);
        setTitle();

        if (mLastListPosCourse >= 0 && mUseLastListPos) {
            if (!isLimited) {
                mTrackList.scrollToPosition(mLastListPosCourse);
                mLastListPosCourse = -1;
            }
        }

        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        if ("nowplaying".equals(mPlaylist)) {
            try {
                int cur = MusicUtils.sService.getQueuePosition();
                mTrackList.scrollToPosition(cur);
                ContextCompat.registerReceiver(this, mNowPlayingListener, new IntentFilter(f), ContextCompat.RECEIVER_NOT_EXPORTED);
                mNowPlayingListener.onReceive(this, new Intent(MediaPlaybackService.META_CHANGED));
            } catch (RemoteException ex) {
            }
        } else {
            String key = getIntent().getStringExtra("artist");
            if (key != null) {
                int keyidx = mTrackCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID);
                mTrackCursor.moveToFirst();
                while (! mTrackCursor.isAfterLast()) {
                    String artist = mTrackCursor.getString(keyidx);
                    if (artist.equals(key)) {
                        mTrackList.scrollToPosition(mTrackCursor.getPosition());
                        break;
                    }
                    mTrackCursor.moveToNext();
                }
            }
            ContextCompat.registerReceiver(this, mTrackListListener, new IntentFilter(f), ContextCompat.RECEIVER_NOT_EXPORTED);
            mTrackListListener.onReceive(this, new Intent(MediaPlaybackService.META_CHANGED));
        }
    }

    private void setAlbumArtBackground() {
        if (!mEditMode) {
            try {
                long albumid = Long.valueOf(mAlbumId);
                Bitmap bm = MusicUtils.getArtwork(TrackBrowserActivity.this, -1, albumid, false);
                if (bm != null) {
                    MusicUtils.setBackground(mTrackList, bm);
                    if (mAdapter != null) {
                        mAdapter.setHasBackground(true);
                    }
                    return;
                }
            } catch (Exception ex) {
            }
        }
        mTrackList.setBackgroundColor(0);
        if (mAdapter != null) {
            mAdapter.setHasBackground(false);
        }
    }

    private void setTitle() {

        CharSequence fancyName = null;
        if (mAlbumId != null) {
            int numresults = mTrackCursor != null ? mTrackCursor.getCount() : 0;
            if (numresults > 0) {
                mTrackCursor.moveToFirst();
                int idx = mTrackCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                fancyName = mTrackCursor.getString(idx);
                String where = MediaStore.Audio.Media.ALBUM_ID + "='" + mAlbumId +
                        "' AND " + MediaStore.Audio.Media.ARTIST_ID + "=" + 
                        mTrackCursor.getLong(mTrackCursor.getColumnIndexOrThrow(
                                MediaStore.Audio.Media.ARTIST_ID));
                Cursor cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.Audio.Media.ALBUM}, where, null, null);
                if (cursor != null) {
                    if (cursor.getCount() != numresults) {
                        fancyName = mTrackCursor.getString(idx);
                    }    
                    cursor.deactivate();
                }
                if (fancyName == null || fancyName.equals(MediaStore.UNKNOWN_STRING)) {
                    fancyName = getString(R.string.unknown_album_name);
                }
            }
        } else if (mPlaylist != null) {
            if (mPlaylist.equals("nowplaying")) {
                if (MusicUtils.getCurrentShuffleMode() == MediaPlaybackService.SHUFFLE_AUTO) {
                    fancyName = getText(R.string.partyshuffle_title);
                } else {
                    fancyName = getText(R.string.nowplaying_title);
                }
            } else if (mPlaylist.equals("podcasts")){
                fancyName = getText(R.string.podcasts_title);
            } else if (mPlaylist.equals("recentlyadded")){
                fancyName = getText(R.string.recentlyadded_title);
            } else {
                String [] cols = new String [] {
                MediaStore.Audio.Playlists.NAME
                };
                Cursor cursor = MusicUtils.query(this,
                        ContentUris.withAppendedId(Playlists.EXTERNAL_CONTENT_URI, Long.valueOf(mPlaylist)),
                        cols, null, null, null);
                if (cursor != null) {
                    if (cursor.getCount() != 0) {
                        cursor.moveToFirst();
                        fancyName = cursor.getString(0);
                    }
                    cursor.deactivate();
                }
            }
        } else if (mGenre != null) {
            String [] cols = new String [] {
            MediaStore.Audio.Genres.NAME
            };
            Cursor cursor = MusicUtils.query(this,
                    ContentUris.withAppendedId(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, Long.valueOf(mGenre)),
                    cols, null, null, null);
            if (cursor != null) {
                if (cursor.getCount() != 0) {
                    cursor.moveToFirst();
                    fancyName = cursor.getString(0);
                }
                cursor.deactivate();
            }
        }

        if (fancyName != null) {
            setTitle(fancyName);
        } else {
            setTitle(R.string.tracks_title);
        }
    }
    
    private void removePlaylistItem(int which) {
        View v = mTrackList.getLayoutManager().findViewByPosition(which);
        if (v == null) {
            Log.d(LOGTAG, "No view when removing playlist item " + which);
            return;
        }
        try {
            if (MusicUtils.sService != null
                    && which != MusicUtils.sService.getQueuePosition()) {
                mDeletedOneRow = true;
            }
        } catch (RemoteException e) {
            mDeletedOneRow = true;
        }
        v.setVisibility(View.GONE);
        if (mTrackCursor instanceof NowPlayingCursor) {
            ((NowPlayingCursor)mTrackCursor).removeItem(which);
        } else {
            int colidx = mTrackCursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Playlists.Members._ID);
            mTrackCursor.moveToPosition(which);
            long id = mTrackCursor.getLong(colidx);
            Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                    Long.valueOf(mPlaylist));
            getContentResolver().delete(
                    ContentUris.withAppendedId(uri, id), null, null);
        }
        v.setVisibility(View.VISIBLE);
        mAdapter.notifyDataSetChanged();
    }
    
    private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            if (!mEditMode) {
                MusicUtils.updateNowPlaying(TrackBrowserActivity.this);
            }
        }
    };

    private BroadcastReceiver mNowPlayingListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(MediaPlaybackService.META_CHANGED)) {
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
                MusicUtils.updateNowPlaying(TrackBrowserActivity.this);
            } else if (intent.getAction().equals(MediaPlaybackService.QUEUE_CHANGED)) {
                if (mIsDragging) {
                    return;
                }
                if (mDeletedOneRow) {
                    mDeletedOneRow = false;
                    return;
                }
                if (MusicUtils.sService == null) {
                    finish();
                    return;
                }
                if (mAdapter != null) {
                    Cursor c = new NowPlayingCursor(MusicUtils.sService, mCursorCols);
                    if (c.getCount() == 0) {
                        finish();
                        return;
                    }
                    mAdapter.changeCursor(c);
                }
            }
        }
    };

    private boolean isMusic(Cursor c) {
        int titleidx = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
        int albumidx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
        int artistidx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);

        String title = c.getString(titleidx);
        String album = c.getString(albumidx);
        String artist = c.getString(artistidx);
        if (MediaStore.UNKNOWN_STRING.equals(album) &&
                MediaStore.UNKNOWN_STRING.equals(artist) &&
                title != null &&
                title.startsWith("recording")) {
            return false;
        }

        int ismusic_idx = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC);
        boolean ismusic = true;
        if (ismusic_idx >= 0) {
            ismusic = mTrackCursor.getInt(ismusic_idx) != 0;
        }
        return ismusic;
    }

    private void showPopupMenu(View view, final int position) {
        PopupMenu popup = new PopupMenu(this, view);
        Menu menu = popup.getMenu();

        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
        SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
        MusicUtils.makePlaylistMenu(this, sub);
        if (mEditMode) {
            menu.add(0, REMOVE, 0, R.string.remove_from_playlist);
        }
        menu.add(0, USE_AS_RINGTONE, 0, R.string.ringtone_menu);
        menu.add(0, DELETE_ITEM, 0, R.string.delete_item);

        mTrackCursor.moveToPosition(position);
        try {
            int id_idx = mTrackCursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Playlists.Members.AUDIO_ID);
            mSelectedId = mTrackCursor.getLong(id_idx);
        } catch (IllegalArgumentException ex) {
            mSelectedId = mTrackCursor.getLong(mTrackCursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        }

        mSelectedPosition = position;
        mCurrentAlbumName = mTrackCursor.getString(mTrackCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ALBUM));
        mCurrentArtistNameForAlbum = mTrackCursor.getString(mTrackCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST));
        mCurrentTrackName = mTrackCursor.getString(mTrackCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.TITLE));

        if (isMusic(mTrackCursor)) {
            menu.add(0, SEARCH, 0, R.string.search_title);
        }

        popup.setOnMenuItemClickListener(this::handleContextItemSelected);
        popup.show();
    }

    private boolean handleContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_SELECTION: {
                int position = mSelectedPosition;
                MusicUtils.playAll(this, mTrackCursor, position);
                return true;
            }

            case QUEUE: {
                long [] list = new long[] { mSelectedId };
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
                long [] list = new long[] { mSelectedId };
                long playlist = item.getIntent().getLongExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }

            case USE_AS_RINGTONE:
                MusicUtils.setRingtone(this, mSelectedId);
                return true;

            case DELETE_ITEM: {
                long [] list = new long[1];
                list[0] = (int) mSelectedId;
                Bundle b = new Bundle();
                String f;
                if (android.os.Environment.isExternalStorageRemovable()) {
                    f = getString(R.string.delete_song_desc); 
                } else {
                    f = getString(R.string.delete_song_desc_nosdcard); 
                }
                String desc = String.format(f, mCurrentTrackName);
                b.putString("description", desc);
                b.putLongArray("items", list);
                Intent intent = new Intent();
                intent.setClass(this, DeleteItems.class);
                intent.putExtras(b);
                startActivityForResult(intent, -1);
                return true;
            }
            
            case REMOVE:
                removePlaylistItem(mSelectedPosition);
                return true;
                
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
        
        title = mCurrentTrackName;
        if (MediaStore.UNKNOWN_STRING.equals(mCurrentArtistNameForAlbum)) {
            query = mCurrentTrackName;
        } else {
            query = mCurrentArtistNameForAlbum + " " + mCurrentTrackName;
            i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, mCurrentArtistNameForAlbum);
        }
        if (MediaStore.UNKNOWN_STRING.equals(mCurrentAlbumName)) {
            i.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, mCurrentAlbumName);
        }
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "audio/*");
        title = getString(R.string.mediasearch, title);
        i.putExtra(SearchManager.QUERY, query);

        startActivity(Intent.createChooser(i, title));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // RecyclerView selection is complex, skipping for now
        return super.dispatchKeyEvent(event);
    }

    private void removeItem() {
        // RecyclerView implementation would be different
    }
    
    private void moveItem(boolean up) {
        // RecyclerView implementation would be different
    }
    
    public void onListItemClick(int position, long id)
    {
        if (mTrackCursor.getCount() == 0) {
            return;
        }
        if (mTrackCursor instanceof NowPlayingCursor) {
            if (MusicUtils.sService != null) {
                try {
                    MusicUtils.sService.setQueuePosition(position);
                    return;
                } catch (RemoteException ex) {
                }
            }
        }
        MusicUtils.playAll(this, mTrackCursor, position);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (mPlaylist == null || (!mPlaylist.equals("nowplaying") && !mPlaylist.equals("recentlyadded"))) {
            menu.add(0, SORT, 0, R.string.sort_menu).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (mPlaylist == null) {
            menu.add(0, PLAY_ALL, 0, R.string.play_all).setIcon(R.drawable.ic_menu_play_clip);
        }
        menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle); 
        menu.add(0, SHUFFLE_ALL, 0, R.string.shuffle_all).setIcon(R.drawable.ic_menu_shuffle);
        if (mPlaylist != null) {
            menu.add(0, SAVE_AS_PLAYLIST, 0, R.string.save_as_playlist).setIcon(android.R.drawable.ic_menu_save);
            if (mPlaylist.equals("nowplaying")) {
                menu.add(0, CLEAR_PLAYLIST, 0, R.string.clear_playlist).setIcon(R.drawable.ic_menu_clear_playlist);
            }
        }
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
            case PLAY_ALL: {
                MusicUtils.playAll(this, mTrackCursor);
                return true;
            }

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
                
            case SAVE_AS_PLAYLIST:
                intent = new Intent();
                intent.setClass(this, CreatePlaylist.class);
                startActivityForResult(intent, SAVE_AS_PLAYLIST);
                return true;
                
            case CLEAR_PLAYLIST:
                MusicUtils.clearQueue();
                return true;

            case SORT:
                showSortMenu();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortMenu() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        PopupMenu popup = new PopupMenu(this, toolbar != null ? toolbar : mTrackList);
        popup.getMenuInflater().inflate(R.menu.sort_options, popup.getMenu());

        if (mArtistId != null) {
            popup.getMenu().findItem(R.id.sort_artist_asc).setVisible(false);
            popup.getMenu().findItem(R.id.sort_artist_desc).setVisible(false);
        }

        popup.setOnMenuItemClickListener(item -> {
            String sortOrder = MediaStore.Audio.Media.DEFAULT_SORT_ORDER;
            int itemId = item.getItemId();
            if (itemId == R.id.sort_name_asc) {
                sortOrder = MediaStore.Audio.Media.TITLE + " ASC";
            } else if (itemId == R.id.sort_name_desc) {
                sortOrder = MediaStore.Audio.Media.TITLE + " DESC";
            } else if (itemId == R.id.sort_date_asc) {
                sortOrder = MediaStore.MediaColumns.DATE_ADDED + " ASC";
            } else if (itemId == R.id.sort_date_desc) {
                sortOrder = MediaStore.MediaColumns.DATE_ADDED + " DESC";
            } else if (itemId == R.id.sort_artist_asc) {
                sortOrder = MediaStore.Audio.Media.ARTIST + " ASC";
            } else if (itemId == R.id.sort_artist_desc) {
                sortOrder = MediaStore.Audio.Media.ARTIST + " DESC";
            }

            MusicUtils.setStringPref(this, "track_sort_order", sortOrder);
            getTrackCursor(mAdapter.getQueryHandler(), null, true);
            return true;
        });
        popup.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else {
                    getTrackCursor(mAdapter.getQueryHandler(), null, true);
                }
                break;
                
            case NEW_PLAYLIST:
                if (resultCode == RESULT_OK) {
                    Uri uri = intent.getData();
                    if (uri != null) {
                        long [] list = new long[] { mSelectedId };
                        MusicUtils.addToPlaylist(this, list, Integer.valueOf(uri.getLastPathSegment()));
                    }
                }
                break;

            case SAVE_AS_PLAYLIST:
                if (resultCode == RESULT_OK) {
                    Uri uri = intent.getData();
                    if (uri != null) {
                        long [] list = MusicUtils.getSongListForCursor(mTrackCursor);
                        int plid = Integer.parseInt(uri.getLastPathSegment());
                        MusicUtils.addToPlaylist(this, list, plid);
                    }
                }
                break;
        }
    }
    
    private Cursor getTrackCursor(TrackListAdapter.TrackQueryHandler queryhandler, String filter,
            boolean async) {

        if (queryhandler == null) {
            throw new IllegalArgumentException();
        }

        Cursor ret = null;
        mSortOrder = MusicUtils.getStringPref(this, "track_sort_order", MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Media.TITLE + " != ''");

        if (mGenre != null) {
            Uri uri = MediaStore.Audio.Genres.Members.getContentUri("external",
                    Integer.valueOf(mGenre));
            if (!TextUtils.isEmpty(filter)) {
                uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
            }
            mSortOrder = MediaStore.Audio.Genres.Members.DEFAULT_SORT_ORDER;
            ret = queryhandler.doQuery(uri,
                    mCursorCols, where.toString(), null, mSortOrder, async);
        } else if (mPlaylist != null) {
            if (mPlaylist.equals("nowplaying")) {
                if (MusicUtils.sService != null) {
                    ret = new NowPlayingCursor(MusicUtils.sService, mCursorCols);
                    if (ret.getCount() == 0) {
                        finish();
                    }
                } else {
                }
            } else if (mPlaylist.equals("podcasts")) {
                where.append(" AND " + MediaStore.Audio.Media.IS_PODCAST + "=1");
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                if (!TextUtils.isEmpty(filter)) {
                    uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
                }
                ret = queryhandler.doQuery(uri,
                        mCursorCols, where.toString(), null,
                        MediaStore.Audio.Media.DEFAULT_SORT_ORDER, async);
            } else if (mPlaylist.equals("recentlyadded")) {
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                if (!TextUtils.isEmpty(filter)) {
                    uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
                }
                int X = MusicUtils.getIntPref(this, "numweeks", 2) * (3600 * 24 * 7);
                where.append(" AND " + MediaStore.MediaColumns.DATE_ADDED + ">");
                where.append(System.currentTimeMillis() / 1000 - X);
                ret = queryhandler.doQuery(uri,
                        mCursorCols, where.toString(), null,
                        MediaStore.Audio.Media.DEFAULT_SORT_ORDER, async);
            } else {
                Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                        Long.valueOf(mPlaylist));
                if (!TextUtils.isEmpty(filter)) {
                    uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
                }
                mSortOrder = MediaStore.Audio.Playlists.Members.DEFAULT_SORT_ORDER;
                ret = queryhandler.doQuery(uri, mPlaylistMemberCols,
                        where.toString(), null, mSortOrder, async);
            }
        } else {
            if (mAlbumId != null) {
                where.append(" AND " + MediaStore.Audio.Media.ALBUM_ID + "=" + mAlbumId);
                mSortOrder = MediaStore.Audio.Media.TRACK + ", " + mSortOrder;
            }
            if (mArtistId != null) {
                where.append(" AND " + MediaStore.Audio.Media.ARTIST_ID + "=" + mArtistId);
            }
            where.append(" AND " + MediaStore.Audio.Media.IS_MUSIC + "=1");
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            if (!TextUtils.isEmpty(filter)) {
                uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filter)).build();
            }
            ret = queryhandler.doQuery(uri,
                    mCursorCols, where.toString() , null, mSortOrder, async);
        }
        
        if (ret != null && async) {
            init(ret, false);
            setTitle();
        }
        return ret;
    }

    private class NowPlayingCursor extends AbstractCursor
    {
        public NowPlayingCursor(IMediaPlaybackService service, String [] cols)
        {
            mCols = cols;
            mService  = service;
            makeNowPlayingCursor();
        }
        private void makeNowPlayingCursor() {
            mCurrentPlaylistCursor = null;
            try {
                mNowPlaying = mService.getQueue();
            } catch (RemoteException ex) {
                mNowPlaying = new long[0];
            }
            mSize = mNowPlaying.length;
            if (mSize == 0) {
                return;
            }

            StringBuilder where = new StringBuilder();
            where.append(MediaStore.Audio.Media._ID + " IN (");
            for (int i = 0; i < mSize; i++) {
                where.append(mNowPlaying[i]);
                if (i < mSize - 1) {
                    where.append(",");
                }
            }
            where.append(")");

            mCurrentPlaylistCursor = MusicUtils.query(TrackBrowserActivity.this,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mCols, where.toString(), null, MediaStore.Audio.Media._ID);

            if (mCurrentPlaylistCursor == null) {
                mSize = 0;
                return;
            }
            
            int size = mCurrentPlaylistCursor.getCount();
            mCursorIdxs = new long[size];
            mCurrentPlaylistCursor.moveToFirst();
            int colidx = mCurrentPlaylistCursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            for (int i = 0; i < size; i++) {
                mCursorIdxs[i] = mCurrentPlaylistCursor.getLong(colidx);
                mCurrentPlaylistCursor.moveToNext();
            }
            mCurrentPlaylistCursor.moveToFirst();
            mCurPos = -1;
            
            try {
                int removed = 0;
                for (int i = mNowPlaying.length - 1; i >= 0; i--) {
                    long trackid = mNowPlaying[i];
                    int crsridx = Arrays.binarySearch(mCursorIdxs, trackid);
                    if (crsridx < 0) {
                        removed += mService.removeTrack(trackid);
                    }
                }
                if (removed > 0) {
                    mNowPlaying = mService.getQueue();
                    mSize = mNowPlaying.length;
                    if (mSize == 0) {
                        mCursorIdxs = null;
                        return;
                    }
                }
            } catch (RemoteException ex) {
                mNowPlaying = new long[0];
            }
        }

        @Override
        public int getCount()
        {
            return mSize;
        }

        @Override
        public boolean onMove(int oldPosition, int newPosition)
        {
            if (oldPosition == newPosition)
                return true;
            
            if (mNowPlaying == null || mCursorIdxs == null || newPosition >= mNowPlaying.length) {
                return false;
            }

            long newid = mNowPlaying[newPosition];
            int crsridx = Arrays.binarySearch(mCursorIdxs, newid);
            mCurrentPlaylistCursor.moveToPosition(crsridx);
            mCurPos = newPosition;
            
            return true;
        }

        public boolean removeItem(int which)
        {
            try {
                if (mService.removeTracks(which, which) == 0) {
                    return false; 
                }
                int i = (int) which;
                mSize--;
                while (i < mSize) {
                    mNowPlaying[i] = mNowPlaying[i+1];
                    i++;
                }
                onMove(-1, (int) mCurPos);
            } catch (RemoteException ex) {
            }
            return true;
        }
        
        public void moveItem(int from, int to) {
            try {
                mService.moveQueueItem(from, to);
                mNowPlaying = mService.getQueue();
                onMove(-1, mCurPos); 
            } catch (RemoteException ex) {
            }
        }

        private void dump() {
            String where = "(";
            for (int i = 0; i < mSize; i++) {
                where += mNowPlaying[i];
                if (i < mSize - 1) {
                    where += ",";
                }
            }
            where += ")";
            Log.i("NowPlayingCursor: ", where);
        }

        @Override
        public String getString(int column)
        {
            try {
                return mCurrentPlaylistCursor.getString(column);
            } catch (Exception ex) {
                onChange(true);
                return "";
            }
        }

        @Override
        public short getShort(int column)
        {
            return mCurrentPlaylistCursor.getShort(column);
        }

        @Override
        public int getInt(int column)
        {
            try {
                return mCurrentPlaylistCursor.getInt(column);
            } catch (Exception ex) {
                onChange(true);
                return 0;
            }
        }

        @Override
        public long getLong(int column)
        {
            try {
                return mCurrentPlaylistCursor.getLong(column);
            } catch (Exception ex) {
                onChange(true);
                return 0;
            }
        }

        @Override
        public float getFloat(int column)
        {
            return mCurrentPlaylistCursor.getFloat(column);
        }

        @Override
        public double getDouble(int column)
        {
            return mCurrentPlaylistCursor.getDouble(column);
        }

        @Override
        public int getType(int column) {
            return mCurrentPlaylistCursor.getType(column);
        }

        @Override
        public boolean isNull(int column)
        {
            return mCurrentPlaylistCursor.isNull(column);
        }

        @Override
        public String[] getColumnNames()
        {
            return mCols;
        }
        
        @Override
        public void deactivate()
        {
            if (mCurrentPlaylistCursor != null)
                mCurrentPlaylistCursor.deactivate();
        }

        @Override
        public boolean requery()
        {
            makeNowPlayingCursor();
            return true;
        }

        private String [] mCols;
        private Cursor mCurrentPlaylistCursor;     
        private int mSize;          
        private long[] mNowPlaying;
        private long[] mCursorIdxs;
        private int mCurPos;
        private IMediaPlaybackService mService;
    }
    
    static class TrackListAdapter extends CursorRecyclerViewAdapter<TrackListAdapter.ViewHolder> implements SectionIndexer {
        boolean mIsNowPlaying;
        boolean mDisableNowPlayingIndicator;
        boolean mHasBackground;
        private int mDefaultColorOnSurface;
        private int mDefaultColorOutline;

        int mTitleIdx;
        int mArtistIdx;
        int mDurationIdx;
        int mAudioIdIdx;

        private final StringBuilder mBuilder = new StringBuilder();
        private final String mUnknownArtist;
        private final String mUnknownAlbum;
        
        private AlphabetIndexer mIndexer;
        
        private TrackBrowserActivity mActivity = null;
        private TrackQueryHandler mQueryHandler;
        private String mConstraint = null;
        private boolean mConstraintIsValid = false;
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView line1;
            TextView line2;
            TextView duration;
            ImageView play_indicator;
            ImageView drag_handle;
            CharArrayBuffer buffer1;
            char [] buffer2;

            ViewHolder(View v) {
                super(v);
                line1 = v.findViewById(R.id.line1);
                line2 = v.findViewById(R.id.line2);
                duration = v.findViewById(R.id.duration);
                play_indicator = v.findViewById(R.id.play_indicator);
                drag_handle = v.findViewById(R.id.drag_handle);
                buffer1 = new CharArrayBuffer(100);
                buffer2 = new char[200];
                View icon = v.findViewById(R.id.icon);
                if (icon != null) icon.setVisibility(View.GONE);
            }
        }

        class TrackQueryHandler extends AsyncQueryHandler {

            class QueryArgs {
                public Uri uri;
                public String [] projection;
                public String selection;
                public String [] selectionArgs;
                public String orderBy;
            }

            TrackQueryHandler(ContentResolver res) {
                super(res);
            }
            
            public Cursor doQuery(Uri uri, String[] projection,
                    String selection, String[] selectionArgs,
                    String orderBy, boolean async) {
                if (async) {
                    Uri limituri = uri.buildUpon().appendQueryParameter("limit", "100").build();
                    QueryArgs args = new QueryArgs();
                    args.uri = uri;
                    args.projection = projection;
                    args.selection = selection;
                    args.selectionArgs = selectionArgs;
                    args.orderBy = orderBy;

                    startQuery(0, args, limituri, projection, selection, selectionArgs, orderBy);
                    return null;
                }
                return MusicUtils.query(mActivity,
                        uri, projection, selection, selectionArgs, orderBy);
            }

            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                mActivity.init(cursor, cookie != null);
                if (token == 0 && cookie != null && cursor != null &&
                    !cursor.isClosed() && cursor.getCount() >= 100) {
                    QueryArgs args = (QueryArgs) cookie;
                    startQuery(1, null, args.uri, args.projection, args.selection,
                            args.selectionArgs, args.orderBy);
                }
            }
        }
        
        TrackListAdapter(Context context, TrackBrowserActivity currentactivity,
                Cursor cursor, boolean isnowplaying, boolean disablenowplayingindicator) {
            super(context, cursor);
            mActivity = currentactivity;
            getColumnIndices(cursor);
            mIsNowPlaying = isnowplaying;
            mDisableNowPlayingIndicator = disablenowplayingindicator;
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
            mUnknownAlbum = context.getString(R.string.unknown_album_name);

            mDefaultColorOnSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK);
            mDefaultColorOutline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, android.graphics.Color.GRAY);
            
            mQueryHandler = new TrackQueryHandler(context.getContentResolver());
        }
        
        public void setActivity(TrackBrowserActivity newactivity) {
            mActivity = newactivity;
        }

        public void setHasBackground(boolean has) {
            mHasBackground = has;
            notifyDataSetChanged();
        }
        
        public TrackQueryHandler getQueryHandler() {
            return mQueryHandler;
        }
        
        private void getColumnIndices(Cursor cursor) {
            if (cursor != null) {
                mTitleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                mArtistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                mDurationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                try {
                    mAudioIdIdx = cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Playlists.Members.AUDIO_ID);
                } catch (IllegalArgumentException ex) {
                    mAudioIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                }
                
                if (mIndexer != null) {
                    mIndexer.setCursor(cursor);
                } else if (!mActivity.mEditMode && mActivity.mAlbumId == null) {
                    String alpha = mActivity.getString(R.string.fast_scroll_alphabet);
                
                    mIndexer = new MusicAlphabetIndexer(cursor, mTitleIdx, alpha);
                }
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    mActivity.mEditMode ? R.layout.edit_track_list_item : R.layout.track_list_item,
                    parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder vh, Cursor cursor) {
            cursor.copyStringToBuffer(mTitleIdx, vh.buffer1);
            vh.line1.setText(vh.buffer1.data, 0, vh.buffer1.sizeCopied);
            
            int secs = cursor.getInt(mDurationIdx) / 1000;
            if (secs == 0) {
                vh.duration.setText("");
            } else {
                vh.duration.setText(MusicUtils.makeTimeString(vh.itemView.getContext(), secs));
            }
            
            final StringBuilder builder = mBuilder;
            builder.delete(0, builder.length());

            String name = cursor.getString(mArtistIdx);
            if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                builder.append(mUnknownArtist);
            } else {
                builder.append(name);
            }
            int len = builder.length();
            if (vh.buffer2.length < len) {
                vh.buffer2 = new char[len];
            }
            builder.getChars(0, len, vh.buffer2, 0);
            vh.line2.setText(vh.buffer2, 0, len);

            ImageView iv = vh.play_indicator;
            long id = -1;
            if (MusicUtils.sService != null) {
                try {
                    if (mIsNowPlaying) {
                        id = MusicUtils.sService.getQueuePosition();
                    } else {
                        id = MusicUtils.sService.getAudioId();
                    }
                } catch (RemoteException ex) {
                }
            }
            
            if ( (mIsNowPlaying && cursor.getPosition() == id) ||
                 (!mIsNowPlaying && !mDisableNowPlayingIndicator && cursor.getLong(mAudioIdIdx) == id)) {
                iv.setImageResource(R.drawable.indicator_ic_mp_playing_list);
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setVisibility(View.GONE);
            }

            if (vh.drag_handle != null) {
                vh.drag_handle.setVisibility((mIsNowPlaying || mActivity.mEditMode) ? View.VISIBLE : View.GONE);
                if (mActivity.mItemTouchHelper != null && mIsNowPlaying) {
                    vh.drag_handle.setOnTouchListener((v, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            mActivity.mItemTouchHelper.startDrag(vh);
                        }
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            v.performClick();
                        }
                        return false;
                    });
                }
                vh.drag_handle.setOnLongClickListener(v -> true);
            }

            final int position = cursor.getPosition();
            final long trackId = cursor.getLong(mAudioIdIdx);
            vh.itemView.setOnClickListener(v -> {
                mActivity.onListItemClick(position, trackId);
            });
            vh.itemView.setOnLongClickListener(v -> {
                mActivity.showPopupMenu(v, position);
                return true;
            });

            if (mHasBackground) {
                vh.line1.setTextColor(android.graphics.Color.WHITE);
                vh.line2.setTextColor(android.graphics.Color.LTGRAY);
                vh.duration.setTextColor(android.graphics.Color.WHITE);
                
                // Add black shadow for legibility over album art
                float radius = 2f;
                float dx = 1f;
                float dy = 1f;
                int color = android.graphics.Color.BLACK;
                vh.line1.setShadowLayer(radius, dx, dy, color);
                vh.line2.setShadowLayer(radius, dx, dy, color);
                vh.duration.setShadowLayer(radius, dx, dy, color);
            } else {
                vh.line1.setTextColor(mDefaultColorOnSurface);
                vh.line2.setTextColor(mDefaultColorOutline);
                vh.duration.setTextColor(mDefaultColorOutline);
                
                // Remove shadow for clean look in normal modes
                vh.line1.setShadowLayer(0, 0, 0, 0);
                vh.line2.setShadowLayer(0, 0, 0, 0);
                vh.duration.setShadowLayer(0, 0, 0, 0);
            }
        }
        
        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mTrackCursor) {
                mActivity.mTrackCursor = cursor;
                super.changeCursor(cursor);
                getColumnIndices(cursor);
            }
        }
        
        public Object[] getSections() {
            if (mIndexer != null) { 
                return mIndexer.getSections();
            } else {
                return new String [] { " " };
            }
        }
        
        public int getPositionForSection(int section) {
            if (mIndexer != null) {
                return mIndexer.getPositionForSection(section);
            }
            return 0;
        }
        
        public int getSectionForPosition(int position) {
            return 0;
        }        
    }
}
