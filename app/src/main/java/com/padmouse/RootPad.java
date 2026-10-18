package com.padmouse;

import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Root 模式的手柄读取：直接吞 /dev/input/eventN 的原始事件。
 *
 * 优点：
 *   - 不吃 Android 版本（不需要 14+ 才有摇杆），Android 8 也能读到模拟摇杆；
 *   - 不需要窗口焦点，游戏/浏览器占着焦点也能读；
 *   - 不依赖无障碍的按键过滤。
 * 代价：要 root，且要能认出手柄对应的 event 节点。
 */
public final class RootPad {

    public interface Listener {
        void onPadKey(int androidKeyCode, boolean down);
        void onPadAxes(float lx, float ly, float rx, float ry);
        void onPadStatus(boolean ok, String detail);
    }

    /** /proc/bus/input/devices 里的一段 */
    public static final class Dev {
        public String name = "";
        public String handlers = "";
        public String node = null;
        public boolean joy = false;
        public boolean abs = false;

        public String toString() {
            return name + (node == null ? "" : " @ " + node);
        }
    }

    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    private static final int ABS_X = 0, ABS_Y = 1, ABS_Z = 2, ABS_RX = 3, ABS_RY = 4, ABS_RZ = 5,
            ABS_HAT0X = 16, ABS_HAT0Y = 17;

    private final Listener listener;
    private final int eventSize;
    private final int typeOff;

    private volatile boolean running;
    private Thread thread;

    private final float[] axMin = new float[64];
    private final float[] axMax = new float[64];
    private final boolean[] hasRange = new boolean[64];

    private float lx, ly, rx, ry, hx, hy;
    private long lastEventAt;

    public RootPad(Listener l) {
        this.listener = l;
        boolean is64 = true;
        try {
            is64 = android.os.Process.is64Bit();
        } catch (Throwable ignored) {
        }
        // struct input_event { timeval(16B on 64bit / 8B on 32bit); u16 type; u16 code; s32 value; }
        eventSize = is64 ? 24 : 16;
        typeOff = is64 ? 16 : 8;
    }

    public boolean isAlive() {
        return running;
    }

    public long lastEventAt() {
        return lastEventAt;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "pm-root-pad");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    // ---------------- 主循环 ----------------

    private void loop() {
        while (running) {
            Dev d = pick();
            if (d == null) {
                listener.onPadStatus(false, "没找到手柄的 event 节点");
                Dbg.padText = "未找到手柄节点（root）";
                sleep(2500);
                continue;
            }
            Dbg.padText = d.name + " @ " + d.node;
            loadRanges(d.name);

            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", "cat " + d.node).start();
                InputStream in = p.getInputStream();
                byte[] ev = new byte[eventSize];
                listener.onPadStatus(true, d.name + " @ " + d.node + "（root 直读）");
                while (running) {
                    if (!readFully(in, ev)) break;
                    handle(ev);
                }
            } catch (Throwable t) {
                Dbg.note("读手柄失败：" + t);
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
            if (running) sleep(1500);
        }
    }

    private void handle(byte[] ev) {
        int type = u16(ev, typeOff);
        int code = u16(ev, typeOff + 2);
        int value = s32(ev, typeOff + 4);

        if (type == EV_KEY) {
            int kc = androidKey(code);
            if (kc != 0) {
                lastEventAt = System.currentTimeMillis();
                Dbg.key = KeyNames.of(kc) + (value > 0 ? " ↓" : " ↑");
                Dbg.keyCount++;
                listener.onPadKey(kc, value > 0);
            }
        } else if (type == EV_ABS) {
            lastEventAt = System.currentTimeMillis();
            float n = norm(code, value);
            switch (code) {
                case ABS_X: lx = n; break;
                case ABS_Y: ly = n; break;
                case ABS_Z: rx = n; break;
                case ABS_RZ: ry = n; break;
                case ABS_RX: if (Math.abs(rx) < 0.05f) rx = n; break;
                case ABS_RY: if (Math.abs(ry) < 0.05f) ry = n; break;
                case ABS_HAT0X: hx = n; break;
                case ABS_HAT0Y: hy = n; break;
            }
            Dbg.axis = String.format(java.util.Locale.US,
                    "LX %+.2f  LY %+.2f  RX %+.2f  RY %+.2f", lx, ly, rx, ry);
            Dbg.motionCount++;
        } else if (type == EV_SYN && code == 0) {
            // 一帧结束，合并 HAT（有些手柄的十字键走 ABS_HAT0X/Y）
            float outLx = Math.abs(lx) < 0.05f ? hx : lx;
            float outLy = Math.abs(ly) < 0.05f ? hy : ly;
            listener.onPadAxes(outLx, outLy, rx, ry);
        }
    }

    // ---------------- 设备发现 ----------------

