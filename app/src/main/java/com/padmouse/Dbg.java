package com.padmouse;

/** 极简调试信息仓库：服务写，界面轮询显示。用来在没有 logcat 的情况下排查"手柄到底有没有被读到"。 */
public final class Dbg {

    public static volatile boolean alive = false;
    public static volatile String modeText = "—";
    public static volatile String padText = "—";
    public static volatile String rootText = "—";
    public static volatile String key = "—";
    public static volatile String axis = "—";
    public static volatile String gesture = "—";
    public static volatile String note = "—";
    public static volatile int keyCount = 0;
    public static volatile int motionCount = 0;

    private Dbg() {}

    public static void reset() {
        key = "—";
        axis = "—";
        gesture = "—";
        keyCount = 0;
        motionCount = 0;
    }

    public static void note(String s) {
        note = s;
    }
}
