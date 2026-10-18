package com.padmouse;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * Root 诊断：把"卡在哪一步"直接摊开给用户看。
 *
 * 背景：SukiSU / KernelSU 的 su 是 ksud 的软链，被拒绝时不会打印
 * "permission denied"，而是直接退出（没有输出），所以光看错误信息判断不出来。
 * 这里分别检查 su 是否存在、su -c id 能不能拿到 uid=0、输入设备节点长什么样。
 */
public final class RootProbe {

    private RootProbe() {
    }

    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备    ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统    Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("ABI     ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("Root    ").append(flavor()).append('\n');
        sb.append("管理器  ").append(managers()).append('\n');
        sb.append("本应用  uid=").append(android.os.Process.myUid()).append('\n');
        sb.append('\n');

        sb.append("── su 在哪 ──\n");
        sb.append(sh("command -v su; ls -l /system/bin/su /debug_ramdisk/su /sbin/su 2>&1")).append('\n');

        sb.append("── su -c id ──\n");
        sb.append(suId()).append('\n');

        sb.append("── 输入设备节点 ──\n");
        sb.append(padNodes()).append('\n');

        sb.append("── 如果上面没有 uid=0 ──\n");
        sb.append("1. 打开 SukiSU Ultra → 超级用户 → 允许「手柄鼠标」\n");
        sb.append("   （之前点过拒绝的，先把这条记录删掉再回 App 点「Root 诊断」）\n");
        sb.append("2. 管理器里如果有「su 超时」，调到 60 秒；有些机型还要关掉「自动拒绝」\n");
        sb.append("3. 先在终端里跑 su -c id 确认 root 本身正常\n");
        return sb.toString();
    }

    private static String suId() {
        String[] out = exec(new String[]{"su", "-c", "id"}, 30000);
        String text = out[1].trim();
        if (text.contains("uid=0")) {
            return "✅ 拿到 root\n" + text;
        }
        StringBuilder sb = new StringBuilder("❌ 没有拿到 root（exit=").append(out[0]).append("）\n");
        if (text.isEmpty()) {
            sb.append("（没有任何输出：SukiSU/KernelSU 被拒绝时就是这样，或者 su 不存在）");
        } else {
            sb.append(text);
        }
        return sb.toString();
    }

    private static String padNodes() {
        List<RootPad.Dev> list = RootPad.scan();
        if (list.isEmpty()) return "（读不到 /proc/bus/input/devices）";
        StringBuilder sb = new StringBuilder();
        for (RootPad.Dev d : list) {
            if (d.node == null) continue;
            if (!d.joy && !d.abs) continue;      // 只列可能的输入设备
            sb.append("• ").append(d.name).append("  ").append(d.node)
                    .append(d.joy ? "  [js]" : "").append('\n');
        }
        return sb.length() == 0 ? "（没找到带 event 节点的输入设备）" : sb.toString();
    }

    private static String flavor() {
        if (new File("/data/adb/ksu").exists()) return "KernelSU / SukiSU 系（/data/adb/ksu 存在）";
        if (new File("/data/adb/ap").exists()) return "APatch（/data/adb/ap 存在）";
        if (new File("/data/adb/magisk").exists()) return "Magisk（/data/adb/magisk 存在）";
        return "未检测到（也可能被隐藏了）";
    }

    private static String managers() {
        String s = sh("pm list packages 2>/dev/null | grep -iE 'sukisu|kernelsu|ksunext|apatch|magisk'").trim();
        return s.isEmpty() ? "未发现已知管理器" : s.replace("package:", "");
    }

    private static String sh(String cmd) {
        return exec(new String[]{"/system/bin/sh", "-c", cmd}, 8000)[1];
    }

    /** @return {exitCode, 合并后的输出} */
    private static String[] exec(String[] cmd, int timeoutMs) {
        Process p = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream out = bos;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] b = new byte[2048];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            out.write(b, 0, n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();

            boolean finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                t.join(400);
                return new String[]{"timeout", "⏱ 超时（授权框没点？）\n" + bos.toString("UTF-8")};
            }
            t.join(600);
            return new String[]{String.valueOf(p.exitValue()), bos.toString("UTF-8")};
        } catch (Throwable e) {
            return new String[]{"-1", "执行失败：" + e};
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
