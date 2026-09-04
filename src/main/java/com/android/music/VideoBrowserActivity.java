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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.Integer;

public class VideoBrowserActivity extends BaseActivity implements MusicUtils.Defs
{
    private RecyclerView mVideoList;
    private Cursor mCursor;
    private String mWhereClause;
    private String mSortOrder;

    public VideoBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        init();
    }

    public void init() {

        setContentView(R.layout.media_picker_activity);
        mVideoList = findViewById(R.id.list);
        mVideoList.setLayoutManager(new LinearLayoutManager(this));

        MakeCursor();

        if (mCursor == null) {
            MusicUtils.displayDatabaseError(this);
            return;
        }

        if (mCursor.getCount() > 0) {
            setTitle(R.string.videos_title);
        } else {
            setTitle(R.string.no_videos_title);
        }

        VideoListAdapter adapter = new VideoListAdapter(this, mCursor);
        mVideoList.setAdapter(adapter);
    }

    public void onListItemClick(int position, long id)
    {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        mCursor.moveToPosition(position);
        String type = mCursor.getString(mCursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE));
        intent.setDataAndType(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), type);
        
        startActivity(intent);
    }

    private void MakeCursor() {
        String[] cols = new String[] {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.ARTIST
        };
        ContentResolver resolver = getContentResolver();
        if (resolver == null) {
            System.out.println("resolver = null");
        } else {
            mSortOrder = MediaStore.Video.Media.TITLE + " COLLATE UNICODE";
            mWhereClause = MediaStore.Video.Media.TITLE + " != ''";
            mCursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cols, mWhereClause , null, mSortOrder);
        }
    }

    @Override
    public void onDestroy() {
        if (mCursor != null) {
            mCursor.close();
        }
        super.onDestroy();
    }

    static class VideoListAdapter extends CursorRecyclerViewAdapter<VideoListAdapter.ViewHolder> {
        private VideoBrowserActivity mActivity;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1;

            ViewHolder(View v) {
                super(v);
                text1 = (TextView) v.findViewById(android.R.id.text1);
            }
        }

        VideoListAdapter(VideoBrowserActivity activity, Cursor cursor) {
            super(activity, cursor);
            mActivity = activity;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder vh, Cursor cursor) {
            String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE));
            vh.text1.setText(title);

            final int position = cursor.getPosition();
            final long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
            vh.itemView.setOnClickListener(v -> {
                mActivity.onListItemClick(position, id);
            });
        }
    }
}

