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

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class DeleteItems extends AppCompatActivity
{
    private TextView mPrompt;
    private MaterialButton mButton;
    private long [] mItemList;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        setContentView(R.layout.confirm_delete);

        mPrompt = findViewById(R.id.prompt);
        mButton = findViewById(R.id.delete);
        mButton.setOnClickListener(mButtonClicked);

        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        Bundle b = getIntent().getExtras();
        String desc = b.getString("description");
        mItemList = b.getLongArray("items");
        
        mPrompt.setText(desc);
    }
    
    private View.OnClickListener mButtonClicked = new View.OnClickListener() {
        public void onClick(View v) {
            // delete the selected item(s)
            MusicUtils.deleteTracks(DeleteItems.this, mItemList);
            finish();
        }
    };
}