    /** 直接读 /proc/bus/input/devices（这个文件普通权限也能读）。 */
    public static List<Dev> scan() {
        List<Dev> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/bus/input/devices"));
            Dev cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("I:")) {
                    if (cur != null) out.add(cur);
                    cur = new Dev();
                } else if (cur != null && line.startsWith("N: Name=")) {
                    cur.name = line.substring(8).trim().replace("\"", "");
                } else if (cur != null && line.startsWith("H: Handlers=")) {
                    cur.handlers = line.substring(12).trim();
                    for (String h : cur.handlers.split("\\s+")) {
                        if (h.matches("event\\d+")) cur.node = "/dev/input/" + h;
                        if (h.matches("js\\d+")) cur.joy = true;
                    }
                } else if (cur != null && line.startsWith("B: ABS=")) {
                    String v = line.substring(7).trim();
                    cur.abs = !v.isEmpty() && !v.equals("0");
                }
            }
            if (cur != null) out.add(cur);
        } catch (Throwable t) {
            Dbg.note("读 /proc/bus/input/devices 失败：" + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static Dev pick() {
        Dev best = null;
        int bestScore = 0;
        for (Dev d : scan()) {
            if (d.node == null) continue;
            int s = 0;
            String n = d.name.toLowerCase();
            if (d.joy) s += 100;                      // 有 js0 基本就是手柄
            if (n.contains("pad") || n.contains("controller") || n.contains("joystick")
                    || n.contains("gamepad") || n.contains("xbox") || n.contains("dualsense")
                    || n.contains("dualshock") || n.contains("stadia") || n.contains("8bitdo")) {
                s += 60;
            }
            if (d.abs) s += 10;
            if (s > bestScore) {
                bestScore = s;
                best = d;
            }
        }
        return bestScore >= 60 ? best : null;
    }

    /** 从 Android 的 InputDevice 拿轴的 min/max，用来把原始值归一化到 -1~1。 */
    private void loadRanges(String name) {
        InputDevice target = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            int src = d.getSources();
            if ((src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) == 0) continue;
            if (name != null && name.equalsIgnoreCase(d.getName())) {
                target = d;
                break;
            }
            if (target == null) target = d;
        }
        if (target == null) return;
        try {
            for (InputDevice.MotionRange r : target.getMotionRanges()) {
                int a = r.getAxis();
                if (a >= 0 && a < axMin.length && r.getMax() > r.getMin()) {
                    axMin[a] = r.getMin();
                    axMax[a] = r.getMax();
                    hasRange[a] = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private float norm(int axis, int v) {
        if (axis >= 0 && axis < axMin.length && hasRange[axis]) {
            float min = axMin[axis], max = axMax[axis];
            if (max > min) {
                float f = 2f * (v - min) / (max - min) - 1f;
                return Math.max(-1f, Math.min(1f, f));
            }
        }
        if (axis == ABS_HAT0X || axis == ABS_HAT0Y) return Math.max(-1f, Math.min(1f, v));
        return Math.max(-1f, Math.min(1f, v / 32767f));
    }

    // ---------------- Linux 按键 -> Android 按键 ----------------

    public static int androidKey(int linux) {
        switch (linux) {
            case 0x130: return android.view.KeyEvent.KEYCODE_BUTTON_A;
            case 0x131: return android.view.KeyEvent.KEYCODE_BUTTON_B;
            case 0x133: return android.view.KeyEvent.KEYCODE_BUTTON_Y;
            case 0x134: return android.view.KeyEvent.KEYCODE_BUTTON_X;
            case 0x136: return android.view.KeyEvent.KEYCODE_BUTTON_L1;
            case 0x137: return android.view.KeyEvent.KEYCODE_BUTTON_R1;
            case 0x138: return android.view.KeyEvent.KEYCODE_BUTTON_L2;
            case 0x139: return android.view.KeyEvent.KEYCODE_BUTTON_R2;
            case 0x13A: return android.view.KeyEvent.KEYCODE_BUTTON_SELECT;
            case 0x13B: return android.view.KeyEvent.KEYCODE_BUTTON_START;
            case 0x13C: return android.view.KeyEvent.KEYCODE_BUTTON_MODE;
            case 0x13D: return android.view.KeyEvent.KEYCODE_BUTTON_THUMBL;
            case 0x13E: return android.view.KeyEvent.KEYCODE_BUTTON_THUMBR;
            case 0x220: return android.view.KeyEvent.KEYCODE_DPAD_UP;
            case 0x221: return android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            case 0x222: return android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            case 0x223: return android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return 0;
        }
    }

    // ---------------- 小工具 ----------------

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static boolean readFully(InputStream in, byte[] buf) {
        int got = 0;
        try {
            while (got < buf.length) {
                int n = in.read(buf, got, buf.length - got);
                if (n < 0) return false;
                got += n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
