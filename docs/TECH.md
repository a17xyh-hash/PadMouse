# 实现说明

## 为什么不是"驱动"

Android 上想让手柄去"点/划屏幕"，不用改内核驱动，只要拿到手柄事件 + 伪造触摸事件就行。
键盘/手柄按键是 `KeyEvent`，摇杆是 `MotionEvent`（`SOURCE_JOYSTICK`），把按键映射成
`dispatchGesture()` 的手势即可，全部在应用层完成。所以这个 App 不需要任何内核模块，
卸载即恢复原样。

## 无障碍模式

- 服务声明 `canRequestFilterKeyEvents="true"` + 运行时 `FLAG_REQUEST_FILTER_KEY_EVENTS`，
  就能在 `onKeyEvent()` 里看到并吞掉手柄按键（`KEYCODE_BUTTON_*`、`KEYCODE_DPAD_*`）。
- 摇杆走 API 34 才有的 `AccessibilityServiceInfo#setMotionEventSources()` +
  `AccessibilityService#onMotionEvent()`。低版本系统没有这个接口——这是"Android 14 以下
  摇杆不可用"的根本原因，不是本项目的取舍。
- 按键按下时记录 keycode，抬起时一并吞掉，避免应用收到"只有抬起没有按下"的怪事件。

## Root 模式

1. 解析 `/proc/bus/input/devices`（普通权限可读），按 `Handlers` 里有没有 `jsN`、
   名字里有没有 pad/controller 等关键词打分，选出最像手柄的那一段，拿到 `eventN`。
2. `su -c cat /dev/input/eventN` 把这路事件流读到进程里。`struct input_event`：
   64 位 24 字节（timeval 16 + type 2 + code 2 + value 4），32 位 16 字节（8 + 2 + 2 + 4）。
3. 轴值的范围（min/max）从 Android 的 `InputDevice#getMotionRanges()` 里按设备名匹配拿，
   把原始值归一化成 -1..1；HAT 轴（十字键走 ABS 的手柄）合并进左摇杆。
4. 只在 `EV_SYN/SYN_REPORT` 时提交一帧，避免一帧发多次。
5. 事件停止 1.2 秒就把摇杆归零，防止手柄断连后"自己一直滚"。

## 注入

优先 `dispatchGesture()`：单指按路径移动，`StrokeDescription` 的 duration 控制"划得多快"。
一次手势一个 `inflight` 计数，带 4 秒看门狗（回调丢失时不至于把通道锁死）。

如果 `dispatchGesture()` 返回 false（服务没连、被系统拒），并且 root 通道就绪，就退回
`input swipe x1 y1 x2 y2 ms`（点击 `input tap`）。`RootShell` 用一个常驻 `su` 会话，
每条命令后追加 `echo __PMn__` 当完成标记，上一条没跑完就丢弃新命令，避免越按越延迟。

## 滚动方向的约定

`dy = +1` 表示"内容往下看"：手指从下往上划。所有映射都先换算成这个约定，再处理"反向滚动"。

## 光标模式

一个 `TYPE_ACCESSIBILITY_OVERLAY` 小圆点（不可触摸、不可聚焦），右摇杆按 40ms 步进移动，
点击时用 `dispatchGesture` 在该坐标做一次 70ms 的按住（长按 700ms）。

## 摇杆画圈（拨轮模式）

`circleScroll()` 每 40ms 取一次左摇杆角度 `atan2(ly, lx)`，把相邻两帧的角差归一化到
(-π, π] 后累加；累加到 60°（`π/3`，可被「摇杆速度」整除）就派发一次 1/10 屏的滑动，
顺时针（角度增大）= 往下滚，逆时针 = 往上滚。回中或猛甩产生的 >1.2rad 跳变直接丢弃，
松杆时清零，所以不会误触。

另外注意：注册了 `SOURCE_JOYSTICK` 后这些摇杆事件就不再下发给别的应用（系统机制），
所以暂停（SELECT）或切 Root 模式时会调用 `setMotionEventSources(0)` 把摇杆还回去。

## 翻页

`page(dir)` 在屏幕中部做一条横向 58% 宽度的滑动（0.21W → 0.79W），避开左右边缘，
不会误触系统返回手势；时长取 `max(200ms, 滑动时长)`。

## 没有做的事（欢迎 PR）

- Root 模式下不依赖无障碍的独立前台服务（目前仍由无障碍服务承载注入）。
- 拖拽/框选、双指缩放、连发（turbo）。
- 每个按键自定义映射（现在只有开关和手感参数）。
