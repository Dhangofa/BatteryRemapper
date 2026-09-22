package com.github.dhangofa.batteryremapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A two-thumb slider describing a window, used for the mapping range so the lower and the upper
 * bound are one control instead of two independent {@code SeekBar}s.
 *
 * <p>The window is defined over an integer domain ({@link #setBounds(int, int)}, the battery
 * percentage here) and always keeps {@link #setMinimumSeparation(int)} between the two thumbs:
 * dragging one thumb onto the other stops it rather than pushing it along, which is what a range
 * control is expected to do.
 *
 * <p>Programmatic updates go through {@link #setValues(int, int)} and never call the listener, so
 * the owner can restore state without echoing a change back to itself.
 *
 * <p>Hand-drawn on purpose: the app carries no Material Components dependency, and this keeps the
 * brand colours and the flat card look the rest of the screen is built from.
 */
public class RangeSlider extends View {

    /** Notified while the user drags, and once more when they let go. */
    public interface OnRangeChangedListener {

        void onRangeChanged(RangeSlider slider, int from, int to);

        void onRangeChangeFinished(RangeSlider slider, int from, int to);
    }

    private static final int THUMB_FROM = 0;
    private static final int THUMB_TO = 1;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    /** Colours are kept raw, because the enabled state is applied as an alpha per draw. */
    private final int trackColor;
    private final int windowColor;
    private final int thumbColor;
    private final int ringColor;

    private final float thumbRadius;
    private final float trackHeight;
    private final float ringWidth;
    private final float verticalInset;

    private int boundFrom = 0;
    private int boundTo = 100;
    private int valueFrom = 0;
    private int valueTo = 100;
    private int minSeparation = 1;

    private int activeThumb = THUMB_FROM;
    private boolean dragging = false;

    private OnRangeChangedListener listener;

    public RangeSlider(Context context) {
        this(context, null);
    }

    public RangeSlider(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RangeSlider(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;

        thumbRadius = 11f * density;
        trackHeight = 6f * density;
        ringWidth = 2f * density;
        verticalInset = 10f * density;

        trackColor = context.getColor(R.color.brand_sand);
        windowColor = context.getColor(R.color.brand_olive);
        thumbColor = context.getColor(R.color.brand_on_container);
        ringColor = context.getColor(R.color.card_surface);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidth);

        setFocusable(true);
        setClickable(true);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Sets the domain both thumbs live in and clamps the current window into it.
     *
     * <p>Deliberately silent: it is used while the screen is being populated, before the user has
     * touched anything.
     */
    public void setBounds(int min, int max) {
        if (max <= min) {
            return;
        }

        boundFrom = min;
        boundTo = max;

        setValues(valueFrom, valueTo);
    }

    /** Keeps at least this much room between the two thumbs. */
    public void setMinimumSeparation(int separation) {
        minSeparation = Math.max(1, Math.min(separation, boundTo - boundFrom));

        setValues(valueFrom, valueTo);
    }

    /** Moves both thumbs without notifying the listener. */
    public void setValues(int from, int to) {
        int low = Math.max(boundFrom, Math.min(from, boundTo));
        int high = Math.max(boundFrom, Math.min(to, boundTo));

        if (high - low < minSeparation) {
            high = Math.min(boundTo, low + minSeparation);

            if (high - low < minSeparation) {
                low = Math.max(boundFrom, high - minSeparation);
            }
        }

        valueFrom = low;
        valueTo = high;

        activeThumb = THUMB_FROM;

        updateContentDescription();
        invalidate();
    }

    public int getValueFrom() {
        return valueFrom;
    }

    public int getValueTo() {
        return valueTo;
    }

    public void setOnRangeChangedListener(OnRangeChangedListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = Math.round(2f * (thumbRadius + verticalInset))
                + getPaddingTop()
                + getPaddingBottom();

        setMeasuredDimension(
                resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left = trackLeft();
        float right = trackRight();

        if (right <= left) {
            return;
        }

        int alpha = isEnabled() ? 255 : 120;

        float centerY = getHeight() / 2f;
        float halfTrack = trackHeight / 2f;
        float top = centerY - halfTrack;
        float bottom = centerY + halfTrack;

        // The whole domain, then the part of it the user selected.
        trackPaint.setColor(withAlpha(trackColor, alpha));
        trackRect.set(left, top, right, bottom);
        canvas.drawRoundRect(trackRect, halfTrack, halfTrack, trackPaint);

        float fromX = xForValue(valueFrom);
        float toX = xForValue(valueTo);

        windowPaint.setColor(withAlpha(windowColor, alpha));
        trackRect.set(
                Math.min(fromX, toX),
                top,
                Math.max(Math.min(fromX, toX) + trackHeight, Math.max(fromX, toX)),
                bottom
        );
        canvas.drawRoundRect(trackRect, halfTrack, halfTrack, windowPaint);

        paintThumb(canvas, fromX, centerY, alpha);
        paintThumb(canvas, toX, centerY, alpha);
    }

    private void paintThumb(Canvas canvas, float x, float centerY, int alpha) {
        thumbPaint.setColor(withAlpha(thumbColor, alpha));
        canvas.drawCircle(x, centerY, thumbRadius, thumbPaint);

        // A ring in the card colour lifts the thumb off the track without a shadow.
        ringPaint.setColor(withAlpha(ringColor, alpha));
        canvas.drawCircle(x, centerY, thumbRadius - ringWidth / 2f, ringPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private float trackLeft() {
        return getPaddingLeft() + thumbRadius;
    }

    private float trackRight() {
        return getWidth() - getPaddingRight() - thumbRadius;
    }

    private boolean isRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    private float xForValue(int value) {
        float left = trackLeft();
        float right = trackRight();
        int span = boundTo - boundFrom;

        if (right <= left || span <= 0) {
            return left;
        }

        float fraction = (value - boundFrom) / (float) span;
        fraction = Math.max(0f, Math.min(1f, fraction));

        if (isRtl()) {
            fraction = 1f - fraction;
        }

        return left + fraction * (right - left);
    }

    private int valueForX(float x) {
        float left = trackLeft();
        float right = trackRight();

        if (right <= left) {
            return boundFrom;
        }

        float fraction = (x - left) / (right - left);
        fraction = Math.max(0f, Math.min(1f, fraction));

        if (isRtl()) {
            fraction = 1f - fraction;
        }

        return Math.round(boundFrom + fraction * (boundTo - boundFrom));
    }

    private int nearestThumb(float x) {
        float toFrom = Math.abs(x - xForValue(valueFrom));
        float toTo = Math.abs(x - xForValue(valueTo));

        return toFrom <= toTo ? THUMB_FROM : THUMB_TO;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeThumb = nearestThumb(event.getX());
                dragging = true;
                disallowParentIntercept(true);
                dragTo(event.getX());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }

                dragTo(event.getX());
                return true;

            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    return false;
                }

                dragTo(event.getX());
                dragging = false;
                disallowParentIntercept(false);
                performClick();
                notifyFinished();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return false;
                }

                dragging = false;
                disallowParentIntercept(false);
                notifyFinished();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return nudgeActiveThumb(-1);

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
                return nudgeActiveThumb(1);

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Moves the focused thumb one step; the leading thumb when neither was used yet. */
    private boolean nudgeActiveThumb(int delta) {
        int previousFrom = valueFrom;
        int previousTo = valueTo;

        if (activeThumb == THUMB_FROM) {
            valueFrom = Math.max(boundFrom, Math.min(valueFrom + delta, valueTo - minSeparation));
        } else {
            valueTo = Math.min(boundTo, Math.max(valueTo + delta, valueFrom + minSeparation));
        }

        if (valueFrom == previousFrom && valueTo == previousTo) {
            return true;
        }

        updateContentDescription();
        invalidate();
        notifyChanged();
        notifyFinished();

        return true;
    }

    private void dragTo(float x) {
        int wanted = valueForX(x);
        int previousFrom = valueFrom;
        int previousTo = valueTo;

        if (activeThumb == THUMB_FROM) {
            /*
             * Stopping at the other thumb rather than pushing it: a single control has no visible
             * active bound, so a window that moves as a whole would be surprising.
             */
            valueFrom = Math.max(boundFrom, Math.min(wanted, valueTo - minSeparation));
        } else {
            valueTo = Math.min(boundTo, Math.max(wanted, valueFrom + minSeparation));
        }

        if (valueFrom == previousFrom && valueTo == previousTo) {
            return;
        }

        updateContentDescription();
        invalidate();
        notifyChanged();
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onRangeChanged(this, valueFrom, valueTo);
        }
    }

    private void notifyFinished() {
        if (listener != null) {
            listener.onRangeChangeFinished(this, valueFrom, valueTo);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------
    // Accessibility
    // ------------------------------------------------------------------

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        // Announced as a slider; both bounds travel in the content description, which is
        // refreshed on every change.
        info.setClassName("android.widget.SeekBar");
    }

    private void updateContentDescription() {
        setContentDescription(
                getContext().getString(
                        R.string.map_range_content_description,
                        valueFrom,
                        valueTo
                )
        );
    }
}
