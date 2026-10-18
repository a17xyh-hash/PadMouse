package com.padmouse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手柄鼠标。
 *
 * 两条输入通道：
 *   1) 无障碍（免 root）：onKeyEvent 过滤手柄按键；Android 14+ 还能用 onMotionEvent 读到摇杆。
 *   2) Root：RootPad 直接读 /dev/input/eventN，任何版本都能读摇杆，也不抢焦点。
 *
 * 输出统一走 dispatchGesture（无障碍手势）；万一派发失败且 root 可用，
 * 就退回 `input swipe/tap` 注入，保证还能滚。
 */
public class PadMouseService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static volatile PadMouseService INSTANCE;

    private static final int TICK_MS = 40;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AtomicInteger inflight = new AtomicInteger(0);
    private final Set<Integer> ownedKeys = new HashSet<>();

    private WindowManager wm;
    private CursorOverlay cursor;
    private TextView hintView;

    private RootShell shell;
    private RootPad rootPad;
    private int rootFails;
    private boolean rootReadOk;

    private int sw = 1080, sh = 1920;

    private volatile float lx, ly, rx, ry;
    private long lastMotionAt, lastScrollTick, lastHScrollTick, lastGestureAt;

    // 画圈检测
    private float circleAcc, prevAngle;
    private boolean angleValid;
    private volatile boolean destroyed;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        destroyed = false;
        Dbg.alive = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshScreenSize();
        try {
            cursor = new CursorOverlay(this, wm, sw, sh);
        } catch (Throwable t) {
            cursor = null;
            Dbg.note("光标浮层不可用：" + t);
        }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        applyServiceInfo();
        switchMode();
        refreshOverlays();
        h.removeCallbacks(tick);
        h.post(tick);
        toast("手柄鼠标已就绪");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        destroyed = true;
        Dbg.alive = false;
        INSTANCE = null;
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this);
        } catch (Throwable ignored) {
        }
        h.removeCallbacks(tick);
        h.removeCallbacks(hideHint);
        stopRoot();
        if (cursor != null) {
            cursor.hide();
            cursor = null;
        }
        hideHintNow();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        refreshScreenSize();
        if (cursor != null) cursor.resize(sw, sh);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要窗口内容，事件只用来维持服务存活
    }

    @Override
    public void onInterrupt() {
    }

    private void refreshScreenSize() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                sw = dm.widthPixels;
                sh = dm.heightPixels;
            }
        } catch (Throwable t) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = dm.heightPixels;
        }
    }

    // ------------------------------------------------------------ 服务配置

    private void applyServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Dbg.note("服务配置失败：" + t);
        }

        if (Build.VERSION.SDK_INT < 34) {
            Dbg.note("系统低于 Android 14：无障碍模式读不到摇杆，可用 Root 模式");
            return;
        }
        boolean rootMode = Prefs.mode(this).equals(Prefs.MODE_ROOT);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            if (rootMode || !Prefs.enabled(this)) {
                // 暂停/root 模式下不订阅摇杆，把摇杆还给游戏
                info.setMotionEventSources(0);
            } else {
                try {
                    info.setMotionEventSources(InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD);
                } catch (Throwable t1) {
                    info.setMotionEventSources(InputDevice.SOURCE_JOYSTICK);
                }
            }
            setServiceInfo(info);
            if (rootMode) Dbg.note("Root 模式：不订阅无障碍的摇杆事件");        } catch (Throwable t) {
            Dbg.note("摇杆订阅失败：" + t + "（可改用 Root 模式）");
        }
    }

    // ------------------------------------------------------------ 模式切换

    private void switchMode() {
        boolean root = Prefs.mode(this).equals(Prefs.MODE_ROOT);
        Dbg.modeText = root ? "Root" : "无障碍";
        if (root) startRoot();
        else stopRoot();
        applyServiceInfo();
        refreshOverlays();
    }

    private void startRoot() {
        if (rootPad != null) return;
        rootFails = 0;
        rootReadOk = false;
        Dbg.rootText = "—";
        Dbg.padText = "root: 正在寻找手柄…";
        rootPad = new RootPad(new RootPad.Listener() {
            @Override
            public void onPadKey(int kc, boolean down) {
                handlePadKey(kc, down, 0);
            }

            @Override
            public void onPadAxes(float ax, float ay, float bx, float by) {
                setAxes(ax, ay, bx, by);
            }

            @Override
            public void onPadStatus(int level, String detail) {
                Dbg.padText = (level == 2 ? "⚠ " : "") + detail;
                if (level == 0) {
                    rootFails = 0;
                    rootReadOk = true;
                    return;
                }
                if (level == 1) {
                    rootFails = 0;
                    return;
                }
                Dbg.note(detail);
                rootFails++;
                if (rootFails >= 2 && !rootReadOk) fallbackToAccess("读手柄失败：" + detail);
            }
        });
        rootPad.start();

        if (shell == null) {
            final RootShell s = new RootShell();
            shell = s;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 2; i++) {
                        boolean ok = s.open(30000);      // 给授权弹窗留足时间
                        if (ok) {
                            Dbg.rootText = "注入通道 " + s.suPath();
                            Dbg.note("root 注入通道就绪：" + s.suPath());
                            return;
                        }
                        Dbg.rootText = "授权失败：" + s.error();
                        Dbg.note("root 授权失败：" + s.error());
                        if (i < 1) {
                            if (Prefs.mode(PadMouseService.this).equals(Prefs.MODE_ROOT)) {
                                showHint("等待 root 授权：请在 SukiSU 里点「允许」");
                            }
                            try {
                                Thread.sleep(8000);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (!rootReadOk) fallbackToAccess("root 不可用：" + s.error());
                    else Dbg.note("root 注入不可用，改用无障碍手势派发");
                }
            }, "pm-su-open");
            t.setDaemon(true);
            t.start();
        }
    }

    /** root 读不到/拿不到授权时，自动回到免 root 的无障碍模式，别让手柄直接失灵。 */
    private void fallbackToAccess(String reason) {
        if (!Prefs.mode(this).equals(Prefs.MODE_ROOT)) return;
        Dbg.note(reason + " → 已切回无障碍模式");
        Dbg.rootText = reason;
        Prefs.setS(this, Prefs.K_MODE, Prefs.MODE_ACCESS);
        showHint("Root 不可用，已切回无障碍模式");
    }

    private void stopRoot() {
        if (rootPad != null) {
            rootPad.stop();
            rootPad = null;
        }
        if (shell != null) {
            shell.close();
            shell = null;
        }
        if (Prefs.mode(this).equals(Prefs.MODE_ACCESS)) Dbg.padText = "—";
    }

    // ------------------------------------------------------------ 按键

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e == null) return false;
        if (Prefs.mode(this).equals(Prefs.MODE_ROOT)) return false;   // root 模式走 evdev，避免重复
        return handlePadKey(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN, e.getRepeatCount());
    }

    private static boolean isPadKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return true;
            default:
                return false;
        }
    }

    private boolean handlePadKey(int code, boolean down, int repeat) {
        if (!isPadKey(code)) return false;
        Dbg.keyCount++;
        Dbg.key = KeyNames.of(code) + (down ? " ↓" : " ↑");

        if (!down) {
            return ownedKeys.remove(code);      // 按下时被我们吃了，抬起也吃掉，别漏给应用
        }
        if (!Prefs.enabled(this)) return false;

        boolean handled = act(code, repeat);
        if (handled) ownedKeys.add(code);
        return handled;
    }

    private boolean act(int code, int repeat) {
        float step = Prefs.step(this);
        boolean inv = Prefs.invert(this);
        boolean cursorOn = Prefs.cursorMode(this);
        boolean pageMode = Prefs.DPAD_PAGE.equals(Prefs.dpadMode(this));

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return scrollV(inv ? 1 : -1, step);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return scrollV(inv ? -1 : 1, step);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 翻页：默认"按左就往左滑"，手指方向和按键方向一致
                return pageMode ? page(pageDirFor(-1)) : scrollH(inv ? 1 : -1, step);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return pageMode ? page(pageDirFor(1)) : scrollH(inv ? -1 : 1, step);

            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return scrollV(inv ? 1 : -1, Math.min(0.85f, step * 2.2f));

            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return scrollV(inv ? -1 : 1, Math.min(0.85f, step * 2.2f));

            case KeyEvent.KEYCODE_BUTTON_START:
                if (repeat == 0) {
                    Prefs.setB(this, Prefs.K_CURSOR_ON, !cursorOn);
                    showHint(!cursorOn
                            ? "光标模式：右摇杆移动 · A 点击 · B 返回 · START 退出"
                            : "光标模式已关闭");
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (repeat == 0) {
                    boolean now = !Prefs.enabled(this);
                    Prefs.setB(this, Prefs.K_ENABLED, now);
                    showHint(now ? "已接管手柄" : "已暂停接管（SELECT 恢复）");
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (cursorOn) {
                    if (repeat == 0) tapCursor(70);
                    return true;
                }
                return false;

            case KeyEvent.KEYCODE_BUTTON_B:
                if (cursorOn) {
                    if (repeat == 0) performGlobalAction(GLOBAL_ACTION_BACK);
                    return true;
                }
                return false;

            case KeyEvent.KEYCODE_BUTTON_X:
                if (cursorOn) {
                    if (repeat == 0) tapCursor(700);   // 长按
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    // ------------------------------------------------------------ 摇杆

    @Override
    public void onMotionEvent(MotionEvent e) {
        if (e == null) return;
        if (Prefs.mode(this).equals(Prefs.MODE_ROOT)) return;

        float ax = e.getAxisValue(MotionEvent.AXIS_X);
        float ay = e.getAxisValue(MotionEvent.AXIS_Y);
        float az = e.getAxisValue(MotionEvent.AXIS_Z);
        float arz = e.getAxisValue(MotionEvent.AXIS_RZ);
        float arx = e.getAxisValue(MotionEvent.AXIS_RX);
        float ary = e.getAxisValue(MotionEvent.AXIS_RY);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);

        if (Math.abs(ax) < 0.03f && Math.abs(hx) > 0.4f) ax = hx;
        if (Math.abs(ay) < 0.03f && Math.abs(hy) > 0.4f) ay = hy;
        if (Math.abs(az) < 0.03f && Math.abs(arz) < 0.03f
                && (Math.abs(arx) > 0.03f || Math.abs(ary) > 0.03f)) {
            az = arx;
            arz = ary;
        }
        setAxes(ax, ay, az, arz);
    }

    private void setAxes(float ax, float ay, float bx, float by) {
        lx = clamp(ax);
        ly = clamp(ay);
        rx = clamp(bx);
        ry = clamp(by);
        lastMotionAt = System.currentTimeMillis();
        Dbg.motionCount++;
        Dbg.axis = String.format(Locale.US, "LX %+.2f  LY %+.2f  RX %+.2f  RY %+.2f", lx, ly, rx, ry);
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) return 0;
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float axisDelta(float v, float dz) {
        float a = Math.abs(v);
        if (a <= dz) return 0;
        return (v > 0 ? 1 : -1) * ((a - dz) / (1f - dz));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Throwable t) {
                Dbg.note("tick 异常：" + t);
            }
            if (!destroyed) h.postDelayed(this, TICK_MS);
        }
    };

    private void onTick() {
        if (!Prefs.enabled(this)) {
            circleAcc = 0;
            angleValid = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastMotionAt > 1200) {
            // 手柄断连或一直没事件：归零，避免"自己一直滚"
            lx = ly = rx = ry = 0;
            circleAcc = 0;
            angleValid = false;
        }

        float dz = Prefs.deadzone(this);
        if (Prefs.cursorMode(this)) moveCursor(dz);

        boolean circleMode = Prefs.STICK_CIRCLE.equals(Prefs.stickMode(this));
        if (circleMode) circleScroll(dz);

        if (inflight.get() > 0) {
            // 手势回调偶尔会丢（比如目标应用被冻结），超时兜底，别把整个通道锁死
            if (now - lastGestureAt > 4000) inflight.set(0);
            return;
        }
        if (circleMode) return;      // 画圈模式下不再按"推杆方向"滚

        float a = Math.abs(ly);
        if (a > dz) {
            float k = axisDelta(ly, dz);
            float sp = Math.max(0.3f, Math.min(3f, Prefs.stickSpeed(this)));
            long gap = (long) ((200 - 110 * k) / sp);
            if (now - lastScrollTick >= gap) {
                int dir = ly > 0 ? 1 : -1;
                if (Prefs.invert(this)) dir = -dir;
                swipe(0, dir, Math.min(0.55f, (0.08f + 0.40f * k) * sp) * sh);
                lastScrollTick = now;
            }
            return;
        }

        float hAbs = Math.abs(lx);
        if (hAbs > dz && Prefs.hscroll(this)) {
            float k = axisDelta(lx, dz);
            if (now - lastHScrollTick >= (long) (260 - 120 * k)) {
                int dir = lx > 0 ? 1 : -1;
                if (Prefs.invert(this)) dir = -dir;
                swipe(dir, 0, Math.min(0.45f, 0.08f + 0.32f * k) * sw);
                lastHScrollTick = now;
            }
        }
    }

    /**
     * 摇杆画圈 = 拨轮：顺时针（角度增大）往下滚，逆时针往上滚。
     * 累计转够一格（默认 60°，可被"摇杆速度"整除）才滚一小步，避免抖动误触。
     */
    private void circleScroll(float dz) {
        float mag = (float) Math.hypot(lx, ly);
        if (mag <= dz) {
            angleValid = false;
            circleAcc = 0;
            return;
        }
        float ang = (float) Math.atan2(ly, lx);
        if (!angleValid) {
            prevAngle = ang;
            angleValid = true;
            return;
        }
        float d = ang - prevAngle;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        prevAngle = ang;
        if (Math.abs(d) > 1.2f) return;      // 回中/猛甩造成的跳变，不算转动

        circleAcc += d;
        float perStep = (float) (Math.PI / 3) / Math.max(0.3f, Math.min(3f, Prefs.stickSpeed(this)));
        if (Math.abs(circleAcc) >= perStep && inflight.get() == 0) {
            int dir = circleAcc > 0 ? 1 : -1;
            circleAcc -= dir * perStep;
            if (Prefs.invert(this)) dir = -dir;
            swipe(0, dir, 0.10f * sh);
        }
        float cap = perStep * 4;
        if (circleAcc > cap) circleAcc = cap;
        if (circleAcc < -cap) circleAcc = -cap;
    }

    private void moveCursor(float dz) {        if (cursor == null) return;
        float speed = 1500f * Prefs.cursorSpeed(this) * (TICK_MS / 1000f);
        float dx = axisDelta(rx, dz) * speed;
        float dy = axisDelta(ry, dz) * speed;
        if (dx != 0 || dy != 0) cursor.moveBy(dx, dy);
    }

    private void tapCursor(int ms) {
        if (cursor == null) return;
        int[] p = cursor.pos();
        doSwipe(p[0], p[1], p[0], p[1], ms);
    }

    // ------------------------------------------------------------ 滚动

    private boolean scrollV(final int dir, final float ratio) {
        keyAction(new Runnable() {
            @Override
            public void run() {
                swipe(0, dir, Math.min(0.9f, ratio) * sh);
            }
        });
        return true;
    }

    private boolean scrollH(final int dir, final float ratio) {
        if (!Prefs.hscroll(this)) return false;
        keyAction(new Runnable() {
            @Override
            public void run() {
                swipe(dir, 0, Math.min(0.6f, ratio) * sw);
            }
        });
        return true;
    }

    /**
     * 按键动作排队：手势还在跑的时候按新的键不要直接丢掉，
     * 等上一条手势结束（最多等几百毫秒）再补上，按一下就一定有一次。
     */
    private void keyAction(final Runnable r) {
        if (inflight.get() == 0) {
            r.run();
            return;
        }
        pendingAction = r;
        h.removeCallbacks(flushPending);
        h.postDelayed(flushPending, 60);
    }

    private Runnable pendingAction;
    private final Runnable flushPending = new Runnable() {
        @Override
        public void run() {
            if (pendingAction == null) return;
            if (inflight.get() > 0) {
                h.postDelayed(this, 60);
                return;
            }
            Runnable r = pendingAction;
            pendingAction = null;
            r.run();
        }
    };

    /**
     * 翻页：一条横跨屏幕中部的横划（离边缘远一点，避免触发系统返回手势）。
     *
     * @param fingerDir +1 = 手指往右滑，-1 = 手指往左滑
     */
    private boolean page(final float fingerDir) {
        keyAction(new Runnable() {
            @Override
            public void run() {
                float cx = sw / 2f, cy = sh / 2f;
                float half = sw * 0.36f;      // 0.14W ↔ 0.86W，比之前更宽，翻页更容易被识别
                int ms = Math.max(200, (int) Prefs.swipeMs(PadMouseService.this));
                doSwipe(cx - fingerDir * half, cy, cx + fingerDir * half, cy, ms);
            }
        });
        return true;
    }

    /** 十字键方向 -> 手指滑动方向。 */
    private float pageDirFor(int keyDir) {
        // keyDir: -1 = 左键, +1 = 右键
        if (Prefs.PAGE_DIR_NAV.equals(Prefs.pageDir(this))) {
            // 导航语义：左键 = 上一页（手指往右），右键 = 下一页（手指往左）
            return -keyDir;
        }
        return keyDir;      // 默认：按左就往左滑
    }

    /**
     * @param dx   +1 = 内容往右看（手指往左划）
     * @param dy   +1 = 内容往下看（手指往上划）
     * @param travel 手指移动的总距离（px）
     */
    private void swipe(float dx, float dy, float travel) {
        float cx = sw / 2f, cy = sh / 2f;
        float half = travel / 2f;
        if (dy != 0) {
            float maxHalf = Math.min(cy - sh * 0.10f, sh * 0.90f - cy);
            half = Math.min(half, Math.max(60f, maxHalf));
            doSwipe(cx, cy + dy * half, cx, cy - dy * half, (int) Prefs.swipeMs(this));
        } else if (dx != 0) {
            float maxHalf = Math.min(cx - sw * 0.10f, sw * 0.90f - cx);
            half = Math.min(half, Math.max(60f, maxHalf));
            doSwipe(cx + dx * half, cy, cx - dx * half, cy, (int) Prefs.swipeMs(this));
        }
    }

    private void doSwipe(float x1, float y1, float x2, float y2, int ms) {
        if (inflight.get() > 0) return;
        ms = Math.max(20, ms);

        Path path = new Path();
        path.moveTo(x1, y1);
        if (Math.abs(x2 - x1) > 0.5f || Math.abs(y2 - y1) > 0.5f) path.lineTo(x2, y2);

        GestureDescription gd = null;
        try {
            gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, ms))
                    .build();
        } catch (Throwable t) {
            Dbg.gesture = "手势参数非法：" + t;
        }

        if (gd != null) {
            inflight.incrementAndGet();
            lastGestureAt = System.currentTimeMillis();
            boolean ok = false;
            try {
                ok = dispatchGesture(gd, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription d) {
                        inflight.decrementAndGet();
                        Dbg.gesture = "✔ 已滑动";
                    }

                    @Override
                    public void onCancelled(GestureDescription d) {
                        inflight.decrementAndGet();
                        Dbg.gesture = "✖ 手势被系统取消";
                    }
                }, h);
            } catch (Throwable t) {
                Dbg.gesture = "dispatchGesture 异常：" + t;
            }
            if (ok) return;
            inflight.decrementAndGet();
        }

        // 手势派发失败，试 root 的 input
        if (shell != null && shell.isReady()) {
            String cmd;
            if (Math.abs(x2 - x1) < 2 && Math.abs(y2 - y1) < 2) {
                cmd = ms < 300
                        ? String.format(Locale.US, "input tap %d %d", (int) x1, (int) y1)
                        : String.format(Locale.US, "input swipe %d %d %d %d %d",
                        (int) x1, (int) y1, (int) x1, (int) y1, ms);
            } else {
                cmd = String.format(Locale.US, "input swipe %d %d %d %d %d",
                        (int) x1, (int) y1, (int) x2, (int) y2, ms);
            }
            if (!shell.run(cmd)) Dbg.gesture = "注入忙，本次跳过";
        } else {
            Dbg.gesture = "没有可用的注入通道（开无障碍或 root）";
        }
    }

    /** 给界面的"试一下"按钮用。 */
    public void testScroll() {
        if (!Prefs.enabled(this)) Prefs.setB(this, Prefs.K_ENABLED, true);
        swipe(0, 1, Math.min(0.9f, Prefs.step(this)) * sh);
        showHint("测试：向下滚 " + Math.round(Prefs.step(this) * 100) + "% 屏");
    }

    // ------------------------------------------------------------ 浮层

    private void refreshOverlays() {
        boolean cm = Prefs.cursorMode(this) && Prefs.enabled(this);
        if (cursor != null) {
            if (cm) cursor.show();
            else cursor.hide();
        }
    }

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            hideHintNow();
        }
    };

    private void hideHintNow() {
        if (hintView != null && hintView.getParent() != null) {
            try {
                wm.removeView(hintView);
            } catch (Throwable ignored) {
            }
        }
    }

    private void showHint(final String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    showHint(s);
                }
            });
            return;
        }
        if (!Prefs.b(this, Prefs.K_HINT, true) || wm == null) return;
        try {
            if (hintView == null) {
                hintView = new TextView(this);
                hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                hintView.setTextColor(0xFFFFFFFF);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xE61B2330);
                g.setCornerRadius(dp(14));
                g.setStroke(dp(1), 0x40FFFFFF);
                hintView.setBackground(g);
                hintView.setPadding(dp(12), dp(7), dp(12), dp(7));
            }
            hintView.setText(s);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(96);
            if (hintView.getParent() == null) wm.addView(hintView, lp);
            else wm.updateViewLayout(hintView, lp);
            h.removeCallbacks(hideHint);
            h.postDelayed(hideHint, 2400);
        } catch (Throwable t) {
            // 浮层失败不影响滚动
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 配置变化

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, final String key) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (Prefs.K_MODE.equals(key)) {
                    switchMode();
                    toast(Prefs.mode(PadMouseService.this).equals(Prefs.MODE_ROOT)
                            ? "已切到 Root 模式" : "已切到无障碍模式");
                } else if (Prefs.K_CURSOR_ON.equals(key)) {
                    refreshOverlays();
                } else if (Prefs.K_ENABLED.equals(key)) {
                    applyServiceInfo();     // 暂停时把摇杆还给游戏
                    refreshOverlays();
                    showHint(Prefs.enabled(PadMouseService.this) ? "已接管手柄" : "已暂停接管");
                }
            }
        });
    }
}
