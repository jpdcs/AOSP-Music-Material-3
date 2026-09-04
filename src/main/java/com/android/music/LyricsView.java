package com.android.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class LyricsView extends View {
    private final TreeMap<Long, String> mLyricsMap = new TreeMap<>();
    private final List<Long> mTimeList = new ArrayList<>();
    private final List<android.text.StaticLayout> mLayoutsNormal = new ArrayList<>();
    private final List<android.text.StaticLayout> mLayoutsHighlight = new ArrayList<>();
    private int mCurrentLine = -1;
    private TextPaint mTextPaint;
    private TextPaint mHighlightPaint;
    private float mScrollY = 0f;
    private boolean mHasLyrics = false;
    private boolean mIsStatic = false;

    private float[] mLineY;

    private float mLastTouchY;
    private boolean mIsDragging = false;
    private long mLastDragTime = 0;
    private android.view.VelocityTracker mVelocityTracker;
    private float mVelocity = 0;

    public LyricsView(Context context) {
        super(context);
        init();
    }

    public LyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextSize(48f);
        mTextPaint.setColor(Color.argb(160, 255, 255, 255));
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        mHighlightPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mHighlightPaint.setTextSize(56f);
        mHighlightPaint.setColor(Color.WHITE);
        mHighlightPaint.setFakeBoldText(true);
        mHighlightPaint.setTextAlign(Paint.Align.LEFT);
        
        setClickable(true);
        setFocusable(true);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (!mHasLyrics) return super.onTouchEvent(event);
        
        if (mVelocityTracker == null) {
            mVelocityTracker = android.view.VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        float y = event.getY();
        switch (event.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mLastTouchY = y;
                mIsDragging = false;
                mVelocity = 0;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                float deltaY = mLastTouchY - y;
                if (Math.abs(deltaY) > 2 || mIsDragging) {
                    mIsDragging = true;
                    
                    if (mIsStatic && mLineY != null && mLineY.length > 0) {
                        android.text.StaticLayout lastLayout = mLayoutsNormal.get(mLayoutsNormal.size() - 1);
                        float totalHeight = mLineY[mLineY.length - 1] + lastLayout.getHeight();
                        float limit = Math.max(0, totalHeight - getHeight() + 160);
                        
                        // Resistance when pulling past boundaries
                        if ((mScrollY < 0 && deltaY < 0) || (mScrollY > limit && deltaY > 0)) {
                            deltaY *= 0.5f; 
                        }
                    }
                    
                    mScrollY += deltaY;
                    mLastDragTime = android.os.SystemClock.elapsedRealtime();
                    invalidate();
                }
                mLastTouchY = y;
                break;

            case android.view.MotionEvent.ACTION_UP:
                mVelocityTracker.computeCurrentVelocity(1000);
                mVelocity = -mVelocityTracker.getYVelocity();
                mIsDragging = false;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                invalidate();
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    public void setLyricColors(int highlightColor, int normalColor) {
        mHighlightPaint.setColor(highlightColor);
        mTextPaint.setColor(normalColor);
        rebuildLayouts();
        invalidate();
    }

    public void setLyrics(String lyrics) {
        mLyricsMap.clear();
        mTimeList.clear();
        mIsStatic = false;
        mHasLyrics = lyrics != null && !lyrics.trim().isEmpty();
        if (mHasLyrics && lyrics != null) {
            parseLrc(lyrics);
        }
        mTimeList.addAll(mLyricsMap.keySet());
        Collections.sort(mTimeList);
        mCurrentLine = -1;
        mScrollY = 0f;
        mVelocity = 0;
        mLastDragTime = 0;
        rebuildLayouts();
        invalidate();
    }

    private void rebuildLayouts() {
        mLayoutsNormal.clear();
        mLayoutsHighlight.clear();
        if (!mHasLyrics || getWidth() <= 0) return;
        
        int maxWidth = getWidth() - 80;
        for (int i = 0; i < mTimeList.size(); i++) {
            String text = mLyricsMap.get(mTimeList.get(i));
            if (text == null) text = "";
            
            mLayoutsNormal.add(new android.text.StaticLayout(text, mTextPaint, maxWidth, 
                    android.text.Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false));
            mLayoutsHighlight.add(new android.text.StaticLayout(text, mHighlightPaint, maxWidth, 
                    android.text.Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false));
        }
        updateLinePositions();
    }

    private void updateLinePositions() {
        int count = mTimeList.size();
        if (mLineY == null || mLineY.length != count) {
            mLineY = new float[count];
        }
        float currentY = 0;
        for (int i = 0; i < count; i++) {
            mLineY[i] = currentY;
            android.text.StaticLayout layout = (i == mCurrentLine && !mIsStatic) 
                    ? mLayoutsHighlight.get(i) : mLayoutsNormal.get(i);
            currentY += layout.getHeight() + 40;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildLayouts();
    }

    private void parseLrc(String lrc) {
        String[] lines = lrc.split("\\r?\\n");
        boolean timed = false;
        long offset = 0;
        
        android.util.Log.d("Lyrics", "Parsing LRC, lines: " + lines.length);

        // Pattern for time tags: [mm:ss.xx] or [mm:ss:xx] or [mm:ss]
        java.util.regex.Pattern timePattern = java.util.regex.Pattern.compile("\\[(\\d+:\\d{2}(?:[.:]\\d+)?)]");
        java.util.regex.Pattern offsetPattern = java.util.regex.Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]");

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;

            // Check for offset
            java.util.regex.Matcher offsetMatcher = offsetPattern.matcher(trimmedLine);
            if (offsetMatcher.find()) {
                String offsetVal = offsetMatcher.group(1);
                if (offsetVal != null) {
                    try {
                        offset = Long.parseLong(offsetVal);
                        android.util.Log.d("Lyrics", "Found offset: " + offset);
                    } catch (NumberFormatException e) {}
                }
                continue;
            }

            java.util.regex.Matcher timeMatcher = timePattern.matcher(trimmedLine);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1);
                if (timeStr != null) {
                    long time = parseTime(timeStr);
                    if (time >= 0) {
                        times.add(time);
                    }
                }
                lastEnd = timeMatcher.end();
            }

            if (!times.isEmpty()) {
                String lyricsPart = trimmedLine.substring(lastEnd).trim();
                for (long t : times) {
                    mLyricsMap.put(t + offset, lyricsPart);
                }
                timed = true;
            }
        }

        android.util.Log.d("Lyrics", "Parsed timed lines: " + mLyricsMap.size());

        if (!timed && !lrc.isEmpty()) {
            mIsStatic = true;
            // Static lyrics fallback
            int lineCount = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("[")) {
                    mLyricsMap.put((long) lineCount * 2000, trimmed);
                    lineCount++;
                }
            }
            android.util.Log.d("Lyrics", "Falling back to static, lines: " + mLyricsMap.size());
        }
    }


    private long parseTime(String time) {
        try {
            int lastColon = time.lastIndexOf(':');
            if (lastColon < 0) return -1;
            
            long min = Long.parseLong(time.substring(0, lastColon));
            String secPart = time.substring(lastColon + 1).replace(':', '.');
            float sec = Float.parseFloat(secPart);
            return (long) (min * 60000 + sec * 1000);
        } catch (Exception e) {}
        return -1;
    }

    public void updateTime(long time) {
        if (mIsStatic) return;
        int newLine = -1;
        for (int i = 0; i < mTimeList.size(); i++) {
            if (time >= mTimeList.get(i)) {
                newLine = i;
            } else {
                break;
            }
        }
        if (newLine != mCurrentLine) {
            mCurrentLine = newLine;
            // Update line positions to reflect highlight height change
            updateLinePositions();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mHasLyrics || mTimeList.isEmpty() || mLayoutsNormal.size() != mTimeList.size()) {
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Lyrics not available", getWidth() / 2f, getHeight() / 2f, mTextPaint);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        
        long now = android.os.SystemClock.elapsedRealtime();
        boolean isUserScrolling = mIsDragging || (now - mLastDragTime < 3000) || Math.abs(mVelocity) > 0.1f;

        // Apply Velocity (Fling)
        if (!mIsDragging && Math.abs(mVelocity) > 0.1f) {
            mScrollY += mVelocity * 0.016f; // Approx 60fps
            mVelocity *= 0.96f; // Friction
            postInvalidateOnAnimation();
        }

        // Content boundary calculation
        android.text.StaticLayout lastLayout = (mTimeList.size() - 1 == mCurrentLine && !mIsStatic) 
                ? mLayoutsHighlight.get(mLayoutsHighlight.size() - 1) 
                : mLayoutsNormal.get(mLayoutsNormal.size() - 1);
        float totalHeight = mLineY[mLineY.length - 1] + lastLayout.getHeight();
        float viewHeight = getHeight();
        float staticLimit = Math.max(0, totalHeight - viewHeight + 160);

        if (!mIsStatic && !isUserScrolling) {
            float targetY = 0;
            if (mCurrentLine >= 0 && mCurrentLine < mLineY.length) {
                android.text.StaticLayout currentLayout = mLayoutsHighlight.get(mCurrentLine);
                targetY = mLineY[mCurrentLine] + currentLayout.getHeight() / 2f;
            }
            if (Math.abs(targetY - mScrollY) > 0.1f) {
                mScrollY = mScrollY + (targetY - mScrollY) * 0.1f;
                postInvalidateOnAnimation();
            }
        } else if (!mIsDragging) {
            // Smooth bounce back
            float targetScroll = mScrollY;
            if (mIsStatic) {
                if (mScrollY < 0) targetScroll = 0;
                else if (mScrollY > staticLimit) targetScroll = staticLimit;
            }
            
            if (Math.abs(targetScroll - mScrollY) > 0.1f) {
                mScrollY = mScrollY + (targetScroll - mScrollY) * 0.2f;
                mVelocity = 0; // Stop velocity during bounce back
                postInvalidateOnAnimation();
            }
        }

        canvas.save();
        float drawOriginY = mIsStatic ? 80 : centerY;
        canvas.translate(0, drawOriginY - mScrollY);

        for (int i = 0; i < mTimeList.size(); i++) {
            android.text.StaticLayout layout = (i == mCurrentLine && !mIsStatic) 
                    ? mLayoutsHighlight.get(i) : mLayoutsNormal.get(i);
            float layoutHeight = layout.getHeight();
            float currentY = mLineY[i];
            
            float absoluteY = drawOriginY - mScrollY + currentY;
            if (absoluteY + layoutHeight > 0 && absoluteY < viewHeight) {
                canvas.save();
                canvas.translate(centerX - layout.getWidth() / 2f, currentY);
                layout.draw(canvas);
                canvas.restore();
            }
        }
        canvas.restore();
    }



}
