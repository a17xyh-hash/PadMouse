package com.padmouse;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有可调参数集中在这里，服务端与界面共用。 */
public final class Prefs {

    public static final String NAME = "padmouse";

    /** 运行模式：access = 无障碍（免 root），root = 直接读 /dev/input */
    public static final String K_MODE = "mode";
    public static final String MODE_ACCESS = "access";
    public static final String MODE_ROOT = "root";

    public static final String K_ENABLED   = "enabled";      // 总开关
    public static final String K_INVERT    = "invert";       // 反向滚动
    public static final String K_HS        = "hscroll";      // 十字键左右横滚
    public static final String K_STEP      = "step";         // 一步 = 屏幕高度的比例
    public static final String K_SWIPE     = "swipe_ms";     // 单次滑动时长(ms)
    public static final String K_DEADZONE  = "deadzone";     // 摇杆死区
    public static final String K_STICK     = "stick_speed";  // 摇杆滚屏速度倍率
    public static final String K_CURSOR    = "cursor_speed"; // 光标移动速度
    public static final String K_CURSOR_ON = "cursor_on";    // 光标模式(当鼠标)
    public static final String K_HINT      = "hint";         // 操作提示浮条
    public static final String K_DPAD_MODE = "dpad_mode";    // 十字键：page 翻页 / scroll 滚动
    public static final String K_PAGE_DIR  = "page_dir";     // 翻页方向：dir 跟着按键 / nav 上一页-下一页
    public static final String K_STICK_MODE = "stick_mode";  // 左摇杆：circle 画圈 / push 推杆

    public static final String DPAD_PAGE = "page";
    public static final String DPAD_SCROLL = "scroll";
    public static final String PAGE_DIR_MATCH = "dir";       // ← = 手指往左滑
    public static final String PAGE_DIR_NAV = "nav";         // ← = 上一页（手指往右滑）
    public static final String STICK_CIRCLE = "circle";
    public static final String STICK_PUSH = "push";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean b(Context c, String k, boolean d) {
        return sp(c).getBoolean(k, d);
    }

    public static float f(Context c, String k, float d) {
        return sp(c).getFloat(k, d);
    }

    public static void setB(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    public static void setF(Context c, String k, float v) {
        sp(c).edit().putFloat(k, v).apply();
    }

    public static void setS(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }

    public static String mode(Context c) {
        return sp(c).getString(K_MODE, MODE_ACCESS);
    }

    public static boolean enabled(Context c)   { return b(c, K_ENABLED, true); }
    public static boolean invert(Context c)    { return b(c, K_INVERT, false); }
    public static boolean hscroll(Context c)   { return b(c, K_HS, true); }
    public static boolean cursorMode(Context c){ return b(c, K_CURSOR_ON, false); }
    public static String dpadMode(Context c)   { return sp(c).getString(K_DPAD_MODE, DPAD_PAGE); }
    public static String pageDir(Context c)    { return sp(c).getString(K_PAGE_DIR, PAGE_DIR_MATCH); }
    public static String stickMode(Context c)  { return sp(c).getString(K_STICK_MODE, STICK_CIRCLE); }
    public static float step(Context c)        { return f(c, K_STEP, 0.25f); }
    public static float swipeMs(Context c)     { return f(c, K_SWIPE, 260f); }
    public static float deadzone(Context c)    { return f(c, K_DEADZONE, 0.22f); }
    public static float stickSpeed(Context c)  { return f(c, K_STICK, 1.0f); }
    public static float cursorSpeed(Context c) { return f(c, K_CURSOR, 1.0f); }
}
