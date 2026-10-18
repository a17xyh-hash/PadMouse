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

## 十字键在 root 下要自己转

不少手柄的十字键在 evdev 里是 **`ABS_HAT0X` / `ABS_HAT0Y`（-1/0/1 的轴）**，不是
`BTN_DPAD_*` 按键。Android 的 InputReader 会替你转成方向键，所以无障碍模式看不出来；
但 root 直读原始事件时如果只把它当摇杆轴，按左按右就什么都不会发生（0.4 之前的问题）。
现在 `setHat()` 会做边沿检测，把 HAT 变化转成真正的 `KEYCODE_DPAD_*` 按下/抬起事件，
再走同一套映射。

## 按键动作不会被吞

手势是串行的：上一条 200~300ms 的滑动还没播完时用户又按了一下，如果直接丢弃，
手感就是"按了没反应"。所以按键触发的动作走 `keyAction()` 排队：等当前手势结束
（通常几十毫秒）再补发，保证按一下就有一下。摇杆的连续滚动仍然"忙则跳过"，
避免越滚越延迟。

## 翻页

`page(fingerDir)` 在屏幕中部做一条横向 72% 宽度的滑动（0.14W ↔ 0.86W），
避开左右边缘，不会误触系统返回手势；时长取 `max(200ms, 滑动时长)`。
`fingerDir` 是**手指方向**：按 ← 就是 -1（手指往左），按 → 就是 +1。
"按左=上一页"的导航语义可以在设置里切换。

## Root 在 SukiSU / KernelSU 上的坑

这几家和 Magisk 的 `su` 不一样，踩过才知道：

1. **拒绝是静默的**。Magisk 的 su 会打印 `permission denied`，SukiSU/KernelSU 的 su 直接退出、
   没有输出。所以判断"失败"只能靠 **stdin 管道 EOF**（本项目的做法），不能靠错误文本。
2. **首次授权是异步的**，等用户点「允许」可能要十几秒。原来只等 12 秒会误判成失败，
   现在每个候选等 30 秒、最多 2 轮，期间屏幕上有提示。
3. **`su` 无参数时 exec 的是 `$SHELL`（一般 /system/bin/sh）**，管道喂命令是可行的；
   但不同 ROM 的 su 包装脚本各异，所以这里还会试 `su -c sh`、`/system/bin/su`、
   `/debug_ramdisk/su` 等写法。
4. **读 `/dev/input/eventN`** 需要 root 上下文（`u:r:su:s0`）。如果 cat 进程 1.2 秒内就退出，
   基本就是没权限或节点不存在，直接换下一个 su 写法 / 下一个节点。
5. 找不到手柄节点时**不**回退（可能只是还没连手柄）；只有"有节点但读不了"才回退到
   无障碍模式，避免用户刚切 Root 就被悄悄换走。

## 没有做的事（欢迎 PR）

- Root 模式下不依赖无障碍的独立前台服务（目前仍由无障碍服务承载注入）。
- 拖拽/框选、双指缩放、连发（turbo）。
- 每个按键自定义映射（现在只有开关和手感参数）。
