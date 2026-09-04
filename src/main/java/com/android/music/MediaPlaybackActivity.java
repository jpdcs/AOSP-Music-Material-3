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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.TextUtils.TruncateAt;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar.OnSeekBarChangeListener;

import java.util.ArrayList;
import java.util.List;


import androidx.palette.graphics.Palette;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowInsetsControllerCompat;
import android.view.Window;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import android.media.MediaMetadataRetriever;
import android.os.Build;

public class MediaPlaybackActivity extends BaseActivity implements MusicUtils.Defs,
    View.OnTouchListener, View.OnLongClickListener
{
    private static final int USE_AS_RINGTONE = CHILD_MENU_BASE;

    private boolean mSeeking = false;
    private boolean mDeviceHasDpad;
    private long mStartSeekPos = 0;
    private long mLastSeekEventTime;
    private IMediaPlaybackService mService = null;
    private RepeatingImageButton mPrevButton;
    private ImageButton mPauseButton;
    private RepeatingImageButton mNextButton;
    private ImageButton mRepeatButton;
    private ImageButton mShuffleButton;
    private ImageButton mQueueButton;
    private LyricsView mLyricsView;
    private Worker mAlbumArtWorker;
    private AlbumArtHandler mAlbumArtHandler;
    private LyricsHandler mLyricsHandler;
    private String mLyricsCachePath;
    private String mLyricsCacheContent;
    private Toast mToast;
    private int mTouchSlop;
    private ServiceToken mToken;

    public MediaPlaybackActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        mAlbumArtWorker = new Worker("album art worker");
        mAlbumArtHandler = new AlbumArtHandler(mAlbumArtWorker.getLooper());
        mLyricsHandler = new LyricsHandler(mAlbumArtWorker.getLooper());

        setContentView(R.layout.audio_player);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mCurrentTime = (TextView) findViewById(R.id.currenttime);
        mTotalTime = (TextView) findViewById(R.id.totaltime);
        mProgress = (ProgressBar) findViewById(android.R.id.progress);
        mAlbum = (ImageView) findViewById(R.id.album);
        mArtistName = (TextView) findViewById(R.id.artistname);
        mAlbumName = (TextView) findViewById(R.id.albumname);
        mTrackName = (TextView) findViewById(R.id.trackname);

        mArtistName.setOnTouchListener(this);
        mArtistName.setOnLongClickListener(this);

        mAlbumName.setOnTouchListener(this);
        mAlbumName.setOnLongClickListener(this);

        mTrackName.setOnTouchListener(this);
        mTrackName.setOnLongClickListener(this);
        
        mPrevButton = (RepeatingImageButton) findViewById(R.id.prev);
        mPrevButton.setOnClickListener(mPrevListener);
        mPrevButton.setRepeatListener(mRewListener, 260);
        mPauseButton = (ImageButton) findViewById(R.id.pause);
        mPauseButton.requestFocus();
        mPauseButton.setOnClickListener(mPauseListener);
        mNextButton = (RepeatingImageButton) findViewById(R.id.next);
        mNextButton.setOnClickListener(mNextListener);
        mNextButton.setRepeatListener(mFfwdListener, 260);
        seekmethod = 1;

        mDeviceHasDpad = (getResources().getConfiguration().navigation ==
            Configuration.NAVIGATION_DPAD);
        
        mQueueButton = (ImageButton) findViewById(R.id.btn_lyrics);
        if (mQueueButton != null) {
            mQueueButton.setOnClickListener(mLyricsListener);
        }
        mLyricsView = (LyricsView) findViewById(R.id.lyrics_view);
        
        mShuffleButton = ((ImageButton) findViewById(R.id.shuffle));
        mShuffleButton.setOnClickListener(mShuffleListener);
        mRepeatButton = ((ImageButton) findViewById(R.id.repeat));
        mRepeatButton.setOnClickListener(mRepeatListener);
        
        if (mProgress instanceof SeekBar) {
            SeekBar seeker = (SeekBar) mProgress;
            seeker.setOnSeekBarChangeListener(mSeekListener);
        }
        mProgress.setMax(1000);

        mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v1 -> finish());
        }

        View btnClose = findViewById(R.id.btn_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v1 -> finish());
        }

        View btnQueue = findViewById(R.id.btn_queue);
        if (btnQueue != null) {
            btnQueue.setOnClickListener(mQueueListener);
        }
        
        // Ensure mQueueButton (Lyrics button) is correctly initialized even if not assigned to field yet
        if (mQueueButton == null) {
            mQueueButton = (ImageButton) findViewById(R.id.btn_lyrics);
        }

        View btnMore = findViewById(R.id.btn_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(v1 -> {
                // Show the standard options menu as a popup at the button's location
                showPopupMenu(v1);
            });
        }
    }

    private void showPopupMenu(View view) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, view);
        Menu menu = popup.getMenu();
        
        // Add items manually to match activity's options menu
        if (MusicUtils.getCurrentAudioId() >= 0) {
            menu.add(0, GOTO_START, 0, R.string.goto_start);
            menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle);
            menu.add(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
            menu.add(0, USE_AS_RINGTONE, 0, R.string.ringtone_menu_short);
            menu.add(0, DELETE_ITEM, 0, R.string.delete_item);
            menu.add(0, EFFECTS_PANEL, 0, R.string.effectspanel);
        }
        
        popup.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));
        popup.show();
    }
    
    int mInitialX = -1;
    int mLastX = -1;
    int mTextWidth = 0;
    int mViewWidth = 0;
    boolean mDraggingLabel = false;
    
    TextView textViewForContainer(View v) {
        View vv = v.findViewById(R.id.artistname);
        if (vv != null) return (TextView) vv;
        vv = v.findViewById(R.id.albumname);
        if (vv != null) return (TextView) vv;
        vv = v.findViewById(R.id.trackname);
        if (vv != null) return (TextView) vv;
        return null;
    }
    
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction();
        TextView tv = textViewForContainer(v);
        if (tv == null) {
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            v.setBackgroundColor(0xff606060);
            mInitialX = mLastX = (int) event.getX();
            mDraggingLabel = false;
        } else if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_CANCEL) {
            v.setBackgroundColor(0);
            if (mDraggingLabel) {
                Message msg = mLabelScroller.obtainMessage(0, tv);
                mLabelScroller.sendMessageDelayed(msg, 1000);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mDraggingLabel) {
                int scrollx = tv.getScrollX();
                int x = (int) event.getX();
                int delta = mLastX - x;
                if (delta != 0) {
                    mLastX = x;
                    scrollx += delta;
                    if (scrollx > mTextWidth) {
                        // scrolled the text completely off the view to the left
                        scrollx -= mTextWidth;
                        scrollx -= mViewWidth;
                    }
                    if (scrollx < -mViewWidth) {
                        // scrolled the text completely off the view to the right
                        scrollx += mViewWidth;
                        scrollx += mTextWidth;
                    }
                    tv.scrollTo(scrollx, 0);
                }
                return true;
            }
            int delta = mInitialX - (int) event.getX();
            if (Math.abs(delta) > mTouchSlop) {
                // start moving
                mLabelScroller.removeMessages(0, tv);
                
                // Only turn ellipsizing off when it's not already off, because it
                // causes the scroll position to be reset to 0.
                if (tv.getEllipsize() != null) {
                    tv.setEllipsize(null);
                }
                Layout ll = tv.getLayout();
                // layout might be null if the text just changed, or ellipsizing
                // was just turned off
                if (ll == null) {
                    return false;
                }
                // get the non-ellipsized line width, to determine whether scrolling
                // should even be allowed
                mTextWidth = (int) tv.getLayout().getLineWidth(0);
                mViewWidth = tv.getWidth();
                if (mViewWidth > mTextWidth) {
                    tv.setEllipsize(TruncateAt.END);
                    v.cancelLongPress();
                    return false;
                }
                mDraggingLabel = true;
                tv.setHorizontalFadingEdgeEnabled(true);
                v.cancelLongPress();
                return true;
            }
        }
        return false; 
    }

    Handler mLabelScroller = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            TextView tv = (TextView) msg.obj;
            int x = tv.getScrollX();
            x = x * 3 / 4;
            tv.scrollTo(x, 0);
            if (x == 0) {
                tv.setEllipsize(TruncateAt.END);
            } else {
                Message newmsg = obtainMessage(0, tv);
                mLabelScroller.sendMessageDelayed(newmsg, 15);
            }
        }
    };
    
    public boolean onLongClick(View view) {

        CharSequence title = null;
        String mime = null;
        String query = null;
        String artist;
        String album;
        String song;
        long audioid;
        
        try {
            artist = mService.getArtistName();
            album = mService.getAlbumName();
            song = mService.getTrackName();
            audioid = mService.getAudioId();
        } catch (RemoteException ex) {
            return true;
        } catch (NullPointerException ex) {
            // we might not actually have the service yet
            return true;
        }

        if (MediaStore.UNKNOWN_STRING.equals(album) &&
                MediaStore.UNKNOWN_STRING.equals(artist) &&
                song != null &&
                song.startsWith("recording")) {
            // not music
            return false;
        }

        if (audioid < 0) {
            return false;
        }

        Cursor c = MusicUtils.query(this,
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioid),
                new String[] {MediaStore.Audio.Media.IS_MUSIC}, null, null, null);
        boolean ismusic = true;
        if (c != null) {
            if (c.moveToFirst()) {
                ismusic = c.getInt(0) != 0;
            }
            c.close();
        }
        if (!ismusic) {
            return false;
        }

        boolean knownartist =
            (artist != null) && !MediaStore.UNKNOWN_STRING.equals(artist);

        boolean knownalbum =
            (album != null) && !MediaStore.UNKNOWN_STRING.equals(album);
        
        if (knownartist && view.equals(mArtistName)) {
            title = artist;
            query = artist;
            mime = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE;
        } else if (knownalbum && view.equals(mAlbumName)) {
            title = album;
            if (knownartist) {
                query = artist + " " + album;
            } else {
                query = album;
            }
            mime = MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE;
        } else if (view.equals(mTrackName) || !knownartist || !knownalbum) {
            if ((song == null) || MediaStore.UNKNOWN_STRING.equals(song)) {
                // A popup of the form "Search for null/'' using ..." is pretty
                // unhelpful, plus, we won't find any way to buy it anyway.
                return true;
            }

            title = song;
            if (knownartist) {
                query = artist + " " + song;
            } else {
                query = song;
            }
            mime = "audio/*"; // the specific type doesn't matter, so don't bother retrieving it
        } else {
            throw new RuntimeException("shouldn't be here");
        }
        title = getString(R.string.mediasearch, title);

        Intent i = new Intent();
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setAction(MediaStore.INTENT_ACTION_MEDIA_SEARCH);
        i.putExtra(SearchManager.QUERY, query);
        if(knownartist) {
            i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        }
        if(knownalbum) {
            i.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, album);
        }
        i.putExtra(MediaStore.EXTRA_MEDIA_TITLE, song);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mime);

        startActivity(Intent.createChooser(i, title));
        return true;
    }

    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
            mLastSeekEventTime = 0;
            mFromTouch = true;
        }
        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if (!fromuser || (mService == null)) return;
            long now = SystemClock.elapsedRealtime();
            if ((now - mLastSeekEventTime) > 250) {
                mLastSeekEventTime = now;
                mPosOverride = mDuration * progress / 1000;
                try {
                    mService.seek(mPosOverride);
                } catch (RemoteException ex) {
                }

                // trackball event, allow progress updates
                if (!mFromTouch) {
                    refreshNow();
                    mPosOverride = -1;
                }
            }
        }
        public void onStopTrackingTouch(SeekBar bar) {
            mPosOverride = -1;
            mFromTouch = false;
        }
    };
    
    private View.OnClickListener mLyricsListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mLyricsView != null) {
                if (mLyricsView.getVisibility() == View.VISIBLE) {
                    mLyricsView.setVisibility(View.GONE);
                } else {
                    updateLyrics();
                    mLyricsView.setVisibility(View.VISIBLE);
                }
                updateVisibilityForLyrics();
            }
        }
    };

    private void updateVisibilityForLyrics() {
        if (mLyricsView == null) return;
        boolean lyricsOpen = mLyricsView.getVisibility() == View.VISIBLE;
        
        View albumCard = findViewById(R.id.album_card);
        if (albumCard != null) {
            // Use INVISIBLE instead of GONE for albumCard in portrait to keep layout stable if needed
            // But since I removed the constraint in XML, GONE should be fine.
            // Let's use GONE as requested to show lyrics in that spot.
            albumCard.setVisibility(lyricsOpen ? View.GONE : View.VISIBLE);
        }
        
        // Track info container should stay visible in both modes
        View info = findViewById(R.id.track_info_container);
        if (info != null) {
            info.setVisibility(View.VISIBLE);
        }
        
        // Ensure controls are also always visible
        View controls = findViewById(R.id.media_controls_container);
        if (controls != null) {
            controls.setVisibility(View.VISIBLE);
        }
    }
    
    private String loadLyrics(String path) {
        if (path == null) return null;
        Log.d("Lyrics", "Loading lyrics for: " + path);
        
        String resolvedPath = path;
        if (path.startsWith("content://")) {
            resolvedPath = getFilePathFromUri(Uri.parse(path));
            if (resolvedPath != null) {
                Log.d("Lyrics", "Resolved content URI to path: " + resolvedPath);
            }
        }

        // 1. Try sidecar files first
        if (resolvedPath != null && !resolvedPath.startsWith("content://")) {
            int dot = resolvedPath.lastIndexOf('.');
            String basePath = dot >= 0 ? resolvedPath.substring(0, dot) : resolvedPath;
            String[] sidecarExts = {".lrc", ".LRC", ".txt", ".TXT"};
            for (String ext : sidecarExts) {
                File f = new File(basePath + ext);
                Log.d("Lyrics", "Checking for sidecar: " + f.getAbsolutePath());
                if (f.exists()) {
                    String content = readFileContent(f);
                    if (content != null && !content.trim().isEmpty()) {
                        Log.d("Lyrics", "Found sidecar: " + ext);
                        return content;
                    }
                }
            }
            
            // Try matching without the number prefix if it exists (e.g. "1-01 - song.mp3" -> "song.lrc")
            // This is a common naming convention mismatch
            File musicFile = new File(resolvedPath);
            String musicName = musicFile.getName();
            File parentDir = musicFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                 Log.d("Lyrics", "Scanning directory for matching LRC: " + parentDir.getAbsolutePath());
                 File[] files = parentDir.listFiles();
                 if (files != null) {
                     for (File f : files) {
                         String name = f.getName().toLowerCase();
                         if (name.endsWith(".lrc") || name.endsWith(".txt")) {
                             // If lyrics file name is contained in music file name or vice versa
                             String simpleMusicName = musicName.toLowerCase().replaceAll("[^a-z0-9]", "");
                             String simpleLrcName = f.getName().toLowerCase().replaceAll("[^a-z0-9]", "").replace("lrc", "").replace("txt", "");
                             if (simpleMusicName.contains(simpleLrcName) || simpleLrcName.contains(simpleMusicName)) {
                                 Log.d("Lyrics", "Found fuzzy match sidecar: " + f.getName());
                                 return readFileContent(f);
                             }
                         }
                     }
                 }
            }
        }



        // 2. Try embedded lyrics using MediaMetadataRetriever
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (path.startsWith("content://")) {
                retriever.setDataSource(this, Uri.parse(path));
            } else {
                retriever.setDataSource(path);
            }
            
            // Try standard keys and common fallbacks
            // 1000 is METADATA_KEY_LYRICS (API 31+)
            int[] keys = {1000, 101, 1001, 1002, 3000, 11};
            for (int key : keys) {
                try {
                    String lyrics = retriever.extractMetadata(key);
                    if (lyrics != null && !lyrics.trim().isEmpty()) {
                        Log.d("Lyrics", "Found embedded via key: " + key);
                        return lyrics;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e("Lyrics", "Error extracting embedded lyrics", e);
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    retriever.close();
                } else {
                    retriever.release();
                }
            } catch (Exception e) {}
        }
        
        // 3. Manual scan for FLAC/MP3
        if (path.startsWith("content://")) {
            try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(Uri.parse(path), "r")) {
                if (pfd != null) {
                    String manual = manualScanForLyrics(pfd.getFileDescriptor());
                    if (manual != null) {
                        Log.d("Lyrics", "Found via manual scan (content URI)");
                        return manual;
                    }
                }
            } catch (Exception e) {
                Log.e("Lyrics", "Error scanning content URI manually", e);
            }
        } else {
            String manual = manualScanForLyrics(path);
            if (manual != null) {
                Log.d("Lyrics", "Found via manual scan (file path)");
                return manual;
            }
        }
        
        Log.d("Lyrics", "No lyrics found");
        return null;
    }

    private String getFilePathFromUri(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        if ("content".equals(uri.getScheme())) {
            String[] projection = { MediaStore.Audio.Media.DATA };
            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int colIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    if (colIdx != -1) {
                        return cursor.getString(colIdx);
                    }
                }
            } catch (Exception e) {
                Log.e("Lyrics", "Failed to resolve path from content URI", e);
            }
        }
        return null;
    }

    private String readFileContent(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] bom = new byte[3];
            int read = fis.read(bom);
            String charset = "UTF-8";
            int skip = 0;
            if (read >= 2 && bom[0] == (byte)0xFF && bom[1] == (byte)0xFE) {
                charset = "UTF-16LE";
                skip = 2;
            } else if (read >= 2 && bom[0] == (byte)0xFE && bom[1] == (byte)0xFF) {
                charset = "UTF-16BE";
                skip = 2;
            } else if (read >= 3 && bom[0] == (byte)0xEF && bom[1] == (byte)0xBB && bom[2] == (byte)0xBF) {
                charset = "UTF-8";
                skip = 3;
            }
            
            // Re-open or skip
            try (java.io.FileInputStream fis2 = new java.io.FileInputStream(file)) {
                if (skip > 0) fis2.skip(skip);
                try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(fis2, charset))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    String content = sb.toString();
                    Log.d("Lyrics", "Read file content (" + charset + "), length: " + content.length());
                    return content;
                }
            }
        } catch (IOException e) {
            Log.e("Lyrics", "Failed to read sidecar file", e);
            return null;
        }
    }


    private String manualScanForLyrics(String path) {
        File file = new File(path);
        if (!file.exists()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            return manualScanForLyrics(raf.getFD());
        } catch (Exception e) {
            return null;
        }
    }

    private String manualScanForLyrics(java.io.FileDescriptor fd) {
        try {
            // Reset position to start
            android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET);
            
            byte[] header = new byte[10];
            int read = 0;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(fd)) {
                read = fis.read(header);
            }
            if (read < 4) return null;

            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(read, 10); i++) {
                hex.append(String.format("%02x ", header[i]));
            }
            Log.d("Lyrics", "File header (first 10 bytes): " + hex.toString());

            // Check for ID3v2 (MP3)
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int version = header[3] & 0xFF;
                int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) |
                             ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
                
                Log.d("Lyrics", "Detected ID3v2." + version + ", tag size: " + tagSize);
                
                byte[] tagData = new byte[tagSize];
                android.system.Os.lseek(fd, 10, android.system.OsConstants.SEEK_SET);
                try (java.io.FileInputStream fis = new java.io.FileInputStream(fd)) {
                    int tagRead = 0;
                    while (tagRead < tagSize) {
                        int r = fis.read(tagData, tagRead, tagSize - tagRead);
                        if (r == -1) break;
                        tagRead += r;
                    }
                    if (tagRead > 0) {
                        return parseId3v2Lyrics(tagData, version);
                    }
                }
            }
            
            // Check for FLAC
            if (header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
                Log.d("Lyrics", "Detected FLAC");
                android.system.Os.lseek(fd, 4, android.system.OsConstants.SEEK_SET);
                try (java.io.FileInputStream flacFis = new java.io.FileInputStream(fd)) {
                    return parseFlacLyrics(flacFis);
                }
            }

            // Check for MP4/M4A (starts with ftyp)
            // Usually header[4-7] is "ftyp"
            if (read >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
                Log.d("Lyrics", "Detected MP4/M4A");
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET);
                try (java.io.FileInputStream mp4Fis = new java.io.FileInputStream(fd)) {
                    return parseMp4Lyrics(mp4Fis);
                }
            }

        } catch (Exception e) {
            Log.e("Lyrics", "Manual scan failed", e);
        }
        return null;
    }

    private String parseMp4Lyrics(java.io.InputStream is) throws IOException {
        byte[] buffer = new byte[8];
        while (true) {
            int r = is.read(buffer);
            if (r < 8) break;
            
            long size = ((buffer[0] & 0xFFL) << 24) | ((buffer[1] & 0xFFL) << 16) | 
                       ((buffer[2] & 0xFFL) << 8) | (buffer[3] & 0xFFL);
            String type = new String(buffer, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            
            if ("moov".equals(type) || "udta".equals(type) || "meta".equals(type) || "ilst".equals(type)) {
                if ("meta".equals(type)) {
                    long skipped = is.skip(4);
                    if (skipped < 4) break;
                }
                continue; 
            }
            
            if ("©lyr".equals(type)) {
                if (is.read(buffer) == 8) {
                    long skipped = is.skip(8);
                    if (skipped < 8) break;
                    
                    int dataLen = (int) (size - 24); 
                    if (dataLen > 0 && dataLen < 100000) {
                        byte[] lyricsData = new byte[dataLen];
                        int totalRead = 0;
                        while (totalRead < dataLen) {
                            int read = is.read(lyricsData, totalRead, dataLen - totalRead);
                            if (read == -1) break;
                            totalRead += read;
                        }
                        return new String(lyricsData, java.nio.charset.StandardCharsets.UTF_8).trim();
                    }
                }
                break;
            }
            
            if (size < 8) break;
            long remaining = size - 8;
            while (remaining > 0) {
                long s = is.skip(remaining);
                if (s <= 0) break;
                remaining -= s;
            }
        }
        return null;
    }



    private String parseId3v2Lyrics(byte[] data, int version) {
        int pos = 0;
        while (pos + 10 < data.length) {
            String frameId;
            int frameSize;
            if (version == 2) {
                frameId = new String(data, pos, 3, java.nio.charset.StandardCharsets.ISO_8859_1);
                frameSize = ((data[pos + 3] & 0xFF) << 16) | ((data[pos + 4] & 0xFF) << 8) | (data[pos + 5] & 0xFF);
                pos += 6;
            } else {
                frameId = new String(data, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (version == 4) {
                    frameSize = ((data[pos + 4] & 0x7F) << 21) | ((data[pos + 5] & 0x7F) << 14) |
                                ((data[pos + 6] & 0x7F) << 7) | (data[pos + 7] & 0x7F);
                } else {
                    frameSize = ((data[pos + 4] & 0xFF) << 24) | ((data[pos + 5] & 0xFF) << 16) |
                                ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
                }
                pos += 10;
            }

            if (frameSize <= 0 || pos + frameSize > data.length) break;

            if ("USLT".equals(frameId) || "ULT".equals(frameId)) {
                int encoding = data[pos] & 0xFF;
                // Skip encoding (1), language (3), and description (varies)
                int start = pos + 4;
                while (start < pos + frameSize && data[start] != 0) {
                    if (encoding == 1 || encoding == 2) { // UTF-16
                        if (start + 1 < pos + frameSize && data[start] == 0 && data[start+1] == 0) {
                            start += 2;
                            break;
                        }
                        start += 2;
                    } else {
                        start++;
                    }
                }
                if (start < pos + frameSize && data[start] == 0) start++; 

                java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.ISO_8859_1;
                if (encoding == 1) charset = java.nio.charset.StandardCharsets.UTF_16;
                else if (encoding == 2) charset = java.nio.charset.StandardCharsets.UTF_16BE;
                else if (encoding == 3) charset = java.nio.charset.StandardCharsets.UTF_8;

                return new String(data, start, pos + frameSize - start, charset).trim();
            }
            pos += frameSize;
        }
        return null;
    }

    private String parseFlacLyrics(java.io.InputStream is) throws IOException {
        boolean lastBlock = false;
        while (!lastBlock) {
            byte[] blockHeader = new byte[4];
            if (is.read(blockHeader) != 4) break;
            
            lastBlock = (blockHeader[0] & 0x80) != 0;
            int type = blockHeader[0] & 0x7F;
            int length = ((blockHeader[1] & 0xFF) << 16) | ((blockHeader[2] & 0xFF) << 8) | (blockHeader[3] & 0xFF);
            
            if (type == 4) { // VORBIS_COMMENT
                byte[] buffer = new byte[length];
                int read = 0;
                while (read < length) {
                    int r = is.read(buffer, read, length - read);
                    if (r == -1) break;
                    read += r;
                }
                
                int pos = 0;
                if (pos + 4 > length) break;
                int vendorLen = readInt32LE(buffer, pos); pos += 4;
                pos += vendorLen; // Skip vendor string
                
                if (pos + 4 > length) break;
                int commentCount = readInt32LE(buffer, pos); pos += 4;
                for (int i = 0; i < commentCount; i++) {
                    if (pos + 4 > length) break;
                    int commentLen = readInt32LE(buffer, pos); pos += 4;
                    if (pos + commentLen > length) break;
                    
                    String comment = new String(buffer, pos, commentLen, java.nio.charset.StandardCharsets.UTF_8);
                    pos += commentLen;
                    
                    String upper = comment.toUpperCase();
                    if (upper.startsWith("LYRICS=") || upper.startsWith("UNSYNCEDLYRICS=")) {
                        return comment.substring(comment.indexOf('=') + 1).trim();
                    }
                }
            } else {
                long remaining = length;
                while (remaining > 0) {
                    long s = is.skip(remaining);
                    if (s <= 0) break;
                    remaining -= s;
                }
            }
        }
        return null;
    }


    private int readInt32LE(byte[] data, int pos) {
        return (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) | 
               ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
    }


    private void updateLyrics() {
        if (mService == null || mLyricsView == null) return;
        try {
            String path = mService.getPath();
            if (path == null) return;
            
            if (path.equals(mLyricsCachePath)) {
                mLyricsView.setLyrics(mLyricsCacheContent);
                mLyricsView.updateTime(mService.position());
                return;
            }

            mLyricsHandler.removeMessages(LOAD_LYRICS);
            mLyricsHandler.obtainMessage(LOAD_LYRICS, path).sendToTarget();
        } catch (RemoteException e) {
            Log.e("MediaPlaybackActivity", "Failed to get path for lyrics", e);
        }
    }

    public class LyricsHandler extends Handler {
        public LyricsHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == LOAD_LYRICS) {
                String path = (String) msg.obj;
                String lyrics = loadLyrics(path);
                mHandler.obtainMessage(LYRICS_LOADED, new LyricsResult(path, lyrics)).sendToTarget();
            }
        }
    }

    private static class LyricsResult {
        String path;
        String lyrics;
        LyricsResult(String p, String l) {
            path = p;
            lyrics = l;
        }
    }

    private View.OnClickListener mQueueListener = new View.OnClickListener() {
        public void onClick(View v) {
            Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setClass(MediaPlaybackActivity.this, TrackBrowserActivity.class);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("playlist", "nowplaying");
            startActivity(intent);
        }
    };
    
    private View.OnClickListener mShuffleListener = new View.OnClickListener() {
        public void onClick(View v) {
            toggleShuffle();
        }
    };

    private View.OnClickListener mRepeatListener = new View.OnClickListener() {
        public void onClick(View v) {
            cycleRepeat();
        }
    };

    private View.OnClickListener mPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
        }
    };

    private View.OnClickListener mPrevListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mService == null) return;
            try {
                if (mService.position() < 2000) {
                    mService.prev();
                } else {
                    mService.seek(0);
                    mService.play();
                }
            } catch (RemoteException ex) {
            }
        }
    };

    private View.OnClickListener mNextListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mService == null) return;
            try {
                mService.next();
            } catch (RemoteException ex) {
            }
        }
    };

    private RepeatingImageButton.RepeatListener mRewListener =
        new RepeatingImageButton.RepeatListener() {
        public void onRepeat(View v, long howlong, int repcnt) {
            scanBackward(repcnt, howlong);
        }
    };
    
    private RepeatingImageButton.RepeatListener mFfwdListener =
        new RepeatingImageButton.RepeatListener() {
        public void onRepeat(View v, long howlong, int repcnt) {
            scanForward(repcnt, howlong);
        }
    };
   
    @Override
    public void onStop() {
        paused = true;
        mHandler.removeMessages(REFRESH);
        unregisterReceiver(mStatusListener);
        MusicUtils.unbindFromService(mToken);
        mService = null;
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        paused = false;

        mToken = MusicUtils.bindToService(this, osc);
        if (mToken == null) {
            // something went wrong
            mHandler.sendEmptyMessage(QUIT);
        }
        
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.PLAYSTATE_CHANGED);
        f.addAction(MediaPlaybackService.META_CHANGED);
        ContextCompat.registerReceiver(this, mStatusListener, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        updateTrackInfo();
        long next = refreshNow();
        queueNextRefresh(next);
    }
    
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        updateTrackInfo();
        setPauseButtonImage();
    }
    
    @Override
    public void onDestroy()
    {
        mAlbumArtWorker.quit();
        super.onDestroy();
        //System.out.println("***************** playback activity onDestroy\n");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // Don't show the menu items if we got launched by path/filedescriptor, or
        // if we're in one shot mode. In most cases, these menu items are not
        // useful in those modes, so for consistency we never show them in these
        // modes, instead of tailoring them to the specific file being played.
        if (MusicUtils.getCurrentAudioId() >= 0) {
            menu.add(0, GOTO_START, 0, R.string.goto_start).setIcon(R.drawable.ic_menu_music_library);
            menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle); // icon will be set in onPrepareOptionsMenu()
            SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0,
                    R.string.add_to_playlist).setIcon(android.R.drawable.ic_menu_add);
            // these next two are in a separate group, so they can be shown/hidden as needed
            // based on the keyguard state
            menu.add(1, USE_AS_RINGTONE, 0, R.string.ringtone_menu_short)
                    .setIcon(R.drawable.ic_menu_set_as_ringtone);
            menu.add(1, DELETE_ITEM, 0, R.string.delete_item)
                    .setIcon(R.drawable.ic_menu_delete);

            menu.add(0, EFFECTS_PANEL, 0, R.string.effectspanel).setIcon(R.drawable.ic_menu_eq);

            return true;
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mService == null) return false;
        MenuItem item = menu.findItem(PARTY_SHUFFLE);
        if (item != null) {
            int shuffle = MusicUtils.getCurrentShuffleMode();
            if (shuffle == MediaPlaybackService.SHUFFLE_AUTO) {
                item.setIcon(R.drawable.ic_menu_party_shuffle);
                item.setTitle(R.string.party_shuffle_off);
            } else {
                item.setIcon(R.drawable.ic_menu_party_shuffle);
                item.setTitle(R.string.party_shuffle);
            }
        }

        item = menu.findItem(ADD_TO_PLAYLIST);
        if (item != null) {
            SubMenu sub = item.getSubMenu();
            MusicUtils.makePlaylistMenu(this, sub);
        }

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        menu.setGroupVisible(1, !km.inKeyguardRestrictedInputMode());

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        Intent intent;
        try {
            switch (item.getItemId()) {
                case GOTO_START:
                    intent = new Intent();
                    intent.setClass(this, MusicBrowserActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                    break;
                case USE_AS_RINGTONE: {
                    // Set the system setting to make this the current ringtone
                    if (mService != null) {
                        MusicUtils.setRingtone(this, mService.getAudioId());
                    }
                    return true;
                }
                case PARTY_SHUFFLE:
                    MusicUtils.togglePartyShuffle();
                    setShuffleButtonImage();
                    break;
                    
                case NEW_PLAYLIST: {
                    intent = new Intent();
                    intent.setClass(this, CreatePlaylist.class);
                    startActivityForResult(intent, NEW_PLAYLIST);
                    return true;
                }

                case PLAYLIST_SELECTED: {
                    long [] list = new long[1];
                    list[0] = MusicUtils.getCurrentAudioId();
                    long playlist = item.getIntent().getLongExtra("playlist", 0);
                    MusicUtils.addToPlaylist(this, list, playlist);
                    return true;
                }
                
                case DELETE_ITEM: {
                    if (mService != null) {
                        long [] list = new long[1];
                        list[0] = MusicUtils.getCurrentAudioId();
                        Bundle b = new Bundle();
                        String f;
                        if (android.os.Environment.isExternalStorageRemovable()) {
                            f = getString(R.string.delete_song_desc, mService.getTrackName());
                        } else {
                            f = getString(R.string.delete_song_desc_nosdcard, mService.getTrackName());
                        }
                        b.putString("description", f);
                        b.putLongArray("items", list);
                        intent = new Intent();
                        intent.setClass(this, DeleteItems.class);
                        intent.putExtras(b);
                        startActivityForResult(intent, -1);
                    }
                    return true;
                }

                case EFFECTS_PANEL: {
                    launchEqualizer();
                    return true;
                }
            }
        } catch (RemoteException ex) {
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (resultCode != RESULT_OK) {
            return;
        }
        switch (requestCode) {
            case NEW_PLAYLIST:
                Uri uri = intent.getData();
                if (uri != null) {
                    long [] list = new long[1];
                    list[0] = MusicUtils.getCurrentAudioId();
                    int playlist = Integer.parseInt(uri.getLastPathSegment());
                    MusicUtils.addToPlaylist(this, list, playlist);
                }
                break;
        }
    }

    private void launchEqualizer() {
        try {
            int sessionId = 0;
            if (mService != null) {
                try {
                    sessionId = mService.getAudioSessionId();
                } catch (RemoteException e) {
                    // fall back to default session
                }
            }

            // 1. Standard AOSP Equalizer Intent
            Intent baseIntent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            baseIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
            baseIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            baseIntent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);

            List<Intent> targetedIntents = new ArrayList<>();
            List<String> addedPackages = new ArrayList<>();
            PackageManager pm = getPackageManager();

            // 2. Query all apps that handle the standard Audio Effect intent
            // This works because we added <queries> for this intent action.
            List<ResolveInfo> infos = pm.queryIntentActivities(baseIntent, 0);
            for (ResolveInfo info : infos) {
                if (info.activityInfo != null) {
                    String pkg = info.activityInfo.packageName;
                    if (!addedPackages.contains(pkg) && !pkg.equals(getPackageName())) {
                        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                        intent.setPackage(pkg);
                        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
                        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
                        targetedIntents.add(intent);
                        addedPackages.add(pkg);
                    }
                }
            }

            // 3. Define known 3rd party and OEM Equalizer Targets (Package, Activity, Action)
            // We use this for apps that might NOT handle the standard intent but we still want to support.
            String[][] targets = {
                {"com.wstxda.viper4android", "com.wstxda.viper4android.MainActivity", null}, // Viper4Android RE
                {"com.pittvandewitt.viperfx", "com.pittvandewitt.viperfx.activity.MainActivity", null}, // Viper4Android FX New
                {"com.audlabs.viperfx", "com.audlabs.viperfx.main.MainActivity", null}, // Viper4Android FX Alternative
                {"com.vipercn.viper4android_v2", "com.vipercn.viper4android_v2.activity.ViPER4Android", null}, // Viper4Android Legacy
                {"com.mi.earphone", null, null}, // Xiaomi Earbuds (Standalone)
                {"com.miui.misound", "com.miui.misound.MiSoundActivity", "miui.intent.action.SOUND_EFFECTS"}, // MIUI MiSound
                {"org.lineageos.audiofx", "org.lineageos.audiofx.activity.ActivityMusic", null}, // LineageOS AudioFX
                {"com.dolby.daxui", "com.dolby.daxui.MainActivity", null}, // Dolby Generic
                {"com.dolby.android.daxui", "com.dolby.android.daxui.MainActivity", null}, // Dolby Motorola
                {"com.sec.android.app.soundalive", "com.sec.android.app.soundalive.activity.SoundAliveActivity", null}, // Samsung
                {"com.miui.player", "com.miui.player.ui.EqualizerActivity", null}, // MIUI Player EQ
                {"com.sonyericsson.music", "com.sonyericsson.music.equalizer.EqualizerActivity", null}, // Sony Xperia
                {"com.sony.songpal", "com.sony.songpal.mdr.view.MdrMainActivity", null}, // Sony Sound Connect
                {"com.oppo.engineermode", "com.oppo.engineermode.audio.AudioEqualizer", null} // Oppo/OnePlus Dirac
            };

            for (String[] target : targets) {
                String pkg = target[0];
                String activity = target[1];
                String action = target[2];
                
                if (addedPackages.contains(pkg)) continue;

                Intent intent = null;
                // Try specific action if available
                if (action != null) {
                    Intent actionIntent = new Intent(action);
                    actionIntent.setPackage(pkg);
                    if (pm.resolveActivity(actionIntent, 0) != null) {
                        intent = actionIntent;
                    }
                }
                
                // Try specific component
                if (intent == null && activity != null) {
                    Intent componentIntent = new Intent();
                    componentIntent.setComponent(new ComponentName(pkg, activity));
                    if (pm.resolveActivity(componentIntent, 0) != null) {
                        intent = componentIntent;
                    }
                }
                
                // Fallback to package launch intent
                if (intent == null) {
                    intent = pm.getLaunchIntentForPackage(pkg);
                }

                if (intent != null) {
                    intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
                    intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                    targetedIntents.add(intent);
                    addedPackages.add(pkg);
                }
            }

            // 4. Always add System Sound Settings as a reliable fallback to force the chooser
            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS);
            if (pm.resolveActivity(settingsIntent, 0) != null) {
                targetedIntents.add(settingsIntent);
            }

            // 5. Create Chooser
            if (!targetedIntents.isEmpty()) {
                Intent chooserIntent = Intent.createChooser(targetedIntents.remove(0), getString(R.string.effectspanel));
                if (!targetedIntents.isEmpty()) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.toArray(new Parcelable[0]));
                }
                startActivityForResult(chooserIntent, EFFECTS_PANEL);
            } else {
                startActivityForResult(baseIntent, EFFECTS_PANEL);
            }

        } catch (Exception e) {
            // Fallback logic
            try {
                Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                if (mService != null) {
                    try {
                        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, mService.getAudioSessionId());
                    } catch (RemoteException ex) {
                        // ignore
                    }
                }
                i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                startActivityForResult(i, EFFECTS_PANEL);
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(this, R.string.effectspanel_not_found, Toast.LENGTH_SHORT).show();
            }
        }
    }
    private final int keyboard[][] = {
        {
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_I,
            KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_P,
        },
        {
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_J,
            KeyEvent.KEYCODE_K,
            KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_DEL,
        },
        {
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_N,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_ENTER
        }

    };

    private int lastX;
    private int lastY;

    private boolean seekMethod1(int keyCode)
    {
        if (mService == null) return false;
        for(int x=0;x<10;x++) {
            for(int y=0;y<3;y++) {
                if(keyboard[y][x] == keyCode) {
                    int dir = 0;
                    // top row
                    if(x == lastX && y == lastY) dir = 0;
                    else if (y == 0 && lastY == 0 && x > lastX) dir = 1;
                    else if (y == 0 && lastY == 0 && x < lastX) dir = -1;
                    // bottom row
                    else if (y == 2 && lastY == 2 && x > lastX) dir = -1;
                    else if (y == 2 && lastY == 2 && x < lastX) dir = 1;
                    // moving up
                    else if (y < lastY && x <= 4) dir = 1; 
                    else if (y < lastY && x >= 5) dir = -1; 
                    // moving down
                    else if (y > lastY && x <= 4) dir = -1; 
                    else if (y > lastY && x >= 5) dir = 1; 
                    lastX = x;
                    lastY = y;
                    try {
                        mService.seek(mService.position() + dir * 5);
                    } catch (RemoteException ex) {
                    }
                    refreshNow();
                    return true;
                }
            }
        }
        lastX = -1;
        lastY = -1;
        return false;
    }

    private boolean seekMethod2(int keyCode)
    {
        if (mService == null) return false;
        for(int i=0;i<10;i++) {
            if(keyboard[0][i] == keyCode) {
                int seekpercentage = 100*i/10;
                try {
                    mService.seek(mService.duration() * seekpercentage / 100);
                } catch (RemoteException ex) {
                }
                refreshNow();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            switch(keyCode)
            {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!useDpadMusicControl()) {
                        break;
                    }
                    if (mService != null) {
                        if (!mSeeking && mStartSeekPos >= 0) {
                            mPauseButton.requestFocus();
                            if (mStartSeekPos < 1000) {
                                mService.prev();
                            } else {
                                mService.seek(0);
                            }
                        } else {
                            scanBackward(-1, event.getEventTime() - event.getDownTime());
                            mPauseButton.requestFocus();
                            mStartSeekPos = -1;
                        }
                    }
                    mSeeking = false;
                    mPosOverride = -1;
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!useDpadMusicControl()) {
                        break;
                    }
                    if (mService != null) {
                        if (!mSeeking && mStartSeekPos >= 0) {
                            mPauseButton.requestFocus();
                            mService.next();
                        } else {
                            scanForward(-1, event.getEventTime() - event.getDownTime());
                            mPauseButton.requestFocus();
                            mStartSeekPos = -1;
                        }
                    }
                    mSeeking = false;
                    mPosOverride = -1;
                    return true;
            }
        } catch (RemoteException ex) {
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean useDpadMusicControl() {
        if (mDeviceHasDpad && (mPrevButton.isFocused() ||
                mNextButton.isFocused() ||
                mPauseButton.isFocused())) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        int direction = -1;
        int repcnt = event.getRepeatCount();

        if((seekmethod==0)?seekMethod1(keyCode):seekMethod2(keyCode))
            return true;

        switch(keyCode)
        {
/*
            // image scale
            case KeyEvent.KEYCODE_Q: av.adjustParams(-0.05, 0.0, 0.0, 0.0, 0.0,-1.0); break;
            case KeyEvent.KEYCODE_E: av.adjustParams( 0.05, 0.0, 0.0, 0.0, 0.0, 1.0); break;
            // image translate
            case KeyEvent.KEYCODE_W: av.adjustParams(    0.0, 0.0,-1.0, 0.0, 0.0, 0.0); break;
            case KeyEvent.KEYCODE_X: av.adjustParams(    0.0, 0.0, 1.0, 0.0, 0.0, 0.0); break;
            case KeyEvent.KEYCODE_A: av.adjustParams(    0.0,-1.0, 0.0, 0.0, 0.0, 0.0); break;
            case KeyEvent.KEYCODE_D: av.adjustParams(    0.0, 1.0, 0.0, 0.0, 0.0, 0.0); break;
            // camera rotation
            case KeyEvent.KEYCODE_R: av.adjustParams(    0.0, 0.0, 0.0, 0.0, 0.0,-1.0); break;
            case KeyEvent.KEYCODE_U: av.adjustParams(    0.0, 0.0, 0.0, 0.0, 0.0, 1.0); break;
            // camera translate
            case KeyEvent.KEYCODE_Y: av.adjustParams(    0.0, 0.0, 0.0, 0.0,-1.0, 0.0); break;
            case KeyEvent.KEYCODE_N: av.adjustParams(    0.0, 0.0, 0.0, 0.0, 1.0, 0.0); break;
            case KeyEvent.KEYCODE_G: av.adjustParams(    0.0, 0.0, 0.0,-1.0, 0.0, 0.0); break;
            case KeyEvent.KEYCODE_J: av.adjustParams(    0.0, 0.0, 0.0, 1.0, 0.0, 0.0); break;

*/

            case KeyEvent.KEYCODE_SLASH:
                seekmethod = 1 - seekmethod;
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!useDpadMusicControl()) {
                    break;
                }
                if (!mPrevButton.hasFocus()) {
                    mPrevButton.requestFocus();
                }
                scanBackward(repcnt, event.getEventTime() - event.getDownTime());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!useDpadMusicControl()) {
                    break;
                }
                if (!mNextButton.hasFocus()) {
                    mNextButton.requestFocus();
                }
                scanForward(repcnt, event.getEventTime() - event.getDownTime());
                return true;

            case KeyEvent.KEYCODE_S:
                toggleShuffle();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
                doPauseResume();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    private void scanBackward(int repcnt, long delta) {
        if(mService == null) return;
        try {
            if(repcnt == 0) {
                mStartSeekPos = mService.position();
                mLastSeekEventTime = 0;
                mSeeking = false;
            } else {
                mSeeking = true;
                if (delta < 5000) {
                    // seek at 10x speed for the first 5 seconds
                    delta = delta * 10; 
                } else {
                    // seek at 40x after that
                    delta = 50000 + (delta - 5000) * 40;
                }
                long newpos = mStartSeekPos - delta;
                if (newpos < 0) {
                    // move to previous track
                    mService.prev();
                    long duration = mService.duration();
                    mStartSeekPos += duration;
                    newpos += duration;
                }
                if (((delta - mLastSeekEventTime) > 250) || repcnt < 0){
                    mService.seek(newpos);
                    mLastSeekEventTime = delta;
                }
                if (repcnt >= 0) {
                    mPosOverride = newpos;
                } else {
                    mPosOverride = -1;
                }
                refreshNow();
            }
        } catch (RemoteException ex) {
        }
    }

    private void scanForward(int repcnt, long delta) {
        if(mService == null) return;
        try {
            if(repcnt == 0) {
                mStartSeekPos = mService.position();
                mLastSeekEventTime = 0;
                mSeeking = false;
            } else {
                mSeeking = true;
                if (delta < 5000) {
                    // seek at 10x speed for the first 5 seconds
                    delta = delta * 10; 
                } else {
                    // seek at 40x after that
                    delta = 50000 + (delta - 5000) * 40;
                }
                long newpos = mStartSeekPos + delta;
                long duration = mService.duration();
                if (newpos >= duration) {
                    // move to next track
                    mService.next();
                    mStartSeekPos -= duration; // is OK to go negative
                    newpos -= duration;
                }
                if (((delta - mLastSeekEventTime) > 250) || repcnt < 0){
                    mService.seek(newpos);
                    mLastSeekEventTime = delta;
                }
                if (repcnt >= 0) {
                    mPosOverride = newpos;
                } else {
                    mPosOverride = -1;
                }
                refreshNow();
            }
        } catch (RemoteException ex) {
        }
    }
    
    private void doPauseResume() {
        try {
            if(mService != null) {
                if (mService.isPlaying()) {
                    mService.pause();
                } else {
                    mService.play();
                }
                refreshNow();
                setPauseButtonImage();
            }
        } catch (RemoteException ex) {
        }
    }
    
    private void toggleShuffle() {
        if (mService == null) {
            return;
        }
        try {
            int shuffle = mService.getShuffleMode();
            if (shuffle == MediaPlaybackService.SHUFFLE_NONE) {
                mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NORMAL);
                if (mService.getRepeatMode() == MediaPlaybackService.REPEAT_CURRENT) {
                    mService.setRepeatMode(MediaPlaybackService.REPEAT_ALL);
                    setRepeatButtonImage();
                }
                showToast(R.string.shuffle_on_notif);
            } else if (shuffle == MediaPlaybackService.SHUFFLE_NORMAL ||
                    shuffle == MediaPlaybackService.SHUFFLE_AUTO) {
                mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NONE);
                showToast(R.string.shuffle_off_notif);
            } else {
                Log.e("MediaPlaybackActivity", "Invalid shuffle mode: " + shuffle);
            }
            setShuffleButtonImage();
        } catch (RemoteException ex) {
        }
    }
    
    private void cycleRepeat() {
        if (mService == null) {
            return;
        }
        try {
            int mode = mService.getRepeatMode();
            if (mode == MediaPlaybackService.REPEAT_NONE) {
                mService.setRepeatMode(MediaPlaybackService.REPEAT_ALL);
                showToast(R.string.repeat_all_notif);
            } else if (mode == MediaPlaybackService.REPEAT_ALL) {
                mService.setRepeatMode(MediaPlaybackService.REPEAT_CURRENT);
                if (mService.getShuffleMode() != MediaPlaybackService.SHUFFLE_NONE) {
                    mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NONE);
                    setShuffleButtonImage();
                }
                showToast(R.string.repeat_current_notif);
            } else {
                mService.setRepeatMode(MediaPlaybackService.REPEAT_NONE);
                showToast(R.string.repeat_off_notif);
            }
            setRepeatButtonImage();
        } catch (RemoteException ex) {
        }
        
    }
    
    private void showToast(int resid) {
        if (mToast == null) {
            mToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
        }
        mToast.setText(resid);
        mToast.show();
    }

    private void startPlayback() {

        if(mService == null)
            return;
        Intent intent = getIntent();
        String filename = "";
        Uri uri = intent.getData();
        if (uri != null && uri.toString().length() > 0) {
            // If this is a file:// URI, just use the path directly instead
            // of going through the open-from-filedescriptor codepath.
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                filename = uri.getPath();
            } else {
                filename = uri.toString();
            }
            try {
                mService.stop();
                mService.openFile(filename);
                mService.play();
                setIntent(new Intent());
            } catch (Exception ex) {
                Log.d("MediaPlaybackActivity", "couldn't start playback: " + ex);
            }
        }

        updateTrackInfo();
        long next = refreshNow();
        queueNextRefresh(next);
    }

    private ServiceConnection osc = new ServiceConnection() {
            public void onServiceConnected(ComponentName classname, IBinder obj) {
                mService = IMediaPlaybackService.Stub.asInterface(obj);
                startPlayback();
                try {
                    // Assume something is playing when the service says it is,
                    // but also if the audio ID is valid but the service is paused.
                    if (mService.getAudioId() >= 0 || mService.isPlaying() ||
                            mService.getPath() != null) {
                        // something is playing now, we're done
                        mRepeatButton.setVisibility(View.VISIBLE);
                        mShuffleButton.setVisibility(View.VISIBLE);
                        mQueueButton.setVisibility(View.VISIBLE);
                        setRepeatButtonImage();
                        setShuffleButtonImage();
                        setPauseButtonImage();
                        return;
                    }
                } catch (RemoteException ex) {
                }
                // Service is dead or not playing anything. If we got here as part
                // of a "play this file" Intent, exit. Otherwise go to the Music
                // app start screen.
                if (getIntent().getData() == null) {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setClass(MediaPlaybackActivity.this, MusicBrowserActivity.class);
                    startActivity(intent);
                }
                finish();
            }
            public void onServiceDisconnected(ComponentName classname) {
                mService = null;
            }
    };

    private void setRepeatButtonImage() {
        if (mService == null) return;
        try {
            switch (mService.getRepeatMode()) {
                case MediaPlaybackService.REPEAT_ALL:
                    mRepeatButton.setImageResource(R.drawable.ic_mp_repeat_all_btn);
                    break;
                case MediaPlaybackService.REPEAT_CURRENT:
                    mRepeatButton.setImageResource(R.drawable.ic_mp_repeat_once_btn);
                    break;
                default:
                    mRepeatButton.setImageResource(R.drawable.ic_mp_repeat_off_btn);
                    break;
            }
        } catch (RemoteException ex) {
        }
    }
    
    private void setShuffleButtonImage() {
        if (mService == null) return;
        try {
            switch (mService.getShuffleMode()) {
                case MediaPlaybackService.SHUFFLE_NONE:
                    mShuffleButton.setImageResource(R.drawable.ic_mp_shuffle_off_btn);
                    break;
                case MediaPlaybackService.SHUFFLE_AUTO:
                    mShuffleButton.setImageResource(R.drawable.ic_mp_partyshuffle_on_btn);
                    break;
                default:
                    mShuffleButton.setImageResource(R.drawable.ic_mp_shuffle_on_btn);
                    break;
            }
        } catch (RemoteException ex) {
        }
    }
    
    private void setPauseButtonImage() {
        try {
            boolean isPlaying = mService != null && mService.isPlaying();
            if (isPlaying) {
                mPauseButton.setImageResource(R.drawable.ic_pause);
            } else {
                mPauseButton.setImageResource(R.drawable.ic_play);
            }
            if (mPauseButton instanceof FlowerPlayPauseButton) {
                ((FlowerPlayPauseButton) mPauseButton).setPlaying(isPlaying);
            }
            if (mProgress instanceof WavySeekBar) {
                ((WavySeekBar) mProgress).setPlaying(isPlaying);
            }
        } catch (RemoteException ex) {
        }
    }
    
    private ImageView mAlbum;
    private TextView mCurrentTime;
    private TextView mTotalTime;
    private TextView mArtistName;
    private TextView mAlbumName;
    private TextView mTrackName;
    private ProgressBar mProgress;
    private long mPosOverride = -1;
    private boolean mFromTouch = false;
    private long mDuration;
    private int seekmethod;
    private boolean paused;

    private static final int REFRESH = 1;
    private static final int QUIT = 2;
    private static final int GET_ALBUM_ART = 3;
    private static final int ALBUM_ART_DECODED = 4;
    private static final int LOAD_LYRICS = 5;
    private static final int LYRICS_LOADED = 6;

    private void queueNextRefresh(long delay) {
        if (!paused) {
            Message msg = mHandler.obtainMessage(REFRESH);
            mHandler.removeMessages(REFRESH);
            mHandler.sendMessageDelayed(msg, delay);
        }
    }

    private long refreshNow() {
        if(mService == null)
            return 500;
        try {
            long pos = mPosOverride < 0 ? mService.position() : mPosOverride;
            if ((pos >= 0) && (mDuration > 0)) {
                mCurrentTime.setText(MusicUtils.makeTimeString(this, pos / 1000));
                int progress = (int) (1000 * pos / mDuration);
                mProgress.setProgress(progress);
                
                if (mLyricsView != null && mLyricsView.getVisibility() == View.VISIBLE) {
                    mLyricsView.updateTime(pos);
                }
                
                if (mService.isPlaying()) {
                    mCurrentTime.setVisibility(View.VISIBLE);
                } else {
                    // blink the counter
                    int vis = mCurrentTime.getVisibility();
                    mCurrentTime.setVisibility(vis == View.INVISIBLE ? View.VISIBLE : View.INVISIBLE);
                    return 500;
                }
            } else {
                mCurrentTime.setText("--:--");
                mProgress.setProgress(1000);
            }
            // calculate the number of milliseconds until the next full second, so
            // the counter can be updated at just the right time
            long remaining = 1000 - (pos % 1000);

            // approximate how often we would need to refresh the slider to
            // move it smoothly
            int width = mProgress.getWidth();
            if (width == 0) width = 320;
            long smoothrefreshtime = mDuration / width;

            if (smoothrefreshtime > remaining) return remaining;
            if (smoothrefreshtime < 200) return 200;
            return smoothrefreshtime;
        } catch (RemoteException ex) {
        }
        return 500;
    }
    
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ALBUM_ART_DECODED:
                    Bitmap bm = (Bitmap)msg.obj;
                    mAlbum.setImageBitmap(bm);
                    mAlbum.getDrawable().setDither(true);
                    if (bm != null) {
                        Palette.from(bm).generate(palette -> {
                            if (palette != null) {
                                int dominantColor = palette.getDominantColor(Color.BLACK);
                                int vibrantColor = palette.getVibrantColor(dominantColor);
                                int lightVibrant = palette.getLightVibrantColor(vibrantColor);
                                
                                // Prefer LightVibrant for the top to ensure color "pops" even with dark art
                                int topBaseColor = (Color.luminance(lightVibrant) > Color.luminance(vibrantColor)) ? lightVibrant : vibrantColor;
                                
                                // Top is more colorful
                                int topColor = ColorUtils.blendARGB(topBaseColor, Color.BLACK, 0.55f);
                                int bottomColor = ColorUtils.blendARGB(dominantColor, Color.BLACK, 0.55f);

                                View root = findViewById(R.id.player_root);
                                if (root != null) {
                                    GradientDrawable gd = new GradientDrawable(
                                        GradientDrawable.Orientation.TOP_BOTTOM,
                                        new int[] { topColor, bottomColor }
                                    );
                                    root.setBackground(gd);
                                }
                                
                                // Bottom bar background - slightly darker than bottom gradient for separation
                                int bottomBarColor = ColorUtils.blendARGB(dominantColor, Color.BLACK, 0.60f);
                                View bottomBar = findViewById(R.id.bottom_controls_bar);
                                if (bottomBar != null) {
                                    bottomBar.setBackgroundColor(bottomBarColor);
                                }

                                // Sync System Bars
                                Window window = getWindow();
                                window.setStatusBarColor(topColor);
                                window.setNavigationBarColor(bottomBarColor);
                                
                                // Ensure system icons are white for dark background
                                WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, window.getDecorView());
                                controller.setAppearanceLightStatusBars(false);
                                controller.setAppearanceLightNavigationBars(false);

                                // Force main text to WHITE as requested
                                mTrackName.setTextColor(Color.WHITE);
                                mArtistName.setTextColor(Color.WHITE);
                                mAlbumName.setTextColor(Color.LTGRAY);
                                mCurrentTime.setTextColor(Color.WHITE);
                                mTotalTime.setTextColor(Color.WHITE);
                                
                                // Update Play/Pause Button
                                if (mPauseButton instanceof FlowerPlayPauseButton) {
                                    FlowerPlayPauseButton flowerBtn = (FlowerPlayPauseButton) mPauseButton;
                                    int btnColor = vibrantColor;
                                    if (Color.luminance(btnColor) < 0.2) {
                                        btnColor = palette.getLightVibrantColor(Color.WHITE);
                                    }
                                    flowerBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnColor));
                                    int iconColor = Color.luminance(btnColor) > 0.5 ? Color.BLACK : Color.WHITE;
                                    flowerBtn.setImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
                                } else if (mPauseButton instanceof com.google.android.material.floatingactionbutton.FloatingActionButton) {
                                    com.google.android.material.floatingactionbutton.FloatingActionButton fab = 
                                        (com.google.android.material.floatingactionbutton.FloatingActionButton) mPauseButton;
                                    
                                    int fabBgColor = vibrantColor;
                                    // Ensure FAB is visible
                                    if (Color.luminance(fabBgColor) < 0.2) {
                                        fabBgColor = palette.getLightVibrantColor(Color.WHITE);
                                    }
                                    
                                    fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(fabBgColor));
                                    int iconColor = Color.luminance(fabBgColor) > 0.5 ? Color.BLACK : Color.WHITE;
                                    fab.setSupportImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
                                }

                                // Update SeekBar colors
                                if (mProgress instanceof WavySeekBar) {
                                    int accentColor = palette.getLightVibrantColor(vibrantColor);
                                    if (Color.luminance(accentColor) < 0.4) {
                                        accentColor = Color.WHITE;
                                    }
                                    ((WavySeekBar) mProgress).setWaveColors(accentColor, Color.argb(64, 255, 255, 255));
                                    
                                    // Sync the dot (thumb) color
                                    ((WavySeekBar) mProgress).setThumbTintList(android.content.res.ColorStateList.valueOf(accentColor));
                                }

                                if (mLyricsView != null) {
                                    int accentColor = palette.getLightVibrantColor(vibrantColor);
                                    if (Color.luminance(accentColor) < 0.4) {
                                        accentColor = Color.WHITE;
                                    }
                                    mLyricsView.setLyricColors(accentColor, Color.argb(160, 255, 255, 255));
                                }

                                // Update icon tints for other buttons - use WHITE for contrast
                                android.content.res.ColorStateList whiteTint = android.content.res.ColorStateList.valueOf(Color.WHITE);
                                
                                mPrevButton.setImageTintList(whiteTint);
                                mNextButton.setImageTintList(whiteTint);
                                mRepeatButton.setImageTintList(whiteTint);
                                mShuffleButton.setImageTintList(whiteTint);
                                mQueueButton.setImageTintList(whiteTint);
                                
                                View btnQueue = findViewById(R.id.btn_queue);
                                if (btnQueue instanceof ImageView) {
                                    ((ImageView) btnQueue).setImageTintList(whiteTint);
                                }
                                
                                View btnLyrics = findViewById(R.id.btn_lyrics);
                                if (btnLyrics instanceof ImageView) {
                                    ((ImageView) btnLyrics).setImageTintList(whiteTint);
                                }
                                
                                View btnClose = findViewById(R.id.btn_close);
                                if (btnClose instanceof ImageView) {
                                    ((ImageView) btnClose).setImageTintList(whiteTint);
                                }
                                
                                View btnMore = findViewById(R.id.btn_more);
                                if (btnMore instanceof ImageView) {
                                    ((ImageView) btnMore).setImageTintList(whiteTint);
                                }
                            }
                        });
                    }
                    break;

                case LYRICS_LOADED:
                    LyricsResult result = (LyricsResult) msg.obj;
                    mLyricsCachePath = result.path;
                    mLyricsCacheContent = result.lyrics;
                    if (mLyricsView != null && mLyricsView.getVisibility() == View.VISIBLE) {
                        mLyricsView.setLyrics(result.lyrics);
                        try {
                            if (mService != null) {
                                mLyricsView.updateTime(mService.position());
                            }
                        } catch (RemoteException e) {}
                    }
                    break;

                case REFRESH:
                    long next = refreshNow();
                    queueNextRefresh(next);
                    break;
                    
                case QUIT:
                    // This can be moved back to onCreate once the bug that prevents
                    // Dialogs from being started from onCreate/onResume is fixed.
                    new AlertDialog.Builder(MediaPlaybackActivity.this)
                            .setTitle(R.string.service_start_error_title)
                            .setMessage(R.string.service_start_error_msg)
                            .setPositiveButton(R.string.service_start_error_button,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int whichButton) {
                                            finish();
                                        }
                                    })
                            .setCancelable(false)
                            .show();
                    break;

                default:
                    break;
            }
        }
    };

    private BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (action.equals(MediaPlaybackService.META_CHANGED)) {
                // redraw the artist/title info and
                // set new max for progress bar
                updateTrackInfo();
                setPauseButtonImage();
                queueNextRefresh(1);
            } else if (action.equals(MediaPlaybackService.PLAYSTATE_CHANGED)) {
                setPauseButtonImage();
            }
        }
    };

    private static class AlbumSongIdWrapper {
        public long albumid;
        public long songid;
        AlbumSongIdWrapper(long aid, long sid) {
            albumid = aid;
            songid = sid;
        }
    }
    
    private void updateTrackInfo() {
        if (mService == null) {
            return;
        }
        try {
            String path = mService.getPath();
            if (path == null) {
                finish();
                return;
            }
            
            long songid = mService.getAudioId(); 
            if (songid < 0 && path.toLowerCase().startsWith("http://")) {
                // Once we can get album art and meta data from MediaPlayer, we
                // can show that info again when streaming.
                ((View) mArtistName.getParent()).setVisibility(View.INVISIBLE);
                ((View) mAlbumName.getParent()).setVisibility(View.INVISIBLE);
                mAlbum.setVisibility(View.GONE);
                mTrackName.setText(path);
                mAlbumArtHandler.removeMessages(GET_ALBUM_ART);
                mAlbumArtHandler.obtainMessage(GET_ALBUM_ART, new AlbumSongIdWrapper(-1, -1)).sendToTarget();
            } else {
                updateVisibilityForLyrics();

                String artistName = mService.getArtistName();
                if (MediaStore.UNKNOWN_STRING.equals(artistName)) {
                    artistName = getString(R.string.unknown_artist_name);
                }
                mArtistName.setText(artistName);
                String albumName = mService.getAlbumName();
                long albumid = mService.getAlbumId();
                if (MediaStore.UNKNOWN_STRING.equals(albumName)) {
                    albumName = getString(R.string.unknown_album_name);
                    albumid = -1;
                }
                mAlbumName.setText(albumName);
                mTrackName.setText(mService.getTrackName());
                mAlbumArtHandler.removeMessages(GET_ALBUM_ART);
                mAlbumArtHandler.obtainMessage(GET_ALBUM_ART, new AlbumSongIdWrapper(albumid, songid)).sendToTarget();
                
                if (mLyricsView != null && mLyricsView.getVisibility() == View.VISIBLE) {
                    updateLyrics();
                }
            }

            mDuration = mService.duration();
            mTotalTime.setText(MusicUtils.makeTimeString(this, mDuration / 1000));
        } catch (RemoteException ex) {
            finish();
        }
    }
    
    public class AlbumArtHandler extends Handler {
        private long mAlbumId = -1;
        
        public AlbumArtHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg)
        {
            long albumid = ((AlbumSongIdWrapper) msg.obj).albumid;
            long songid = ((AlbumSongIdWrapper) msg.obj).songid;
            if (msg.what == GET_ALBUM_ART && (mAlbumId != albumid || albumid < 0)) {
                // while decoding the new image, show the default album art
                Message numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, null);
                mHandler.removeMessages(ALBUM_ART_DECODED);
                mHandler.sendMessageDelayed(numsg, 300);
                // Don't allow default artwork here, because we want to fall back to song-specific
                // album art if we can't find anything for the album.
                Bitmap bm = MusicUtils.getArtwork(MediaPlaybackActivity.this, songid, albumid, false);
                if (bm == null) {
                    bm = MusicUtils.getArtwork(MediaPlaybackActivity.this, songid, -1);
                    albumid = -1;
                }
                if (bm != null) {
                    numsg = mHandler.obtainMessage(ALBUM_ART_DECODED, bm);
                    mHandler.removeMessages(ALBUM_ART_DECODED);
                    mHandler.sendMessage(numsg);
                }
                mAlbumId = albumid;
            }
        }
    }
    
    private static class Worker implements Runnable {
        private final Object mLock = new Object();
        private Looper mLooper;
        
        /**
         * Creates a worker thread with the given name. The thread
         * then runs a {@link android.os.Looper}.
         * @param name A name for the new thread
         */
        Worker(String name) {
            Thread t = new Thread(null, this, name);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }
        
        public Looper getLooper() {
            return mLooper;
        }
        
        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Looper.loop();
        }
        
        public void quit() {
            mLooper.quit();
        }
    }
}
