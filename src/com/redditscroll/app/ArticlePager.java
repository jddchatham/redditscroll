package com.redditscroll.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * Minimal vertical snap pager: each child fills the viewport exactly,
 * dragging scrolls, releasing snaps to the nearest page (fling-aware).
 * No external dependencies.
 */
public class ArticlePager extends ViewGroup {

    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    private final Scroller scroller;
    private VelocityTracker velocity;
    private final int touchSlop;
    private final int minFling;

    private float downY;
    private float lastY;
    private boolean dragging;

    private int currentPage;
    private int lastNotifiedPage = -1;
    private OnPageChangeListener listener;

    public ArticlePager(Context context) {
        super(context);
        scroller = new Scroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        listener = l;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childW, childH);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (h <= 0) return;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(0, i * h, w, i * h + h);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h != oldh && h > 0) {
            scroller.forceFinished(true);
            scrollTo(0, currentPage * h);
            requestLayout();
        }
    }

    @Override
    public void scrollTo(int x, int y) {
        int max = Math.max(0, getChildCount() * getHeight() - getHeight());
        super.scrollTo(0, Math.max(0, Math.min(y, max)));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downY = lastY = ev.getY();
                dragging = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                else velocity.clear();
                velocity.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(ev.getY() - downY) > touchSlop) {
                    dragging = true;
                    lastY = ev.getY();
                }
                if (dragging) {
                    velocity.addMovement(ev);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downY = lastY = ev.getY();
                velocity.clear();
                velocity.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                velocity.addMovement(ev);
                float y = ev.getY();
                if (!dragging && Math.abs(y - downY) > touchSlop) {
                    dragging = true;
                    lastY = y;
                }
                if (dragging) {
                    scrollBy(0, (int) (lastY - y));
                    lastY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity();
                    int page = nearestPage();
                    if (vy < -minFling) {
                        page = currentPage + 1;
                    } else if (vy > minFling) {
                        page = currentPage - 1;
                    }
                    setPage(page, true);
                    dragging = false;
                }
                recycleVelocity();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) setPage(nearestPage(), true);
                dragging = false;
                recycleVelocity();
                return true;
            default:
                return true;
        }
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private int nearestPage() {
        int h = getHeight();
        if (h <= 0 || getChildCount() == 0) return 0;
        return clamp((int) Math.round(getScrollY() / (double) h));
    }

    private int clamp(int page) {
        int last = getChildCount() - 1;
        if (last < 0) return 0;
        return Math.max(0, Math.min(page, last));
    }

    /** Settle on a page. */
    public void setPage(int page, boolean smooth) {
        currentPage = clamp(page);
        int targetY = currentPage * getHeight();
        scroller.forceFinished(true);
        scroller.startScroll(0, getScrollY(), 0, targetY - getScrollY(), smooth ? 320 : 0);
        postInvalidate();
        notifyIfChanged();
    }

    private void notifyIfChanged() {
        if (listener != null && currentPage != lastNotifiedPage) {
            lastNotifiedPage = currentPage;
            listener.onPageChanged(currentPage);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.getCurrY());
            postInvalidate();
        }
    }
}
