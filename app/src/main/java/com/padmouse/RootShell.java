package com.padmouse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 常驻 root shell，用来执行 `input swipe/tap/keyevent`。
 *
 * 关于 SukiSU / KernelSU：它们的 su 是 ksud 的软链，拿不到授权时会**静默退出**
 * （不像 Magisk 会打印 "permission denied"），而且首次授权要等用户在管理器里点允许，
 * 所以这里做了三件事：
 *   1. 依次尝试多个 su 路径（su / su -c sh / /system/bin/su / /debug_ramdisk/su ...）
 *   2. 握手最多等 45 秒，给授权弹窗留时间
 *   3. 进程提前退出（EOF）就立刻判定失败，并把原因带出去
 */
public final class RootShell {

    /** 依次尝试的 su 调用方式；{@code null} 结尾的数组表示"直接跑命令"的写法。 */
    public static final String[][] CANDIDATES = {
            {"su"},
            {"su", "-c", "sh"},
            {"/system/bin/su"},
            {"/system/bin/su", "-c", "sh"},
            {"/debug_ramdisk/su"},
            {"/debug_ramdisk/su", "-c", "sh"},
            {"/sbin/su"},
    };

    private Process proc;
    private BufferedWriter out;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger();
    private volatile long busySince = 0;
    private volatile boolean ready = false;
    private volatile boolean eof = false;
    private volatile String error = "";
    private volatile String suPath = "";
    private volatile boolean lastTimedOut = false;
    private Thread reader;

    public boolean isReady() {
        return ready;
    }

    public String error() {
        return error;
    }

    public String suPath() {
        return suPath;
    }

    /**
     * 打开 su 并做一次握手，成功返回 true。会阻塞最多几十秒，**别在主线程调用**。
     *
     * @param timeoutMs 每个候选等待授权的时间
     */
    public synchronized boolean open(long timeoutMs) {
        for (String[] cmd : CANDIDATES) {
            lastTimedOut = false;
            if (tryOpen(cmd, timeoutMs)) {
                suPath = join(cmd);
                return true;
            }
            if (lastTimedOut) {
                // su 是存在的，只是还在等授权：别把剩下的候选也挨个等一遍
                return false;
            }
        }
        if (error.isEmpty()) error = "找不到可用的 su";
        return false;
    }

    private boolean tryOpen(String[] cmd, long timeoutMs) {
        close();
        eof = false;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            final BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        String s;
                        while ((s = in.readLine()) != null) {
                            if (s.startsWith("__PM")) busy.set(false);
                            else lines.offer(s);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        eof = true;
                        busy.set(true);   // 进程没了，别再往里写
                    }
                }
            }, "pm-su-reader");
            reader.setDaemon(true);
            reader.start();

            // 握手：能不能拿到 uid=0（KernelSU 系被拒绝时会直接退出，不会留错误文本）
            out.write("id\n");
            out.flush();
            long deadline = System.currentTimeMillis() + timeoutMs;
            String last = "";
            while (System.currentTimeMillis() < deadline) {
                String l = lines.poll(150, TimeUnit.MILLISECONDS);
                if (l != null) {
                    last = l.trim();
                    if (l.contains("uid=0")) {
                        ready = true;
                        error = "";
                        return true;
                    }
                    if (l.toLowerCase().contains("denied")
                            || l.contains("not allowed")
                            || l.contains("Permission denied")) {
                        error = last;
                        close();
                        return false;
                    }
                }
                if (eof && lines.isEmpty()) {
                    // 进程提前没了：多半是被拒绝 / 这个 su 不能用
                    error = "su 立即退出" + (last.isEmpty() ? "（可能未授权，或该 su 不可用）" : "：" + last);
                    close();
                    return false;
                }
            }
            error = "等授权超时（" + join(cmd) + "）";
            lastTimedOut = true;
            close();
            return false;
        } catch (Throwable t) {
            error = join(cmd) + " 启动失败：" + shortMsg(t);
            close();
            return false;
        }
    }

    /** 执行一条命令；上一条还没跑完就丢弃，避免越按越延迟。 */
    public boolean run(String cmd) {
        if (!ready || proc == null) return false;
        if (busy.get()) {
            if (System.currentTimeMillis() - busySince > 4000) {
                busy.set(false);   // 看门狗，防止卡死
            } else {
                return false;
            }
        }
        if (!busy.compareAndSet(false, true)) return false;
        busySince = System.currentTimeMillis();
        try {
            out.write(cmd + "; echo __PM" + seq.incrementAndGet() + "__\n");
            out.flush();
            Dbg.gesture = "root: " + cmd;
            return true;
        } catch (Throwable t) {
            busy.set(false);
            ready = false;
            error = "写入 su 失败：" + shortMsg(t);
            return false;
        }
    }

    public void close() {
        ready = false;
        busy.set(false);
        try {
            if (out != null) out.close();
        } catch (Throwable ignored) {
        }
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignored) {
        }
        proc = null;
        out = null;
        lines.clear();
    }

    private static String join(String[] cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(cmd[i]);
        }
        return sb.toString();
    }

    private static String shortMsg(Throwable t) {
        String s = t.getMessage();
        return s == null || s.isEmpty() ? t.getClass().getSimpleName() : s;
    }
}
