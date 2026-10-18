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
 * 为什么不是每次都 exec("su -c ...")：su 握手 + 起进程要几百毫秒，
 * 连按十字键会明显跟不上。这里开一个 su 会话，命令排队写进去。
 *
 * 每条命令后面追加 `echo __PM<n>__`，读到标记就认为这条命令执行完了，
 * 这样既不会把命令堆到卡死，也能知道 input 有没有在跑。
 */
public final class RootShell {

    private Process proc;
    private BufferedWriter out;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger();
    private volatile long busySince = 0;
    private volatile boolean ready = false;
    private volatile String error = "";
    private Thread reader;

    public boolean isReady() { return ready; }

    public String error() { return error; }

    /** 打开 su 并做一次握手，成功返回 true。会阻塞，别在主线程调用。 */
    public synchronized boolean open() {
        close();
        try {
            proc = new ProcessBuilder("su").redirectErrorStream(true).start();
            out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
            final BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        String s;
                        while ((s = in.readLine()) != null) {
                            if (s.startsWith("__PM")) {
                                busy.set(false);
                            } else {
                                lines.offer(s);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "pm-su-reader");
            reader.setDaemon(true);
            reader.start();

            // 握手：能不能拿到 uid=0
            out.write("id\n");
            out.flush();
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                String l = lines.poll(120, TimeUnit.MILLISECONDS);
                if (l != null && l.contains("uid=0")) {
                    ready = true;
                    error = "";
                    return true;
                }
            }
            ready = false;
            error = "su 没有回应（未授权？）";
            close();
            return false;
        } catch (Throwable t) {
            ready = false;
            error = "su 启动失败：" + t;
            close();
            return false;
        }
    }

    /** 执行一条命令；上一条还没跑完就直接丢弃，避免越按越延迟。 */
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
            error = "写入 su 失败：" + t;
            return false;
        }
    }

    public void close() {
        ready = false;
        busy.set(false);
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        proc = null;
        out = null;
        lines.clear();
    }
}
