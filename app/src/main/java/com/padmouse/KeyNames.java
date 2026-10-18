package com.padmouse;

import android.view.KeyEvent;

/** 把系统给的 KEYCODE_BUTTON_* 变成人话，方便界面里显示。 */
public final class KeyNames {

    private KeyNames() {}

    public static String of(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "MODE";
            case KeyEvent.KEYCODE_DPAD_UP: return "十字键 ↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "十字键 ↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "十字键 ←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "十字键 →";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "十字键 中";
            default:
                String s = KeyEvent.keyCodeToString(code);
                return s == null ? String.valueOf(code) : s.replace("KEYCODE_", "");
        }
    }
}
