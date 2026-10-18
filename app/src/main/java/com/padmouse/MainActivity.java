package com.padmouse;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0B0D11, CARD = 0xFF161B22, CARD2 = 0xFF1E2530,
            TXT = 0xFFF2F4F8, DIM = 0xFF8B95A5, HINT = 0xFF6B7482,
            ACCENT = 0xFF3B82F6, GOOD = 0xFF34D399, WARN = 0xFFFBBF24, LINE = 0xFF242C38;

    private static final String DESC_ACCESS =
            "免 root。按键通过无障碍的按键过滤拿到，滚动由系统手势派发。\n"
                    + "· 十字键 / L1 / R1 / L2 / R2 / L3 / R3 都能滚\n"
                    + "· 摇杆需要 Android 14 及以上（系统才允许无障碍读摇杆），否则只能按键滚动";

    private static final String DESC_ROOT =
            "需要 su 授权。直接读 /dev/input 的原始事件，任何 Android 版本都能用摇杆，\n"
                    + "游戏/浏览器占着焦点也能读到。注入优先走无障碍手势，失败时自动退回 input。";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tabAccess, tabRoot, modeDesc;
    private TextView tabDpadPage, tabDpadScroll, tabStickCircle, tabStickPush;
    private TextView stService, stRoot, stPad, stStick, dbgTv;
    private Switch swOn, swInv, swHs, swCursor, swHint;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(30));
        sc.addView(col);
        setContentView(sc);

        header(col);
        modeCard(col);
        statusCard(col);
        switchCard(col);
        opCard(col);
        feelCard(col);
        testCard(col);
        mapCard(col);
        footer(col);

        refresh();
        ui.post(poll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 600);
        }
    };

    // ------------------------------------------------------------ 各个卡片

    private void header(LinearLayout col) {
        col.addView(tv("手柄鼠标", 27, TXT, true));
        TextView s = tv("把游戏手柄当成鼠标：十字键滚页面，摇杆顺滑滚屏，START 切指针", 12.5f, DIM, false);
        s.setPadding(0, dp(6), 0, dp(18));
        s.setLineSpacing(dp(2), 1f);
        col.addView(s);
    }

    private void modeCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("运行模式", 12f, DIM, true));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        tabAccess = tab("无障碍 · 免 root");
        tabRoot = tab("Root · 直读手柄");
        tabAccess.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(Prefs.MODE_ACCESS);
            }
        });
        tabRoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(Prefs.MODE_ROOT);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        row.addView(tabAccess, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp2.leftMargin = dp(8);
        row.addView(tabRoot, lp2);
        card.addView(row);

        modeDesc = tv("", 11.5f, HINT, false);
        modeDesc.setPadding(0, dp(12), 0, 0);
        modeDesc.setLineSpacing(dp(3), 1f);
        card.addView(modeDesc);
    }

    private void statusCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("状态", 12f, DIM, true));

        stService = addRow(card, "无障碍服务", "—");
        stRoot = addRow(card, "Root 读取", "—");
        stStick = addRow(card, "摇杆通道", "—");
        stPad = addRow(card, "手柄", "—");

        Button open = button("打开无障碍设置", false);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("找到「手柄鼠标」并打开");
                } catch (Throwable t) {
                    toast("打不开设置：" + t.getMessage());
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(14);
        card.addView(open, lp);
    }

    private void switchCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("开关", 12f, DIM, true));

        swOn = addSwitch(card, "接管手柄", "关掉后手柄恢复原样（手柄上按 SELECT 也能切）",
                true, Prefs.K_ENABLED);
        swInv = addSwitch(card, "反向滚动", "上下颠倒，看你习惯",
                false, Prefs.K_INVERT);
        swHs = addSwitch(card, "十字键左右横滚", "浏览器/看图时左右滑",
                true, Prefs.K_HS);
        swCursor = addSwitch(card, "光标模式（当鼠标）",
                "START 切换：右摇杆移动指针，A 点击，X 长按，B 返回",
                false, Prefs.K_CURSOR_ON);
        swHint = addSwitch(card, "操作提示浮条", "手柄切换模式时在屏幕上提示一下",
                true, Prefs.K_HINT);
    }

    private void opCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("操作方式", 12f, DIM, true));

        TextView[] d = seg(card, "十字键",
                Prefs.K_DPAD_MODE,
                new String[]{Prefs.DPAD_PAGE, Prefs.DPAD_SCROLL},
                new String[]{"左右翻页", "上下滚动"});
        tabDpadPage = d[0];
        tabDpadScroll = d[1];

        TextView[] s = seg(card, "左摇杆",
                Prefs.K_STICK_MODE,
                new String[]{Prefs.STICK_CIRCLE, Prefs.STICK_PUSH},
                new String[]{"画圈当滚轮", "推杆滚屏"});
        tabStickCircle = s[0];
        tabStickPush = s[1];

        TextView tip = tv("画圈：顺时针往下、逆时针往上（转 60° 滚一格，可配合「摇杆速度」调灵敏度）。\n"
                + "推杆：跟以前一样，推得越深滚得越快。", 10.5f, HINT, false);
        tip.setPadding(0, dp(12), 0, 0);
        tip.setLineSpacing(dp(3), 1f);
        card.addView(tip);
    }

    /** 两段式选择器：点一下写 pref，刷新时高亮当前项。 */
    private TextView[] seg(LinearLayout card, String title, final String key,
                           String[] values, String[] labels) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);
        box.addView(tv(title, 13f, TXT, false));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        TextView[] out = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final String v = values[i];
            TextView t = tab(labels[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    Prefs.setS(MainActivity.this, key, v);
                    refresh();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(t, lp);
            out[i] = t;
        }
        box.addView(row);
        card.addView(box);
        return out;
    }

    private void feelCard(LinearLayout col) {        LinearLayout card = card(col);
        card.addView(tv("手感", 12f, DIM, true));
        addSlider(card, "滚动步长", Prefs.K_STEP, 0.05f, 0.60f, 0.25f, "pct");
        addSlider(card, "滑动时长", Prefs.K_SWIPE, 80f, 400f, 260f, "ms");
        addSlider(card, "摇杆死区", Prefs.K_DEADZONE, 0.05f, 0.50f, 0.22f, "pct");
        addSlider(card, "摇杆速度", Prefs.K_STICK, 0.3f, 3.0f, 1.0f, "x");
        addSlider(card, "光标速度", Prefs.K_CURSOR, 0.3f, 2.5f, 1.0f, "x");
    }

    private void testCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("测试 / 诊断", 12f, DIM, true));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        Button t1 = button("试一下滚动", true);
        t1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (PadMouseService.INSTANCE == null) {
                    toast("先开启无障碍服务");
                } else {
                    PadMouseService.INSTANCE.testScroll();
                }
            }
        });
        Button t2 = button("测试 su", false);
        t2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testRoot();
            }
        });
        row.addView(t1, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp2.leftMargin = dp(8);
        row.addView(t2, lp2);
        card.addView(row);

        dbgTv = tv("—", 11f, HINT, false);
        dbgTv.setPadding(0, dp(14), 0, 0);
        dbgTv.setLineSpacing(dp(3), 1f);
        card.addView(dbgTv);
    }

    private void mapCard(LinearLayout col) {
        LinearLayout card = card(col);
        card.addView(tv("默认映射", 12f, DIM, true));
        TextView t = tv(
                "十字键 ← / ↑      上一页（翻页模式）\n"
                        + "十字键 → / ↓      下一页（翻页模式）\n"
                        + "十字键（滚动模式） 上下滚动 / 左右横滚\n"
                        + "L1 / R1            大步滚动（约 2 倍）\n"
                        + "L2 / R2 / L3 / R3  大步滚动\n"
                        + "左摇杆             画圈 = 滚轮：顺时针往下、逆时针往上\n"
                        + "右摇杆             移动光标（光标模式）\n"
                        + "A                  点击（光标模式）\n"
                        + "X                  长按（光标模式）\n"
                        + "B                  返回（光标模式）\n"
                        + "START              开关光标模式\n"
                        + "SELECT             暂停 / 恢复接管（暂停时摇杆还给游戏）", 11.5f, DIM, false);
        t.setPadding(0, dp(12), 0, 0);
        t.setLineSpacing(dp(4), 1f);
        card.addView(t);
    }

    private void footer(LinearLayout col) {
        TextView t = tv("MIT License · 完全本地运行，不联网、不上传任何数据\n"
                + "源码：github.com/a17xyh-hash/PadMouse", 10.5f, HINT, false);
        t.setPadding(0, dp(20), 0, 0);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(3), 1f);
        col.addView(t);
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
        boolean rootMode = Prefs.mode(this).equals(Prefs.MODE_ROOT);
        boolean alive = PadMouseService.INSTANCE != null;

        stService.setText(alive ? "已连接" : (listedInSettings() ? "已勾选，等待连接" : "未开启"));
        stService.setTextColor(alive ? GOOD : WARN);

        if (rootMode) {
            String p = Dbg.padText;
            stRoot.setText(p == null || p.equals("—") ? (alive ? "准备中…" : "服务未启动") : p);
            stRoot.setTextColor(p != null && p.startsWith("⚠") ? WARN : DIM);
        } else {
            stRoot.setText("未使用");
            stRoot.setTextColor(HINT);
        }

        if (rootMode) {
            stStick.setText("root 直读 /dev/input（全版本可用）");
            stStick.setTextColor(GOOD);
        } else if (Build.VERSION.SDK_INT >= 34) {
            stStick.setText("系统支持（Android " + Build.VERSION.RELEASE + "）");
            stStick.setTextColor(DIM);
        } else {
            stStick.setText("本机不支持，请用 Root 模式");
            stStick.setTextColor(WARN);
        }

        stPad.setText(deviceList());

        dbgTv.setText(String.format(Locale.US,
                "按键 %d 次 · 摇杆事件 %d 次\n最近按键：%s\n最近摇杆：%s\n注入：%s\n备注：%s",
                Dbg.keyCount, Dbg.motionCount, Dbg.key, Dbg.axis, Dbg.gesture, Dbg.note));

        syncing = true;
        swOn.setChecked(Prefs.enabled(this));
        swInv.setChecked(Prefs.invert(this));
        swHs.setChecked(Prefs.hscroll(this));
        swCursor.setChecked(Prefs.cursorMode(this));
        swHint.setChecked(Prefs.b(this, Prefs.K_HINT, true));
        syncing = false;

        styleTab(tabAccess, !rootMode);
        styleTab(tabRoot, rootMode);
        styleTab(tabDpadPage, Prefs.DPAD_PAGE.equals(Prefs.dpadMode(this)));
        styleTab(tabDpadScroll, Prefs.DPAD_SCROLL.equals(Prefs.dpadMode(this)));
        styleTab(tabStickCircle, Prefs.STICK_CIRCLE.equals(Prefs.stickMode(this)));
        styleTab(tabStickPush, Prefs.STICK_PUSH.equals(Prefs.stickMode(this)));
        modeDesc.setText(rootMode ? DESC_ROOT : DESC_ACCESS);
    }

    private String deviceList() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            int src = d.getSources();
            if ((src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)) == 0) {
                continue;
            }
            boolean stick = false;
            try {
                for (InputDevice.MotionRange r : d.getMotionRanges()) {
                    if (r.getAxis() == MotionEvent.AXIS_X || r.getAxis() == MotionEvent.AXIS_Y) stick = true;
                }
            } catch (Throwable ignored) {
            }
            if (n > 0) sb.append('\n');
            sb.append("• ").append(d.getName()).append(stick ? " · 有摇杆" : " · 仅按键");
            n++;
        }
        return n == 0 ? "未检测到（先连上手柄，随便按一下键）" : sb.toString();
    }

    private boolean listedInSettings() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String flat = new ComponentName(this, PadMouseService.class).flattenToString();
            String shortForm = getPackageName() + "/." + PadMouseService.class.getSimpleName();
            return s.contains(flat) || s.contains(shortForm);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 动作

    private void setMode(String mode) {
        if (mode.equals(Prefs.mode(this))) return;
        Prefs.setS(this, Prefs.K_MODE, mode);
        refresh();
        if (PadMouseService.INSTANCE == null) {
            toast("已记下，开启无障碍服务后生效");
        }
    }

    private void testRoot() {
        toast("正在测试 su…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootShell s = new RootShell();
                final boolean ok = s.open();
                final String msg = ok ? "root 可用 ✅" : "root 不可用：" + s.error();
                s.close();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(msg);
                        Dbg.note(msg);
                    }
                });
            }
        }, "pm-test-su").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 控件工厂

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout col) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), LINE);
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        col.addView(card, lp);
        return card;
    }

    private TextView addRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, 0);

        TextView l = tv(label, 12.5f, DIM, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        TextView v = tv(value, 12.5f, TXT, false);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.1f));
        card.addView(row);
        return v;
    }

    private Switch addSwitch(LinearLayout card, String title, String sub, boolean def, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv(title, 13.5f, TXT, false));
        if (sub != null) {
            TextView s = tv(sub, 10.5f, HINT, false);
            s.setPadding(0, dp(3), dp(10), 0);
            s.setLineSpacing(dp(2), 1f);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(Prefs.b(this, key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                if (!syncing) Prefs.setB(MainActivity.this, key, v);
            }
        });
        row.addView(sw);
        card.addView(row);
        return sw;
    }

    private void addSlider(LinearLayout card, String title, final String key,
                           final float min, final float max, final float def, final String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(tv(title, 13f, TXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = tv(fmt(Prefs.f(this, key, def), unit), 12f, ACCENT, true);
        val.setGravity(Gravity.END);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        sb.setProgress(toProgress(Prefs.f(this, key, def), min, max));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = min + (max - min) * p / 1000f;
                val.setText(fmt(v, unit));
                if (fromUser) Prefs.setF(MainActivity.this, key, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
        card.addView(box);
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(primary ? 0xFFFFFFFF : TXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD2);
        g.setCornerRadius(dp(12));
        if (!primary) g.setStroke(dp(1), LINE);
        b.setBackground(g);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        return b;
    }

    private TextView tab(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        styleTab(t, false);
        return t;
    }

    private void styleTab(TextView t, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(active ? ACCENT : CARD2);
        g.setCornerRadius(dp(11));
        if (!active) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        t.setTextColor(active ? 0xFFFFFFFF : DIM);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private int toProgress(float v, float min, float max) {
        float p = (v - min) / (max - min);
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        return Math.round(p * 1000);
    }

    private String fmt(float v, String unit) {
        if ("pct".equals(unit)) return Math.round(v * 100) + "%";
        if ("ms".equals(unit)) return Math.round(v) + " ms";
        return String.format(Locale.US, "%.2f×", v);
    }
}
