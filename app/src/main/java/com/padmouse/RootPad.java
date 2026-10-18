package com.padmouse;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Root 模式的手柄读取：直接吞 /dev/input/eventN 的原始事件。
 *
 * 优点：
 *   - 不吃 Android 版本（不需要 14+ 才有摇杆），Android 8 也能读到模拟摇杆；
 *   - 不需要窗口焦点，游戏/浏览器占着焦点也能读；
 *   - 不依赖无障碍的按键过滤。
 * 代价：要 root（SukiSU / KernelSU / APatch / Magisk 都行）。
 *
 * 兼容性处理：
 *   - su 依次尝试 su / su -c sh / /system/bin/su / /debug_ramdisk/su / /sbin/su ...；
 *   - 节点按分数挑（有 jsN 最高，名字像手柄次之，排除触摸屏/传感器）；
 *   - cat 进程 1.2 秒内就退出，说明这个 su 或这个节点不行，换下一个；
 *   - 有多个候选节点时，8 秒内没有手柄事件就换下一个。
 */
public final class RootPad {

    public interface Listener {
        void onPadKey(int androidKeyCode, boolean down);

        void onPadAxes(float lx, float ly, float rx, float ry);

        /** @param level 0=在读 1=等待（没插手柄） 2=读失败（权限/节点问题） */
        void onPadStatus(int level, String detail);
    }

    /** /proc/bus/input/devices 里的一段 */
    public static final class Dev {
        public String name = "";
        public String handlers = "";
        public String node = null;
        public boolean joy = false;
        public boolean abs = false;
        public int score = 0;

        public String toString() {
            return name + (node == null ? "" : " @ " + node);
        }
    }

    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    private static final int ABS_X = 0, ABS_Y = 1, ABS_Z = 2, ABS_RX = 3, ABS_RY = 4, ABS_RZ = 5,
            ABS_HAT0X = 16, ABS_HAT0Y = 17;

    /** 读 /dev/input 时依次尝试的 su 写法（第 3 段是拼在后面的命令前缀）。 */
    private static final String[][] CAT_CANDIDATES = {
            {"su", "-c", "cat "},
            {"/system/bin/su", "-c", "cat "},
            {"/debug_ramdisk/su", "-c", "cat "},
            {"/data/adb/ksu/bin/su", "-c", "cat "},
            {"/sbin/su", "-c", "cat "},
    };

    private final Listener listener;
    private final int eventSize;
    private final int typeOff;

    private volatile boolean running;
    private Thread thread;

    private final float[] axMin = new float[64];
    private final float[] axMax = new float[64];
    private final boolean[] hasRange = new boolean[64];

    private float lx, ly, rx, ry;
    private int hatX, hatY;      // 十字键常走 ABS_HAT0X/Y，要自己转成按键事件
    private boolean sawDpadKeys;
    private volatile long lastEventAt;

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
        int idx = 0;
        while (running) {
            List<Dev> cands = candidates();
            if (cands.isEmpty()) {
                listener.onPadStatus(1, "没找到手柄的 event 节点（先连上手柄按一下键）");
                Dbg.padText = "未找到手柄节点（root）";
                sleep(2500);
                continue;
            }
            if (idx >= cands.size()) idx = 0;
            Dev d = cands.get(idx);
            Dbg.padText = d.name + " @ " + d.node;
            loadRanges(d.name);

            Process p = openCat(d, cands.size() == 1);
            if (p == null) {
                // su 或节点不行，2.5 秒后重来（su 授权往往是异步的）
                sleep(2500);
                continue;
            }

            boolean gotEvent = readLoop(p);
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }

