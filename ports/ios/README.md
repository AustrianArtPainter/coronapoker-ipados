# CoronaPoker 25.86 — native iPadOS port

Unofficial port of **[tonikelope/coronapoker](https://github.com/tonikelope/coronapoker)**,
based on **[v25.86](https://github.com/tonikelope/coronapoker/tree/v25.86)**.
The original game belongs to tonikelope and its contributors. This fork preserves
the upstream license, credits and Git history; see [IPADOS-NOTICE.md](../../IPADOS-NOTICE.md).

本移植将现有 Java/libGDX 游戏编译为原生 ARM64 App，供个人用自己的
Apple ID 签名安装。主要目标是 iPad；iPhone 实机验证目前暂停。

## Features and scope

- 加入原版 **25.86** 电脑端的房间，或在 iPad 本机创建房间。
- 在本机离线与原版机器人对战，沿用桌面布局、牌局规则和加密协议。
- UIKit 系统键盘、输入/退格/完成、长按剪切/粘贴、文件选择头像。
- 原版格式的语音消息录制、发送和回放；原生截图生成及图片剪贴板。
- 本机后台/锁屏主持：需要持续、实际可听见的游戏音乐播放。
- 自己的玩家超时仍按原规则：**可以免费 CHECK 就过牌，否则 FOLD**。
  不会替用户自动下注或加注；正常机器人仍使用原版策略。

协议严格匹配 **25.86**。不能把原版最新分支或其他版本直接混用。
当前最低部署目标为 iOS/iPadOS **18.0**，但这只是构建目标；尚未完成
iOS 18 实机或 iPhone 实机验证。实机结果见下文，不能推广为所有设备保证。

持续音乐播放是已验证的后台执行条件。不要静音、停止音乐或强制退出后仍
期待设备继续主持。来电、音频会话中断、系统资源限制、设备关机或系统终止
App 都可能影响后台执行。没有借助外部电脑或服务器接管主持。

当前升级分支为 `ipados-25.86`，尚未推送；下面的分支克隆命令在发布后适用。

## Build from a fresh checkout

以下命令从仓库根目录执行。此构建流程针对 **Apple Silicon Mac**。

前置条件：

1. Xcode 和 iOS SDK，完成首次启动和组件安装。已验证环境是 **Xcode 26.6 / iOS SDK 26.5**。
   `xcode-select -p` 应指向完整 Xcode；编译脚本默认读取当前选择的 SDK 版本。
   其他 Xcode/SDK 组合尚未验证。
2. Python **3.12+** 和 Maven **3.9+** 已在 PATH 中可用。
3. 安装实机时，在 Xcode Settings → Accounts 登录自己的 Apple ID；连接并信任
   iPad，开启设备“设置 → 隐私与安全性 → 开发者模式”。
4. 首次准备需要网络和数 GB 磁盘空间；完整 AOT 编译可能需要较长时间。

```sh
git clone --branch ipados-25.86 https://github.com/AustrianArtPainter/coronapoker-ipados.git
cd coronapoker-ipados
python3 ports/ios/tools/bootstrap.py
python3 ports/ios/tools/build_game.py
```

`bootstrap.py` 下载并逐一校验固定的 JDK、MobiVM、字节码工具、iOS 原生依赖
和 SQLite 源码，随后用固定 JDK 执行原版 Maven `package`。所有缓存和输出
留在忽略的目录内；校验失败会停止，不会自动替换为新的依赖版本。
首次构建不需要手动复制其他人的缓存。

生成的未签名 App：`ports/ios/build/game/CoronaPoker.app`。

可选构建方式：

```sh
# 只准备下载及工具链；不执行 Maven
python3 ports/ios/tools/bootstrap.py --downloads-only

# 依赖都已缓存时，禁止联网准备（Maven 也使用离线模式）
python3 ports/ios/tools/bootstrap.py --offline

# 只验证 Java 编译和构建期字节码转换
python3 ports/ios/tools/build_game.py --compile-only

# ARM64 模拟器构建；模拟器不能证明实机锁屏后台执行
python3 ports/ios/tools/build_game.py --simulator
```

## Sign and install with your own Apple ID

查看连接设备：

```sh
xcrun devicectl list devices
```

用自己的设备 UDID/CoreDevice ID 和 Xcode Team ID 替换下面的占位符。
脚本不包含维护者的默认设备或团队，也不导出私钥。

```sh
python3 ports/ios/tools/install_ipad.py \
  --device YOUR_DEVICE_ID --team YOUR_TEAM_ID \
  --refresh-profile --launch
```

脚本生成小型 Xcode 签名目标，让已登录的 Xcode 为同一 Bundle ID 创建开发
配置文件，再签名真正的 AOT 游戏和内嵌 Frameworks，校验签名并安装。
最终启动正常游戏，不设置验证模式的环境变量。首次启动请允许本地网络；
录音时允许麦克风。

如需从 Xcode 界面查看自己的 Team：

```sh
python3 ports/ios/tools/generate_validation_project.py
open ports/ios/validation-host/CoronaPokerHostValidation.xcodeproj
```

在 Signing & Capabilities 中选择自己的 Personal Team，可查看 Team ID。
若自动签名失败，先处理 Xcode 报告的账户、设备或配置文件问题，再重试。

后续更新/续签：

```sh
# 没有改源码时无需再次 AOT 编译；重新申请配置文件并更新安装
python3 ports/ios/tools/install_ipad.py \
  --device YOUR_DEVICE_ID --team YOUR_TEAM_ID --refresh-profile

# 改源码后重新构建并安装
python3 ports/ios/tools/install_ipad.py \
  --device YOUR_DEVICE_ID --team YOUR_TEAM_ID --build --refresh-profile

# 仅在本机生成个人开发 IPA，不安装
python3 ports/ios/tools/install_ipad.py \
  --device YOUR_DEVICE_ID --team YOUR_TEAM_ID \
  --refresh-profile --no-install --package
```

Personal Team 配置文件有有效期，免费开发签名通常需要每周续签；具体以
Xcode 生成的配置文件为准。参见 [Apple 的会员与设备测试说明](https://developer.apple.com/support/compare-memberships/)。
个人开发 IPA 只适用于其配置文件授权的设备，不是通用安装包。
本仓库不发布签名 IPA、证书、配置文件或维护者设备信息。

## Architecture

```text
Original 25.86 core + libGDX desktop sources (unchanged)
    └─ prepare_game_sources.py: reviewed mobile source variants
         + shared-adapters/src: platform implementations
         + launcher/src: native app entry point
              └─ Java 17 compilation → build-time bytecode adaptation
                   └─ pinned MobiVM ARM64 AOT → CoronaPoker.app
                        ├─ UIKit / AVFoundation / Keychain
                        ├─ libGDX MetalANGLE native frameworks
                        └─ static SQLite JNI + native platform bridge
```

没有在设备上启动 JVM/JIT，也不在运行时下载或编译 Java 代码。
`prepare_game_sources.py` 对原版结构做明确检查，产生移动变体和派生记录，
不会改写 `src/`、`modules/` 或原版规则。桌面自动更新交接在移动变体中禁用；
更新移动版本需要重新编译、签名和安装。

后台逻辑与渲染分离：持续音频满足平台执行条件时，网络/牌局/超时在本机
推进；后台不向暂停的 GL 渲染循环排入持续任务，返回前台后恢复显示。
身份信任和恢复确认保持显式操作，后台不会擅自批准。

## Dependencies and runtime patches

精确下载 URL 和 SHA-256：
[config/downloads.json](config/downloads.json)。版本和背景条件：
[config/runtime-lock.json](config/runtime-lock.json)。

| Dependency | Pinned input / purpose | Upstream |
| --- | --- | --- |
| Temurin JDK | 17.0.20.1+1, macOS ARM64 host compiler and OpenJDK declarations/TZDB | [Adoptium](https://github.com/adoptium/temurin17-binaries) |
| MobiVM | 3.0.0-20260924.074901-5 AOT compiler/runtime | [MobiVM](https://github.com/MobiVM/robovm) |
| libGDX | 1.14.2, MetalANGLE iOS backend and FreeType | [libGDX](https://github.com/libgdx/libgdx) |
| Bouncy Castle | 1.86, existing crypto API platform support | [Bouncy Castle](https://github.com/bcgit/bc-java) |
| SQLite / Xerial JDBC | 3.51.1 / 3.51.1.0, static native JDBC backend | [SQLite](https://www.sqlite.org/), [Xerial](https://github.com/xerial/sqlite-jdbc) |
| JvmDowngrader | 2.0.1, build-time bytecode compatibility only | [JvmDowngrader](https://github.com/unimined/JvmDowngrader) |

MobiVM 是固定的时间戳 snapshot；仓库不假定远端会永久保留它。远端清理或
内容改变时应停止并重新审计，不能直接使用不同 snapshot。
检查过的源提交是 `6d97fcec3112d4da6e1087fd0f0bc670a24e41d8`；
编译器修补使用该提交中的 `Executor.java` 并锁定其哈希。
整个分发制品与该源提交的逐文件对应关系尚未独立验证，制品本身有固定 SHA-256。

派生脚本保留下载原件和上游许可通知，变更记录只写入忽略的 `validation/`：

- `prepare_compiler.py`：等待同步子进程输出泵结束，避免编译器读取不完整输出。
- `prepare_runtime.py`：适配该 snapshot 的 ThreadLocal/ForkJoin 字段布局、
  `ThreadLocal.withInitial`、既有 `BigInteger.TWO` 的可见性、Darwin accept
  后的阻塞 Socket 语义，以及固定 OpenJDK JDBC 类型/TZDB。
- `build_sqlite.py`：从原 Xerial JNI 层静态编译相同 SQLite 版本，禁用动态扩展加载。

下载依赖各自的许可仍适用；工具链内的 `LICENSE`/`NOTICE` 和生成源文件的
原始版权通知应保留。游戏和新移植代码的许可见 [原 GPLv3](../../LICENSE)
及 [移植声明](../../IPADOS-NOTICE.md)。

## Validation

以下是开发过程中的匿名摘要，原始设备日志、数据库和个人签名制品不公开。
### 25.86 upgrade verification (2026-10-10)

Pinned to upstream `v25.86`, commit
`15ddcfc73bdc15ed6da6bd272f9f9211e23d766f`. The desktop peer uses the
same version; the old 25.79 compatibility target has been superseded.

| Scope | Observed result |
| --- | --- |
| Official source parity | 3,432 original source/resource files match upstream v25.86; mobile variants remain generated in the port |
| Original Maven verification | 935 passed, 1 Windows-only wake-lock test skipped; no failed/error tests in the final full run |
| Original mobile boundary contracts | 250 passed, including hot-join snapshot, visual event, presentation cutover, mapper and terminal navigation contracts; only the desktop AWT-null clipboard assertion is omitted in the generated test |
| Selected upstream multiprocess certification | Both FLOP bootstrap and bootstrap-before-renderer-attachment scenarios passed; this does not claim all 21 certification scenarios were rerun |
| Port regression probes | Scene projection, exact GIF pixels/Pixmap lifetime, retained text, sound ownership, scrolling, text input, clipping and screenshot ownership passed |
| Physical offline full table | Nine bots and the native local player completed 3 hands with real computation, ALL IN, music and effects; 53 signed actions verified and amount tampering rejected; final funds 100/100 |
| Physical hot join | Joined the original desktop GUI during a paused FLOP: 3 public cards and WARMING UP were confirmed on the iPad, followed by admission at the next hand boundary; the 2 shared participating hands had identical signed records |
| Physical performance | 10,475 table frames: P99 16.80 ms, maximum 41.68 ms, 4 intervals over 33.3 ms; user reports performance comparable to the previous build with occasional slight hitch/crackle |
| Resource/lifecycle invariants | 463 preloaded Pixmaps created/disposed, retained decoded output pixels 0, hot ALL IN uploads 0, hidden table draws 0; five libgc.a hashes unchanged |
| Physical 25.86 locked hosting | Build 5: 900.02 s, 179 continuous background/music samples; 21 additional observed hands, 24 completed total, 108 identical signed actions; background CHECK 41 / FOLD 10 only; funds 20/20 |
| 25.86 foreground restore | Build 5 passed after full physical lock and desktop-peer exit: the user confirmed an upright summary, no replay of old animations, and successful log navigation. Terminal UI presentation is separated from state application; retained framebuffer UVs preserve top/bottom orientation. 38 summary-restore and 25 actual SpriteBatch corner checks pass |

The final Maven run used isolated Surefire forks and network diagnostic logging.
Earlier full runs encountered intermittent address-in-use and warming-peer exit
timeouts; their logs remain local. A clean final run does not establish those
upstream tests as deterministic. No upstream test assertions were weakened.

The performance sample is a separate randomized three-hand game, not proof that
all residual hitches were eliminated. iPhone and iOS 18 physical validation remain
unfinished. The published branch provides the tested build 5 sources and build
scripts; private device evidence, databases and personally signed binaries remain
on the developer's machine.

### Historical 25.79 evidence

下表保留 25.79 移植的历史验证；不能当作 25.86 的验收结果。

| Scope | Observed result |
| --- | --- |
| Original desktop source baseline | 3,429 files unchanged against upstream v25.79 |
| Original Maven verification | 911 tests, 0 failures/errors, 1 skipped |
| Mobile boundary contracts | 211 tests passed; one desktop AWT-null clipboard assertion excluded only in the generated mobile test |
| Native core probes | Java 17 stream/ForkJoin, Ristretto/DLEQ rejection, crypto/signatures and SQLite persistence passed |
| Desktop 25.79 interoperability | Native iPad HOST and JOIN each completed 3 hands with original desktop GUI; additional manual PC + bot game completed 4 hands |
| Full-game locked hosting build | Physical iPad remained locked for 900.13 s with audible music; 179 independent TCP samples, 22 new hands and 101 additional signed actions; own AFK actions CHECK/FOLD only |
| Locked-session integrity | Native background timer maximum observed gap about 1.018 s; original desktop and iPad action histories matched; signature/tamper checks and balance checks passed |
| Later input/media fixes | Physical keyboard, deletion/done, long-press cut/paste, avatar picker, voice send/playback, game buttons and last-hand marker passed |
| Final screenshot fix | Physical screenshot displayed in the viewer and pasted as an image into Notes; later native offline run completed 3 hands with signature/tamper and balance checks |
| Public-source checkout verification | Hash-pinned toolchains freshly extracted, official-source download exercised, Maven package and full unsigned ARM64 App build succeeded; mobile boundary/input/scissor/screenshot regression tools all passed |

15 分钟锁屏测试发生在后续键盘/语音/截图修复之前；不能据此宣称最终发布源码
重新完成了同一套 15 分钟实机测试。iPhone 和 iOS 18 实机验证尚未完成。

可自行重跑的源码工具位于 `tools/` 和 `java-probe/`。先运行 Maven `verify`
以生成原版测试类，再构建移动 Java 变体：

```sh
export JAVA_HOME="$(pwd)/ports/ios/.cache/toolchains/jdk-17.0.20.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -s ports/ios/config/maven-settings.xml verify
python3 ports/ios/tools/build_game.py --compile-only
python3 ports/ios/tools/run_boundary_tests.py
python3 ports/ios/tools/run_summary_restore_probe.py
python3 ports/ios/tools/run_surface_orientation_probe.py
python3 ports/ios/tools/run_text_input_probe.py
python3 ports/ios/tools/run_scissor_probe.py
python3 ports/ios/tools/run_screenshot_probe.py
```

验证模式需要明确设置专用环境变量；普通安装脚本不会启用它。
带验证标记的测试工具会生成局域网信息、牌局数据或日志，请留在本机。
实机锁屏测试必须使用完整游戏、实际音频和真实锁屏窗口；模拟器和短时原型
不能替代。重新评估时应记录所用源码、构建、测试窗口和中断条件。
