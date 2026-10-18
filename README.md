# 手柄鼠标 · PadMouse

把游戏手柄当成手机鼠标用：**十字键滚页面、摇杆顺滑滚屏、START 一键切成屏幕指针**。
给"鼠标滚轮坏了""躺着不想伸手划屏幕""手游里想用手柄翻列表"这类场景用。

- 纯本地运行，**不联网、不申请网络权限、不上传任何数据**
- 免 root 可用（无障碍模式）；有 root 更完整（Root 模式，能读摇杆）
- 体积 60 KB，无第三方依赖，无 Gradle 也能编译

## 两种模式

| | 无障碍模式（免 root） | Root 模式 |
|---|---|---|
| 按键（十字键 / L1 / R1 / A / B …） | 无障碍按键过滤 | 直接读 `/dev/input/eventN` |
| **摇杆** | 需要 **Android 14+** | **任何版本都能读** |
| 抢不抢焦点 | 不抢 | 不抢（evdev 是旁路读取） |
| 需要什么 | 开启无障碍服务 | su 授权（注入仍优先用无障碍手势） |
| 适用 | 大多数人 | 老系统想用摇杆 / 游戏独占输入 |

> Root 模式也建议把无障碍服务开着：手势注入走系统 API 更准更快；万一派发失败，会自动退回
> `su -c input swipe`，保证还能滚。

## 默认映射

| 手柄按键 | 动作 |
|---|---|
| 十字键 ↑ / ↓ | 向下 / 向上滚动一屏的 25% |
| 十字键 ← / → | 左右横滚 |
| L1 / R1 | 大步滚动（约 2 倍步长） |
| L2 / R2 / L3 / R3 | 大步滚动 |
| 左摇杆 | 顺滑连续滚动（推得越多越快，松手即停） |
| 右摇杆 | 移动光标（光标模式） |
| A | 点击（光标模式） |
| X | 长按（光标模式） |
| B | 返回（光标模式） |
| START | 开关光标模式 |
| SELECT | 暂停 / 恢复接管（暂停时摇杆会还给游戏） |

所有力度都能在 App 里调：滚动步长、滑动时长、摇杆死区、摇杆速度、光标速度、反向滚动、左右横滚开关。

## 安装

1. 到 Releases 下载 `PadMouse-0.1.apk` 安装（Android 8.0+）。
2. 打开 App → **打开无障碍设置** → 找到「手柄鼠标」→ 打开。
3. 蓝牙 / USB 连上手柄，随便按一下键，App 的「状态」里应该能看到手柄名字。
4. 回到浏览器 / 阅读 App，用十字键或左摇杆滚屏。

Root 模式：App 里切到「Root · 直读手柄」，首次会弹 su 授权，允许即可（可先用「测试 su」验证）。

## 权限说明（就这些）

- **无障碍服务**：只用来 (a) 读手柄按键 (b) 派发滑动手势。`canRetrieveWindowContent=false`，不读屏幕内容。
- **Root（可选）**：读 `/dev/input/event*` 和必要时执行 `input swipe/tap`。不需要的话完全不用给。
- 没有 `INTERNET` 权限，装完可以断网用。

## 已知限制

- 系统锁屏、`FLAG_SECURE` 界面（部分银行/流媒体）里手势注入无效，这是 Android 的限制。
- 少数游戏会用 `EVIOCGRAB` 独占手柄节点，此时 Root 模式也可能读不到；无障碍模式的按键过滤一般还能用。
- 无障碍模式下，**手柄会被本 App 独占**（系统机制：服务订阅了摇杆来源，就不再下发给别的应用）。
  想正常打游戏时按 SELECT 暂停，或关掉服务。
- Android 14 以下、又没有 root 时，摇杆读不到，只能用按键滚（这是系统 API 的限制）。
- 光标模式是"点一下走一步"的指针，不是系统级鼠标指针，不能拖拽选词。

## 自己编译

不用 Gradle，直接用 SDK build-tools：

```bash
# 需要 JDK 17、Android SDK（build-tools 35、platform android-35）、node（mkzip.js 打包 dex）
./build.sh                                   # 产出 ./PadMouse-0.1.apk
VER_CODE=2 VER_NAME=0.2 ./build.sh /tmp/p.apk
```

签名默认用调试 keystore，可以通过环境变量指定自己的：

```bash
KS=/path/to/your.keystore ./build.sh
```

## 代码结构

```
app/src/main/java/com/padmouse/
├── MainActivity.java     设置界面（纯代码布局，无 XML 依赖）
├── PadMouseService.java  无障碍服务：按键映射、摇杆、手势派发、浮层
├── RootPad.java          Root 模式：扫 /proc/bus/input/devices + 解析 evdev 原始事件
├── RootShell.java        常驻 su shell，带命令排队与完成标记
├── CursorOverlay.java    光标模式的小圆点浮层
├── Prefs.java            全部参数与默认值
├── Dbg.java              给界面看的诊断信息
└── KeyNames.java         按键名 -> 人话
```

实现细节见 [docs/TECH.md](docs/TECH.md)。

## License

[MIT](LICENSE)
