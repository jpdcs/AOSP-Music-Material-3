package com.android.music;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import androidx.appcompat.widget.AppCompatImageButton;
import com.google.android.material.color.MaterialColors;

public class FlowerPlayPauseButton extends AppCompatImageButton {

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    
    private boolean mIsPlaying = false;
    private float mMorphProgress = 0f; // 0 = Squircle, 1 = Flower
    private float mRotationAngle = 0f;
    private ValueAnimator mMorphAnimator;
    private ValueAnimator mRotationAnimator;
    
    private static final int PETALS = 16;
    private static final float FLOWER_AMPLITUDE = 0.06f;

    public FlowerPlayPauseButton(Context context) {
        super(context);
        init();
    }

    public FlowerPlayPauseButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FlowerPlayPauseButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mBackgroundPaint.setStyle(Paint.Style.FILL);
        mBackgroundPaint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer));
        
        // Remove default button background to avoid "white square"
        setBackground(null);
        // Ensure icon is centered
        setScaleType(ScaleType.CENTER_INSIDE);
        setPadding(0, 0, 0, 0);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int width = view.getWidth();
                int height = view.getHeight();
                if (width == 0 || height == 0) return;

                // For a "pino" (fine/smooth) shadow, we use setRoundRect
                // We morph the radius between a Squircle (30%) and a Circle (50%)
                float margin = width * 0.06f;
                float size = width - 2 * margin;
                float radius = size * (0.28f + 0.22f * mMorphProgress);
                
                outline.setRoundRect((int)margin, (int)margin, 
                    (int)(width - margin), (int)(height - margin), radius);
            }
        });
        // Enable shadows
        setElevation(8f);

        mRotationAnimator = ValueAnimator.ofFloat(0f, 1f);
        mRotationAnimator.setDuration(10000);
        mRotationAnimator.setInterpolator(new LinearInterpolator());
        mRotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mRotationAnimator.addUpdateListener(animation -> {
            mRotationAngle = animation.getAnimatedFraction() * 360f;
            invalidate();
        });
    }

    public void setPlaying(boolean playing) {
        if (mIsPlaying != playing) {
            mIsPlaying = playing;
            animateMorph(playing ? 1f : 0f);
            if (mIsPlaying) {
                mRotationAnimator.start();
            } else {
                mRotationAnimator.cancel();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mMorphAnimator != null) mMorphAnimator.cancel();
        if (mRotationAnimator != null) mRotationAnimator.cancel();
        super.onDetachedFromWindow();
    }
    
    @Override
    public void setBackgroundTintList(android.content.res.ColorStateList tint) {
        if (tint != null) {
            mBackgroundPaint.setColor(tint.getDefaultColor());
        }
        invalidate();
    }

    private void animateMorph(float target) {
        if (mMorphAnimator != null) {
            mMorphAnimator.cancel();
        }
        mMorphAnimator = ValueAnimator.ofFloat(mMorphProgress, target);
        mMorphAnimator.setDuration(600);
        mMorphAnimator.setInterpolator(new DecelerateInterpolator());
        mMorphAnimator.addUpdateListener(animation -> {
            mMorphProgress = (float) animation.getAnimatedValue();
            invalidate();
            invalidateOutline();
        });
        mMorphAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        
        int width = getWidth();
        int height = getHeight();
        float centerX = width / 2f;
        float centerY = height / 2f;
        
        // Use a consistent radius that matches the shadow logic
        float baseRadius = Math.min(width, height) / 2f * 0.88f;

        mPath.reset();
        
        for (int i = 0; i <= 360; i += 1) {
            double angle = Math.toRadians(i);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            
            // Flower component
            float flowerR = baseRadius * (1f + FLOWER_AMPLITUDE * (float) Math.sin(PETALS * (angle + Math.toRadians(mRotationAngle))));
            
            // Squircle component (Superellipse)
            float n = 4.0f; 
            float squareR = baseRadius / (float) Math.pow(Math.pow(Math.abs(cos), n) + Math.pow(Math.abs(sin), n), 1.0/n);

            float r = squareR * (1f - mMorphProgress) + flowerR * mMorphProgress;
            
            float x = centerX + r * cos;
            float y = centerY + r * sin;
            
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        mPath.close();
        
        // Draw the themed background shape
        canvas.drawPath(mPath, mBackgroundPaint);

        // Now let super draw the icon (triangle/pause) correctly centered on top
        super.onDraw(canvas);
    }
}
