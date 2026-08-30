package com.android.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatSeekBar;
import com.google.android.material.color.MaterialColors;

public class WavySeekBar extends AppCompatSeekBar {

    private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mWavePath = new Path();
    private final float mWaveLength = 60f;
    private final float mWaveHeight = 6f;
    private float mOffset = 0f;
    private boolean mIsPlaying = false;

    public WavySeekBar(Context context) {
        super(context);
        init();
    }

    public WavySeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        int colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        int colorOutline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);
        
        mWavePaint.setColor(colorOutline);
        mWavePaint.setStyle(Paint.Style.STROKE);
        mWavePaint.setStrokeWidth(8f);
        mWavePaint.setStrokeCap(Paint.Cap.ROUND);

        mProgressPaint.setColor(colorPrimary);
        mProgressPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStrokeWidth(8f);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);

        // Hide the default progress line to avoid "hindi pulido" look
        setProgressDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        setSplitTrack(false);
    }

    public void setPlaying(boolean playing) {
        if (mIsPlaying != playing) {
            mIsPlaying = playing;
            invalidate();
        }
    }

    public void setWaveColors(int progressColor, int backgroundColor) {
        mProgressPaint.setColor(progressColor);
        mWavePaint.setColor(backgroundColor);
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight();
        float centerY = height / 2f;
        
        float progressRatio = getMax() > 0 ? (float) getProgress() / getMax() : 0f;
        float progressX = getPaddingLeft() + width * progressRatio;

        // Draw background line (unplayed part)
        canvas.drawLine(progressX, centerY, getPaddingLeft() + width, centerY, mWavePaint);

        // Draw progress path
        mWavePath.reset();
        mWavePath.moveTo(getPaddingLeft(), centerY);
        
        if (mIsPlaying && progressX > getPaddingLeft()) {
            // Draw wavy path ONLY when playing
            for (float x = getPaddingLeft(); x <= progressX; x += 2f) {
                float y = (float) (centerY + Math.sin((x + mOffset) / mWaveLength * 2 * Math.PI) * mWaveHeight);
                mWavePath.lineTo(x, y);
            }
        } else {
            // Draw straight line when paused or at the start
            mWavePath.lineTo(progressX, centerY);
        }
        
        canvas.drawPath(mWavePath, mProgressPaint);
        
        // Only animate if playing and there is progress
        if (mIsPlaying && getProgress() > 0) {
            mOffset -= 2.0f; // Slightly faster for better effect
            invalidate();
        }

        // Draw standard thumb
        super.onDraw(canvas);
    }
}