            if (!running) break;
            if (cands.size() > 1) {
                // 多个候选：读过事件就继续用这个；没读到就试下一个
                idx = gotEvent ? idx : idx + 1;
            }
            if (!gotEvent) sleep(1200);
        }
    }

    /** 启动 cat 进程；成功（1.2 秒后还活着）返回进程，否则返回 null。 */
    private Process openCat(Dev d, boolean onlyCandidate) {
        String lastErr = "";
        for (String[] tpl : CAT_CANDIDATES) {
            String[] cmd = {tpl[0], tpl[1], tpl[2] + d.node};
            Process q;
            try {
                q = new ProcessBuilder(cmd).start();
            } catch (Throwable t) {
                lastErr = tpl[0] + " 不存在";
                continue;
            }
            final StringBuilder err = new StringBuilder();
            Thread et = new Thread(stderrDrainer(q, err), "pm-cat-err");
            et.setDaemon(true);
            et.start();

            boolean alive;
            try {
                alive = !q.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                alive = true;
            }
            if (!alive) {
                String e = err.toString().trim();
                lastErr = e.isEmpty() ? (tpl[0] + " 退出（code " + exitCode(q) + "）") : e;
                try {
                    q.destroy();
                } catch (Throwable ignored) {
                }
                continue;
            }
            return q;
        }
        String msg = lastErr.isEmpty() ? "读不到 " + d.node : lastErr;
        listener.onPadStatus(2, msg);
        Dbg.note("root 读手柄失败：" + msg);
        return null;
    }

    /**
     * 读事件流。返回 false 表示"有多个候选时，这个节点没读到任何手柄事件"。
     */
    private boolean readLoop(Process p) {
        final InputStream in = p.getInputStream();
        final long start = System.currentTimeMillis();
        final boolean[] got = {false};

        Thread rt = new Thread(new Runnable() {
            public void run() {
                byte[] ev = new byte[eventSize];
                while (running) {
                    if (!readFully(in, ev)) break;
                    got[0] = true;
                    handle(ev);
                }
            }
        }, "pm-root-read");
        rt.setDaemon(true);
        rt.start();

        // 8 秒观察窗口：没有事件说明可能挑错了节点
        long deadline = start + 8000;
        while (running && rt.isAlive() && System.currentTimeMillis() < deadline) {
            if (got[0]) break;
            sleep(150);
        }

        if (got[0]) {
            listener.onPadStatus(0, Dbg.padText + "（root 直读）");
        } else if (rt.isAlive()) {
            listener.onPadStatus(1, "节点 " + Dbg.padText + " 暂时没事件");
        }

        while (running && rt.isAlive() && got[0]) {
            sleep(400);
        }
        return got[0];
    }

    private void handle(byte[] ev) {
        int type = u16(ev, typeOff);
        int code = u16(ev, typeOff + 2);
        int value = s32(ev, typeOff + 4);

        if (type == EV_KEY) {
            int kc = androidKey(code);
            if (kc != 0) {
                lastEventAt = System.currentTimeMillis();
                if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                        || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    sawDpadKeys = true;   // 这个手柄的十字键走按键，HAT 就别再补一遍
                }
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
                case ABS_HAT0X: setHat(0, value > 0 ? 1 : (value < 0 ? -1 : 0)); break;
                case ABS_HAT0Y: setHat(1, value > 0 ? 1 : (value < 0 ? -1 : 0)); break;
            }
            Dbg.axis = String.format(java.util.Locale.US,
                    "LX %+.2f  LY %+.2f  RX %+.2f  RY %+.2f", lx, ly, rx, ry);
            Dbg.motionCount++;
        } else if (type == EV_SYN && code == 0) {
            listener.onPadAxes(lx, ly, rx, ry);
        }
    }

    /**
     * 十字键在 evdev 里经常是 ABS_HAT0X/ABS_HAT0Y（-1/0/1 的轴），不是按键。
     * 这里把它转成真正的 DPAD 按键按下/抬起，后面的映射逻辑才能共用。
     */
    private void setHat(int axis, int v) {
        int cur = axis == 0 ? hatX : hatY;
        if (v == cur) return;
        if (!sawDpadKeys) {
            if (cur != 0) emitHat(axis, cur, false);
            if (v != 0) emitHat(axis, v, true);
        }
        if (axis == 0) hatX = v;
        else hatY = v;
    }

    private void emitHat(int axis, int v, boolean down) {
        int kc;
        if (axis == 0) {
            kc = v < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        } else {
            kc = v < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        }
        lastEventAt = System.currentTimeMillis();
        Dbg.key = KeyNames.of(kc) + (down ? " ↓" : " ↑");
        Dbg.keyCount++;
        listener.onPadKey(kc, down);
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
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 按"像不像手柄"打分排序；触摸屏、按键板、传感器直接排除。 */
    public static List<Dev> candidates() {
        List<Dev> out = new ArrayList<>();
        for (Dev d : scan()) {
            if (d.node == null) continue;
            String n = d.name.toLowerCase();
            boolean bad = n.contains("touch") || n.contains("keyboard") || n.contains("gpio")
                    || n.contains("power") || n.contains("volume") || n.contains("accel")
                    || n.contains("compass") || n.contains("proximity") || n.contains("light")
                    || n.contains("hall") || n.contains("fingerprint") || n.contains("headset")
                    || n.contains("button") || n.contains("keys");
            int s = 0;
            if (d.joy) s += 100;
            if (n.contains("pad") || n.contains("controller") || n.contains("joystick")
                    || n.contains("gamepad") || n.contains("xbox") || n.contains("dualsense")
                    || n.contains("dualshock") || n.contains("stadia") || n.contains("8bitdo")
                    || n.contains("nintendo") || n.contains("switch") || n.contains("joy-con")) {
                s += 60;
            }
            if (d.abs && !bad) s += 30;
            if (bad) s -= 200;
            d.score = s;
            if (s > 0) out.add(d);
        }
        Collections.sort(out, new Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                return b.score - a.score;
            }
        });
        return out;
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
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    private static Runnable stderrDrainer(final Process proc, final StringBuilder sb) {
        return new Runnable() {
            public void run() {
                try {
                    InputStream es = proc.getErrorStream();
                    byte[] b = new byte[256];
                    int n;
                    while ((n = es.read(b)) > 0) {
                        if (sb.length() < 400) sb.append(new String(b, 0, n, "UTF-8"));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static int exitCode(Process p) {
        try {
            return p.exitValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
