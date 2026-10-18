package com.padmouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 光标模式用的那个小圆点（悬浮在所有应用上面，只负责画）。 */
final class CursorOverlay {

    private final WindowManager wm;
    private final View dot;
    private final WindowManager.LayoutParams lp;
    private final int size;

    private int sw, sh;
    private float x, y;
    private boolean shown;

    CursorOverlay(Context ctx, WindowManager wm, int screenW, int screenH) {
        this.wm = wm;
        this.sw = screenW;
        this.sh = screenH;
        this.size = Math.round(30 * ctx.getResources().getDisplayMetrics().density);
        this.x = sw / 2f;
        this.y = sh / 2f;

        dot = new Dot(ctx);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        applyPosition();
    }

    void show() {
        if (shown) return;
        try {
            wm.addView(dot, lp);
            shown = true;
        } catch (Throwable ignored) {
        }
    }

    void hide() {
        if (!shown) return;
        try {
            wm.removeView(dot);
        } catch (Throwable ignored) {
        }
        shown = false;
    }

    boolean isShown() {
        return shown;
    }

    void resize(int w, int h) {
        sw = w;
        sh = h;
        x = Math.max(0, Math.min(x, sw));
        y = Math.max(0, Math.min(y, sh));
        applyPosition();
        if (shown) {
            try {
                wm.updateViewLayout(dot, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    void moveBy(float dx, float dy) {
        x = Math.max(0, Math.min(sw, x + dx));
        y = Math.max(0, Math.min(sh, y + dy));
        applyPosition();
        if (shown) {
            try {
                wm.updateViewLayout(dot, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    int[] pos() {
        return new int[]{(int) x, (int) y};
    }

    private void applyPosition() {
        lp.x = Math.round(x - size / 2f);
        lp.y = Math.round(y - size / 2f);
    }

    /** 一个带描边的圆点，带十字准心，方便在浅色页面上也能看见。 */
    private final class Dot extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dot(Context c) {
            super(c);
            fill.setColor(0xCC3B82F6);
            fill.setStyle(Paint.Style.FILL);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(size * 0.075f);
            cross.setColor(0xFFFFFFFF);
            cross.setStrokeWidth(size * 0.06f);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - ring.getStrokeWidth() / 2f - 1;
            cv.drawCircle(cx, cy, r, fill);
            cv.drawCircle(cx, cy, r, ring);
            float arm = r * 0.42f;
            cv.drawLine(cx - arm, cy, cx + arm, cy, cross);
            cv.drawLine(cx, cy - arm, cx, cy + arm, cross);
            // 一个小亮点，让中心位置更明确
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            cv.drawCircle(cx, cy, r * 0.16f, p);
        }
    }
}
