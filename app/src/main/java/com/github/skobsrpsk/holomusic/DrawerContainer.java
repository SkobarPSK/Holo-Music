package com.github.skobsrpsk.holomusic;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Простая замена androidx.drawerlayout.widget.DrawerLayout — своя реализация
 * без сторонних библиотек. Держит три слоя: контент, полупрозрачный scrim
 * (виден и кликабелен только когда меню открыто) и панель меню слева.
 * Поддерживает открытие/закрытие жестом от левого края экрана и обычным
 * перетаскиванием уже открытой панели, плюс программные open()/close()/toggle().
 */
public class DrawerContainer extends FrameLayout {

    private static final int EDGE_SIZE_DP = 24;
    private static final int ANIM_DURATION_MS = 220;

    private View content;
    private View drawer;
    private View scrim;

    private int drawerWidth;
    private final int edgeSizePx;
    private final int touchSlop;
    private VelocityTracker velocityTracker;

    private boolean isOpen = false;
    private boolean dragging = false;
    private float downX, downY;
    private float lastDrawerX; // текущее смещение панели во время перетаскивания (0 = закрыта, drawerWidth = открыта)

    public interface DrawerListener {
        void onDrawerOpened();
        void onDrawerClosed();
    }

    private DrawerListener drawerListener;

    public DrawerContainer(Context context) {
        this(context, null);
    }

    public DrawerContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        edgeSizePx = (int) (EDGE_SIZE_DP * getResources().getDisplayMetrics().density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    public void setDrawerListener(DrawerListener listener) {
        this.drawerListener = listener;
    }

    /**
     * Должно быть вызвано после addView(): первый добавленный child — контент,
     * второй — панель меню. Порядок задаётся в layout XML.
     */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() < 2) {
            throw new IllegalStateException("DrawerContainer требует ровно два дочерних view: контент и панель меню");
        }
        content = getChildAt(0);
        drawer = getChildAt(1);

        // Scrim добавляем сами программно между контентом и панелью.
        scrim = new View(getContext());
        scrim.setBackgroundColor(Color.argb(153, 0, 0, 0));
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        removeView(drawer);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(drawer);

        drawer.setTranslationX(-9999); // спрячем до первого onSizeChanged, чтобы не мелькнуло
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        drawerWidth = drawer.getLayoutParams().width > 0 && drawer.getLayoutParams().width != LayoutParams.MATCH_PARENT
                ? drawer.getLayoutParams().width
                : (int) (w * 0.8f);
        ViewGroup.LayoutParams lp = drawer.getLayoutParams();
        lp.width = drawerWidth;
        drawer.setLayoutParams(lp);
        setDrawerTranslation(isOpen ? 0 : -drawerWidth);
    }

    // ---------- Публичное API ----------

    public void open() {
        animateTo(0);
    }

    public void close() {
        animateTo(-drawerWidth);
    }

    public void toggle() {
        if (isOpen) close(); else open();
    }

    public boolean isDrawerOpen() {
        return isOpen;
    }

    // ---------- Touch handling ----------

    private boolean dragCandidate = false; // этот жест МОЖЕТ стать перетаскиванием (начался у края или меню уже открыто)

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            dragging = false;
            lastDrawerX = isOpen ? 0 : -drawerWidth;
            dragCandidate = isOpen || downX <= edgeSizePx;
            // Никогда не перехватываем на ACTION_DOWN — иначе дочерние view
            // (пункты списка меню, кнопки) вообще не получают тап. Перехват
            // случится позже, только если палец реально сдвинулся по
            // горизонтали (см. ACTION_MOVE ниже) — так обычный клик спокойно
            // доходит до ListView, а жест-свайп всё равно ловится.
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE && dragCandidate) {
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                dragging = true;
                return true;
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragCandidate = false;
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                lastDrawerX = isOpen ? 0 : -drawerWidth;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float newTranslation = clamp(lastDrawerX + dx, -drawerWidth, 0);
                setDrawerTranslation(newTranslation);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                velocityTracker.computeCurrentVelocity(1000);
                float velocity = velocityTracker.getXVelocity();
                float currentTranslation = drawer.getTranslationX();

                boolean shouldOpen;
                if (Math.abs(velocity) > 600) {
                    shouldOpen = velocity > 0;
                } else {
                    shouldOpen = currentTranslation > -drawerWidth / 2f;
                }

                if (shouldOpen) open(); else close();

                velocityTracker.recycle();
                velocityTracker = null;
                dragging = false;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void setDrawerTranslation(float translationX) {
        drawer.setTranslationX(translationX);
        float progress = 1f - (Math.abs(translationX) / (float) drawerWidth); // 0..1
        scrim.setVisibility(progress > 0f ? View.VISIBLE : View.GONE);
        scrim.setAlpha(progress);
    }

    private android.animation.ValueAnimator currentAnimator;

    private void animateTo(final float target) {
        final float start = drawer.getTranslationX();
        if (currentAnimator != null) currentAnimator.cancel();

        // Обычный ValueAnimator вместо ViewPropertyAnimator.setUpdateListener() —
        // последний появился только в API 19, на API 16-18 упал бы с
        // NoSuchMethodError. addUpdateListener() у ValueAnimator есть с API 11.
        currentAnimator = android.animation.ValueAnimator.ofFloat(start, target);
        currentAnimator.setDuration(ANIM_DURATION_MS);
        currentAnimator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                float current = (Float) animation.getAnimatedValue();
                setDrawerTranslation(current);
            }
        });
        currentAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                boolean nowOpen = target == 0;
                if (nowOpen != isOpen) {
                    isOpen = nowOpen;
                    if (drawerListener != null) {
                        if (isOpen) drawerListener.onDrawerOpened();
                        else drawerListener.onDrawerClosed();
                    }
                }
                scrim.setVisibility(isOpen ? View.VISIBLE : View.GONE);
            }
        });
        currentAnimator.start();
    }

    /** Перехватывает системную кнопку "назад" — вызывающий код должен это проверить. */
    public boolean handleBackPress() {
        if (isOpen) {
            close();
            return true;
        }
        return false;
    }
}
