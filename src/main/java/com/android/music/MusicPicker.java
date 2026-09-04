/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.SectionIndexer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.text.Collator;
import java.util.Formatter;
import java.util.Locale;

/**
 * Activity allowing the user to select a music track on the device, and
 * return it to its caller.  The music picker user interface is fairly
 * extensive, providing information about each track like the music
 * application (title, author, album, duration), as well as the ability to
 * previous tracks and sort them in different orders.
 */
public class MusicPicker extends BaseActivity
        implements View.OnClickListener, MediaPlayer.OnCompletionListener,
        MusicUtils.Defs {
    static final boolean DBG = false;
    static final String TAG = "MusicPicker";
    
    static final String LIST_STATE_KEY = "liststate";
    static final String FOCUS_KEY = "focused";
    static final String SORT_MODE_KEY = "sortMode";
    
    static final int MY_QUERY_TOKEN = 42;
    
    static final int TRACK_MENU = Menu.FIRST;
    static final int ALBUM_MENU = Menu.FIRST+1;
    static final int ARTIST_MENU = Menu.FIRST+2;
    
    static final String[] CURSOR_COLS = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MusicUtils.TITLE_KEY,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK
    };
    
    static StringBuilder sFormatBuilder = new StringBuilder();
    static Formatter sFormatter = new Formatter(sFormatBuilder, Locale.getDefault());
    static final Object[] sTimeArgs = new Object[5];

    Uri mBaseUri;
    
    TrackListAdapter mAdapter;
    QueryHandler mQueryHandler;
    
    Parcelable mListState = null;
    boolean mListHasFocus;
    
    Cursor mCursor;
    int mSortMode = -1;
    String mSortOrder;

    View mProgressContainer;
    View mListContainer;
    boolean mListShown;
    
    View mOkayButton;
    View mCancelButton;
    
    long mSelectedId = -1;
    Uri mSelectedUri;
    
    long mPlayingId = -1;
    
    MediaPlayer mMediaPlayer;
    
    private RecyclerView mTrackList;

    class TrackListAdapter extends CursorRecyclerViewAdapter<TrackListAdapter.ViewHolder>
            implements SectionIndexer {
        
        private final StringBuilder mBuilder = new StringBuilder();
        private final String mUnknownArtist;
        private final String mUnknownAlbum;

        private int mIdIdx;
        private int mTitleIdx;
        private int mArtistIdx;
        private int mAlbumIdx;
        private int mDurationIdx;

        private boolean mLoading = true;
        private int mIndexerSortMode;
        private MusicAlphabetIndexer mIndexer;
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView line1;
            TextView line2;
            TextView duration;
            RadioButton radio;
            ImageView play_indicator;
            CharArrayBuffer buffer1;
            char [] buffer2;

            ViewHolder(View v) {
                super(v);
                line1 = (TextView) v.findViewById(R.id.line1);
                line2 = (TextView) v.findViewById(R.id.line2);
                duration = (TextView) v.findViewById(R.id.duration);
                radio = (RadioButton) v.findViewById(R.id.radio);
                play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
                buffer1 = new CharArrayBuffer(100);
                buffer2 = new char[200];
            }
        }
        
        TrackListAdapter(Context context, int layout,
                String[] from, int[] to) {
            super(context, null);
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
            mUnknownAlbum = context.getString(R.string.unknown_album_name);
        }

        public void setLoading(boolean loading) {
            mLoading = loading;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.music_picker_item, parent, false);
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

            String name = cursor.getString(mAlbumIdx);
            if (name == null || name.equals("<unknown>")) {
                builder.append(mUnknownAlbum);
            } else {
                builder.append(name);
            }
            builder.append('\n');
            name = cursor.getString(mArtistIdx);
            if (name == null || name.equals("<unknown>")) {
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

            final long id = cursor.getLong(mIdIdx);
            vh.radio.setChecked(id == mSelectedId);
            
            ImageView iv = vh.play_indicator;
            if (id == mPlayingId) {
                iv.setImageResource(R.drawable.indicator_ic_mp_playing_list);
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setVisibility(View.GONE);
            }

            vh.itemView.setOnClickListener(v -> {
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    MusicPicker.this.onListItemClick(pos, id);
                }
            });
        }
        
        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            MusicPicker.this.mCursor = cursor;
            
            if (cursor != null) {
                mIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                mTitleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                mArtistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                mAlbumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                mDurationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                if (mIndexerSortMode != mSortMode || mIndexer == null) {
                    mIndexerSortMode = mSortMode;
                    int idx = mTitleIdx;
                    switch (mIndexerSortMode) {
                        case ARTIST_MENU:
                            idx = mArtistIdx;
                            break;
                        case ALBUM_MENU:
                            idx = mAlbumIdx;
                            break;
                    }
                    mIndexer = new MusicAlphabetIndexer(cursor, idx,
                            getResources().getString(R.string.fast_scroll_alphabet));
                    
                } else {
                    mIndexer.setCursor(cursor);
                }
            }
            makeListShown();
        }
        
        public int getPositionForSection(int section) {
            Cursor cursor = getCursor();
            if (cursor == null) {
                return 0;
            }
            return mIndexer.getPositionForSection(section);
        }

        public int getSectionForPosition(int position) {
            return 0;
        }

        public Object[] getSections() {
            if (mIndexer != null) {
                return mIndexer.getSections();
            }
            return null;
        }
    }

    private final class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(Context context) {
            super(context.getContentResolver());
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            if (!isFinishing()) {
                mAdapter.setLoading(false);
                mAdapter.changeCursor(cursor);
                setProgressBarIndeterminateVisibility(false);
    
                if (mListState != null) {
                    mTrackList.getLayoutManager().onRestoreInstanceState(mListState);
                    if (mListHasFocus) {
                        mTrackList.requestFocus();
                    }
                    mListHasFocus = false;
                    mListState = null;
                }
            } else {
                if (cursor != null) cursor.close();
            }
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        
        int sortMode = TRACK_MENU;
        if (icicle == null) {
            mSelectedUri = getIntent().getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);
        } else {
            mSelectedUri = (Uri)icicle.getParcelable(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);
            mListState = icicle.getParcelable(LIST_STATE_KEY);
            mListHasFocus = icicle.getBoolean(FOCUS_KEY);
            sortMode = icicle.getInt(SORT_MODE_KEY, sortMode);
        }
        if (Intent.ACTION_GET_CONTENT.equals(getIntent().getAction())) {
            mBaseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            mBaseUri = getIntent().getData();
            if (mBaseUri == null) {
                Log.w("MusicPicker", "No data URI given to PICK action");
                finish();
                return;
            }
        }
        
        setContentView(R.layout.music_picker);

        mSortOrder = MusicUtils.TITLE_KEY;

        mTrackList = findViewById(R.id.list);
        mTrackList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new TrackListAdapter(this,
                R.layout.music_picker_item, new String[] {},
                new int[] {});
        mTrackList.setAdapter(mAdapter);
        mQueryHandler = new QueryHandler(this);
        mProgressContainer = findViewById(R.id.progressContainer);
        mListContainer = findViewById(R.id.listContainer);
        mOkayButton = findViewById(R.id.okayButton);
        mOkayButton.setOnClickListener(this);
        mCancelButton = findViewById(R.id.cancelButton);
        mCancelButton.setOnClickListener(this);
        
        if (mSelectedUri != null) {
            Uri.Builder builder = mSelectedUri.buildUpon();
            String path = mSelectedUri.getEncodedPath();
            if (path != null) {
                int idx = path.lastIndexOf('/');
                if (idx >= 0) {
                    path = path.substring(0, idx);
                }
                builder.encodedPath(path);
                Uri baseSelectedUri = builder.build();
                if (baseSelectedUri.equals(mBaseUri)) {
                    try {
                        mSelectedId = ContentUris.parseId(mSelectedUri);
                    } catch (NumberFormatException e) {
                        mSelectedId = -1;
                    }
                }
            }
        }
        
        setSortMode(sortMode);
    }

    @Override public void onRestart() {
        super.onRestart();
        doQuery(false, null);
    }
    
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (setSortMode(item.getItemId())) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, TRACK_MENU, Menu.NONE, R.string.sort_by_track);
        menu.add(Menu.NONE, ALBUM_MENU, Menu.NONE, R.string.sort_by_album);
        menu.add(Menu.NONE, ARTIST_MENU, Menu.NONE, R.string.sort_by_artist);
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle icicle) {
        super.onSaveInstanceState(icicle);
        if (mTrackList != null && mTrackList.getLayoutManager() != null) {
            icicle.putParcelable(LIST_STATE_KEY, mTrackList.getLayoutManager().onSaveInstanceState());
            icicle.putBoolean(FOCUS_KEY, mTrackList.hasFocus());
        }
        icicle.putInt(SORT_MODE_KEY, mSortMode);
    }
    
    @Override public void onPause() {
        super.onPause();
        stopMediaPlayer();
    }
    
    @Override public void onStop() {
        super.onStop();
        mAdapter.setLoading(true);
        mAdapter.changeCursor(null);
    }
    
    boolean setSortMode(int sortMode) {
        if (sortMode != mSortMode) {
            switch (sortMode) {
                case TRACK_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MusicUtils.TITLE_KEY;
                    doQuery(false, null);
                    return true;
                case ALBUM_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MusicUtils.ALBUM_KEY + " ASC, "
                            + MediaStore.Audio.Media.TRACK + " ASC, "
                            + MusicUtils.TITLE_KEY + " ASC";
                    doQuery(false, null);
                    return true;
                case ARTIST_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MusicUtils.ARTIST_KEY + " ASC, "
                            + MusicUtils.ALBUM_KEY + " ASC, "
                            + MediaStore.Audio.Media.TRACK + " ASC, "
                            + MusicUtils.TITLE_KEY + " ASC";
                    doQuery(false, null);
                    return true;
            }
            
        }
        return false;
    }
    
    void makeListShown() {
        if (!mListShown) {
            mListShown = true;
            mProgressContainer.startAnimation(AnimationUtils.loadAnimation(
                    this, android.R.anim.fade_out));
            mProgressContainer.setVisibility(View.GONE);
            mListContainer.startAnimation(AnimationUtils.loadAnimation(
                    this, android.R.anim.fade_in));
            mListContainer.setVisibility(View.VISIBLE);
        }
    }
    
    Cursor doQuery(boolean sync, String filterstring) {
        mQueryHandler.cancelOperation(MY_QUERY_TOKEN);
        
        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Media.TITLE + " != ''");
        
        Uri uri = mBaseUri;
        if (!TextUtils.isEmpty(filterstring)) {
            uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filterstring)).build();
        }

        if (sync) {
            try {
                return getContentResolver().query(uri, CURSOR_COLS,
                        where.toString(), null, mSortOrder);
            } catch (UnsupportedOperationException ex) {
            }
        } else {
            mAdapter.setLoading(true);
            setProgressBarIndeterminateVisibility(true);
            mQueryHandler.startQuery(MY_QUERY_TOKEN, null, uri, CURSOR_COLS,
                    where.toString(), null, mSortOrder);
        }
        return null;
    }
    
    public void onListItemClick(int position, long id) {
        if (mCursor != null && mCursor.moveToPosition(position)) {
            setSelected(mCursor);
        }
    }
    
    void setSelected(Cursor c) {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        int idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID);
        if (idIdx < 0) {
            Log.e(TAG, "Cursor does not contain _ID column");
            return;
        }
        long newId = c.getLong(idIdx);
        mSelectedUri = ContentUris.withAppendedId(uri, newId);
        
        mSelectedId = newId;
        if (newId != mPlayingId || mMediaPlayer == null) {
            stopMediaPlayer();
            mMediaPlayer = new MediaPlayer();
            try {
                mMediaPlayer.setDataSource(this, mSelectedUri);
                mMediaPlayer.setOnCompletionListener(this);
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
                mPlayingId = newId;
                mAdapter.notifyDataSetChanged();
            } catch (Exception e) {
                Log.w("MusicPicker", "Unable to play track", e);
            }
        } else if (mMediaPlayer != null) {
            stopMediaPlayer();
            mAdapter.notifyDataSetChanged();
        }
    }
    
    public void onCompletion(MediaPlayer mp) {
        if (mMediaPlayer == mp) {
            mp.stop();
            mp.release();
            mMediaPlayer = null;
            mPlayingId = -1;
            mAdapter.notifyDataSetChanged();
        }
    }
    
    void stopMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mPlayingId = -1;
        }
    }
    
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.okayButton) {
            if (mSelectedId >= 0) {
                setResult(RESULT_OK, new Intent().setData(mSelectedUri));
                finish();
            }
        } else if (id == R.id.cancelButton) {
            finish();
        }
    }
}
