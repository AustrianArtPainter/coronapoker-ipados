# CoronaPoker 26.16 — native iPadOS port

Unofficial port of **[tonikelope/coronapoker](https://github.com/tonikelope/coronapoker)**,
based on **[v26.16](https://github.com/tonikelope/coronapoker/tree/v26.16)**.
The original game belongs to tonikelope and its contributors. This fork preserves
the upstream license, credits and Git history; see [IPADOS-NOTICE.md](../../IPADOS-NOTICE.md).

本移植将现有 Java/libGDX 游戏编译为原生 ARM64 App，供个人用自己的
Apple ID 签名安装。主要目标是 iPad；iPhone 实机验证目前暂停。

## Features and scope

- 加入原版 **26.16** 电脑端的房间，或在 iPad 本机创建房间。
- 在本机离线与原版机器人对战，沿用桌面布局、牌局规则和加密协议。
- UIKit 系统键盘、输入/退格/完成、长按剪切/粘贴、文件选择头像。
- 原版格式的语音消息录制、发送和回放；原生截图生成及图片剪贴板。
- 本机后台/锁屏主持：需要持续、实际可听见的游戏音乐播放。
- 自己的玩家超时仍按原规则：**可以免费 CHECK 就过牌，否则 FOLD**。
  不会替用户自动下注或加注；正常机器人仍使用原版策略。

协议严格匹配 **26.16**。不能把其他版本直接混用。完成源码生成、主机侧
边界测试和 iPad 构建验证后，仍需由用户在真机上重新验收听感、锁屏主持、
解锁恢复、电脑端双向联机、热加入、重购、输入和媒体功能。
当前最低部署目标为 iOS/iPadOS **18.0**，但这只是构建目标；尚未完成
iOS 18 实机或 iPhone 实机验证。实机结果见下文，不能推广为所有设备保证。

持续音乐播放是已验证的后台执行条件。不要静音、停止音乐或强制退出后仍
期待设备继续主持。来电、音频会话中断、系统资源限制、设备关机或系统终止
App 都可能影响后台执行。没有借助外部电脑或服务器接管主持。

当前升级分支为 `ipados-26.16`，尚未推送；以下构建命令在当前源码检出的根目录执行。

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
Original 26.16 core + libGDX desktop sources (unchanged)
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
  后的阻塞 Socket 语义、路径编码器的线程内强引用缓存，以及固定 OpenJDK JDBC 类型/TZDB。
  派生运行时字节相同时保留文件时间，实际内容变化时原子替换，避免无效的全量重编译。
- `build_sqlite.py`：从原 Xerial JNI 层静态编译相同 SQLite 版本，禁用动态扩展加载。

下载依赖各自的许可仍适用；工具链内的 `LICENSE`/`NOTICE` 和生成源文件的
原始版权通知应保留。游戏和新移植代码的许可见 [原 GPLv3](../../LICENSE)
及 [移植声明](../../IPADOS-NOTICE.md)。

## Normal foreground frame/audio investigation

Build 9 adds opt-in foreground sampling; it does not change game, audio-session,
hosting or restore behavior. Start with an uninstrumented normal-app observation
on the physical iPad, then compare the same scene with sampling enabled. This
diagnostic change is not a crackle fix or a completed physical acceptance result.

For a five-minute normal-homepage capture, launch with a fresh `CP_IOS_RUN_ID`,
`CP_IOS_SCENE_EVIDENCE=1` and `CP_IOS_EVIDENCE_SECONDS=300`. Leave
`CP_IOS_PROFILE`, `CP_IOS_DIAGNOSTICS`, `CP_IOS_PATH_STRESS`,
`CP_IOS_SCENARIO`, `CP_IOS_SCENE_SCOPES` and all isolation/probe flags unset.
Keep audible music, actual crypto/animation and the normal user save directory.
Do not attach a console, take periodic screenshots or poll the device during
this observation. Audio quality needs physical listening or independent audio
evidence; a running player and a valid frame report do not establish it.

The sampler records all foreground phases with monotonic timestamps, process
allocation/GC counters, scene-transition and pause/resume boundaries. It freezes
the bounded arrays at the end of the requested window and writes
`Documents/ios-scene-evidence.json` once from a worker. The 300-second capture
preallocates about 2.9 MiB of primitive arrays, so compare against sampling off.
Pause/resume gaps are explicitly disconnected from frame intervals. The timer
uses wall elapsed time from the first render: if the device is locked, completion
waits for the next foreground render. It is not a lock-screen hosting monitor.

`tools/collect_scene_evidence.py` performs one final container-file copy after
the window and audits freshness, configuration, scene coverage and complete
frame pairing. It never launches or installs the app. Its `--help` documents the
required launch metadata: bind the source commit, binary manifest/hash, actual
launch environment and launch UTC boundaries. Keep the metadata and collector
output in separate ignored validation subdirectories. `--audit-only` rechecks a
saved capture without accessing the device. Acceptance of capture integrity is
separate from performance and audible-glitch acceptance.

For a local Xcode simulator capture, use `--simulator SIMULATOR_UDID` in place
of `--device DEVICE_ID`. The same launch metadata, bounded window, configuration
and coverage checks apply. The collector waits locally, queries the simulator
app data container once, then copies the completed report once; it never falls
back to a physical device. Provenance and offline audits retain the simulator
platform marker, since host-Mac timing and audio do not establish physical-iPad
performance or locked-background behavior.

For separate scene captures, set `CP_IOS_EVIDENCE_START_PHASE` to `menu`,
`table` or `summary` together with the timed evidence settings. Sampling starts
only after that phase remains visible for five continuous foreground seconds;
startup prompts and preparation do not consume the requested window. An overlay,
scene change or suspension before readiness resets those five seconds. Record
`captureReadyUtcMillis` in launch metadata after observing the requested page;
the collector waits through that observation plus six seconds, the full capture
duration and write grace, then performs its usual single copy. The audit checks
the selected phase, first-foreground and sampling clock pairs and the readiness
bound. The original duration, completeness and scene-coverage thresholds still
apply, and legacy launch-timed captures retain their original behavior.

Build 10 added the default-off `CP_IOS_QUOTE_WRAP_CACHE=1` investigation control.
It reuses only the current menu quote's wrapping result; quote rotation, original
wrapping on a cache miss, text drawing, music, hosting and restore stay on their
existing paths. Compare fresh runs of the same signed binary and language, first
with this flag unset and then enabled. Keep the normal frame-capture configuration
above. The enabled run's launch metadata must declare `control: "quote-wrap-cache"`
and collection must explicitly use `--control quote-wrap-cache`; the normal audit
rejects this enabled control. Quote order remains randomized, so account for
quote-dependent allocation variation rather than treating different runs as
identical quote sequences. Reduced allocations, GC counts or long frames alone
do not establish that audible crackle has been resolved. That build's control
was not an enabled product fix or completed lock-screen acceptance.

The iOS table now retains a bounded set of short uppercase labels and their
language locales. It also uses ordered list queries for turn eligibility,
timeout-kick lookup and deck selection, avoiding temporary streams in repeated
render queries. Each eligibility query still reads the current snapshot; no
permission or action decision is cached. The desktop methods remain unchanged
and supply the exact behavioral oracle for these mobile transformations.

Build 17 also reuses pure table amount strings in a synchronized, fixed
128-entry cache keyed by the full double bits. All call sites and guards stay
unchanged; misses and non-finite inputs execute the mechanically preserved
upstream formatter. The differential/concurrency probe is
`python3 ports/ios/tools/run_table_amount_probe.py`. Its host allocation result
does not establish native frame-time or audio improvement; compare actual App
records before drawing that conclusion.

Build 11 enables the same quote-wrapping reuse by default; an explicit
`CP_IOS_QUOTE_WRAP_CACHE=0` retains the original wrapping path for rollback.
It also reduces repeated fixed-label work in the frontend and uses an iPad-local
fixed-arity `GameText.translate(String)` overload to reuse the empty argument
array. Parameterized translations retain the existing varargs implementation.
Rendering and animation continue each frame; audio-session, locked hosting and
foreground restoration code are unchanged.

The same-binary build 10 off/on/off captures showed about 2.066/0.712/1.704 MB/s
of process allocation and 54/12/49 collections in matched 240-second menu
windows. The user reported roughly one audible anomaly per minute after the
earlier change; further development and primary investigation now use the
local Xcode iPad simulator. Simulator results do not establish physical-iPad
audio quality or acceptance of physical-device gameplay, summary, background
locked hosting and unlock recovery.

The ARM64 build 11 simulator app has been built, installed and launched on a
dedicated iPad Pro 11-inch (M5) simulator with the installed iOS 26.5 runtime.
Local UI checks reached a real ten-seat bot table, saved and resumed that game,
and displayed its five-hand closing summary. Against the simulator application
classes, the existing host checks passed 63 JUnit contracts, 38 summary/restore
checks, 14 iOS audio-topology checks and the 10,000-event background lifecycle
probe. The existing quote/font probe also passed with the production default
and explicit rollback setting. These checks preserve their host/simulator scope;
they do not validate physical lock-screen execution or audible iPad playback.

Keep startup update prompts outside the steady-state frame analysis. Preserve
the original coverage audit even when a useful partial menu window falls below
the declared scene threshold. Two attempted simulator Instruments recordings
did not complete and were stopped; neither is an audio/CPU acceptance result.
The app's bounded frame sampler and one-copy simulator collector remain the
verified local evidence path. Private run metadata, unsuccessful attempts and
per-run reports belong in the ignored validation directory.

Controlled music comparisons use fresh runs of the same binary with
`CP_IOS_MUSIC_BACKEND=legacy` or `queue`, matching launch metadata `control`
and collector `--control` values `legacy-music` or `queue-music`. Normal audits
reject these explicit comparison settings. Controlled audits also require the
reported actual background backend to match; a pan fallback cannot count as a
queue result. The queue candidate currently applies only to the shared
`background_music.mp3`; default production selection remains legacy. Voice,
short cues, audio-session category and locked-hosting policy keep their existing
paths. Neither API selection nor a backend label establishes continuous audio.

`tools/run_app_audio_tap.py --inspect` verifies a specified installed simulator
App PID and executable without creating a tap. Its separate `--capture` operation
requires the inspected SHA256, a bounded duration and a fresh private directory.
After the user's audio-capture authorization, it records only that App's unique
Core Audio process object; missing or shared attribution fails without a global
or microphone fallback. PCM transport validity is independent of audio quality.
Keep source alignment, intentional stress intervals and ordinary scene captures
separate when evaluating its PCM and host timestamps.

The opt-in `CP_IOS_MUSIC_PROBE` contract/GC modes bypass ordinary gameplay and
are diagnostic-only. Scene audits reject them as normal or controlled scene
evidence. Actual music lifecycle and GC-stress results must identify their own
mode, run ID, backend and time range.

The build now selects a registered-thread collector by default. The pinned
MobiVM collector's ARM64 stack scanner already uses registered Java/callback
threads, but its task-discovery suspension path also freezes unrelated native
threads, including audio I/O. `tools/build_gc.py` selects its existing
`GC_NO_THREADS_DISCOVERY` path so suspension and resumption use that same
registered set. Java thread creation and native-to-Java callback attachment
retain the RVM registration protocol. Collection, finalization and default
marker settings remain enabled; no audio-thread name exemptions are used.

The builder verifies source archive hashes, required RVM consumer symbols,
target platforms and both policy configurations' private layouts. It creates
a private SDK clone and replaces only its two ARM64 `libgc.a` files; the
supplied SDK is never edited. The [source lock](config/gc-registered-runtime.json)
identifies the inspected MobiVM recipe's compatible fork. The distributed SDK
does not attest its native source revision, so these checks do not establish
an exact source-to-artifact identity. `build_game.py --gc-policy original`
provides an explicit rollback to the supplied collector.

Build 18 corrects an omission in the port's collector build: its explicit
`CFLAGS` previously suppressed configure's default optimization, leaving the
collector in builds 14–17 at Clang's default `-O0`. The source lock now requires
`-O2`. The builder checks real target-compiler predefines and the executed
compile command for every archive member, recording this evidence in the
isolated build manifest. Earlier performance records remain historical;
the correction alone does not prove that a particular long frame was GC time.

`CP_IOS_GC_PROBE=1` launches an isolated correctness stress probe instead of
the game. Normal scene audits reject this setting and collector environment
tuning. New reports bind the actual suspension getter, GC version and bundle
build-manifest declaration to the recorded binary manifest. Bundle declarations
are provenance checks, not independent machine-code attestation. Neither a
correctness probe nor this policy identity alone establishes continuous audio
or acceptable frame timing.

For focused foreground attribution, `CP_IOS_AUDIO_CLOCK_SECONDS=180` enables
a bounded background-music clock recorder in the normal game. It requires an
explicit `CP_IOS_RUN_ID` and a run-specific trigger in the App's Documents
directory. Sampling runs from the existing Java render entry, under the same
music monitor as playback controls; it adds no native-to-Java callback or
sampling thread. The trace records player/device positions, Mach timestamps
and actual playback-control events. Render-driven observations have finite
resolution: a smooth player clock cannot rule out a downstream decoder, I/O
or capture fault. These instrumented runs are rejected as normal scene
acceptance evidence, including when the launch declaration omits the flag.

Scene evidence also records bounded foreground projection-delivery timings:
monitor acquisition, event presentation and process GC counters. These timings
help account for long frame intervals outside the delegate's render call;
process GC counts alone do not measure stop-the-world duration or identify
the allocating thread. Both diagnostics are inactive by default.

Build 19 adds `CP_IOS_FRAME_ATTRIBUTION=1` for the remaining render hitches.
It requires timed scene evidence and records bounded render-stage wall time
and `CLOCK_THREAD_CPUTIME_ID` on the main thread. At most 256 long-render or
long-interval events retain stage values; absent or failed measurements are
explicitly null. Without the flag, stage arrays and native CPU-clock calls
are absent. CPU time excludes other threads, including GC helpers; wall time
minus thread CPU indicates off-CPU time, not a measured GC, GPU or blocked
duration. Use `collect_scene_evidence.py --control frame-attribution`; normal
audits reject both the launch flag and an enabled recorder. Instrumented
results do not establish normal performance or continuous audio.

Build 20 separates begin-GC-counter reads, shell prefix/tail and keyboard fitting
in the opt-in recorder (`bounded-main-thread-attribution-v2`). Existing stage
numbers remain stable; four stages are appended, for 20 stages and 113,280 bytes
of primitive storage. The begin-counter stage includes observation overhead;
end-counter reads remain after the render endpoints and are not timed. These
buckets must not be reported as measured GC suspension durations. Ordinary
rendering reuses a four-float safe-area transfer buffer while still querying
UIKit each frame, and skips the scenario service lookup when QA is disabled.
Avatar lookup scans the same fresh lobby snapshot by index, preserving loading,
cache, fallback and retry behavior while avoiding iterator allocation. This
also applies to the ten avatar lookups before a cached summary is replayed.
These allocation reductions do not themselves establish that long frames are
eliminated.

For Audio System Trace, prefer the locally verified Deferred configuration:
`python3 ports/ios/tools/prepare_audio_trace_template.py --output ports/ios/validation/PRIVATE/Audio-Deferred.tracetemplate`
reads the installed Xcode template (or `--source`), preserves its instruments,
`recordingMode=2`, `supportsImmediateMode=false` and time limit, and changes only
the rolling `windowLimit` from the stock five seconds to zero. It creates a new
private template plus a hash/configuration manifest and refuses existing outputs;
it never edits the Xcode original or starts a recording. Keep these artifacts in
ignored validation directories. First attach with this template and
`--instrument 'Time Profiler' --time-limit 30s`, without a rolling-window flag,
and verify the actual recording mode and exported table timestamps/row coverage
before a formal capture. A requested rolling window is a retention ceiling, not
an assurance that all earlier data survived system buffers; see the
[Apple Instruments engineer's explanation](https://developer.apple.com/forums/thread/745016).
Template preparation and command success alone do not establish trace completeness.

Host integrity checks:

```sh
python3 ports/ios/tools/run_frame_evidence_probe.py
python3 ports/ios/tools/test_scene_evidence.py
```

## Validation

以下是开发过程中的匿名摘要，原始设备日志、数据库和个人签名制品不公开。

### 26.16 upgrade verification (2026-10-16)

The port is pinned to upstream `v26.16`, commit
`0c0830d3adaccbec5f3f23ba3adc092ec44df8a1`. The upgrade carries the official
menu material, language selector, vector icon and corrected Spanish/English
quote changes while preserving the iPad platform, audio, lifecycle and
allocation adaptations. All 3,470 captured upstream files match the new tag,
and the preserved upstream README matches the tagged file byte for byte.

The clean official regression completed 1,059 tests with zero failures or
errors and one upstream skip. One network-throttle case first crossed its
timeout boundary, then passed both an isolated retry and the complete clean
rerun. The regenerated mobile sources passed 338 Java 8 boundary tests and 238
cryptographic tests. Menu quote/cache, scene and vector geometry, table query
and amount caches, uppercase conversion, audio ownership, summary restoration,
touch scrolling, screenshot ownership, acceptance-evidence and native GC build
checks also pass.

Both ARM64 targets build successfully from the upgraded sources. The simulator
and physical-device bundles report version 26.16/build 22, contain the official
26.16 assets, and retain the registered native GC suspension policy used to
protect audio playback. The physical-device bundle was signed successfully with
the existing local development profile, installed over the previous build on the
paired iPad Pro 11-inch (M5), and launched normally. The device's installed-App
query confirms version 26.16/build 22; the signed executable SHA256 is
`0ae122da9572b95c0f3218af158781fae6fb561495b988dd3c1dbbaa6e472667`.
Mullvad LAN sharing was temporarily allowed to restore the Xcode device channel
and then restored to its previous `block` setting. Automated checks establish
source and build compatibility; the physical iPad remains the acceptance
authority for perceptible crackle, stutter and frame-rate fluctuation.

### 26.15 upgrade verification (2026-10-16)

The port is pinned to upstream `v26.15`, commit
`db50aa8c0a6b7a7e631ba172092b3c176f9989a9`. All 3,470 captured upstream
source, resource and build-tool files match the 26.15 baseline, and
`git diff v26.15 -- src modules tools coronaupdater .mvn pom.xml` is empty.
The preserved upstream README also matches the tagged file byte for byte.

Both ARM64 targets build successfully from the upgraded sources. The simulator
and physical-device bundles report version 26.15/build 21, contain the official
26.15 assets, and retain the registered native GC suspension policy used to
protect audio playback. The physical-device bundle was signed successfully with
the existing local development profile, installed on the paired iPad Pro
11-inch (M5), and launched normally. The installed-App query confirms version
26.15/build 21. Mullvad LAN sharing was temporarily allowed to restore the Xcode
device channel and returned to its previous blocked state after installation.
The user's listening/interaction acceptance on the iPad remains pending.

The official core/GDX Maven suite completed 1,057 tests with zero failures or
errors and one skip. The Java 8 mobile boundary suite completed 338 tests, the
mobile cryptography suite completed 238 tests, and the acceptance-evidence suite
completed 32 lifecycle cases. Scene lifecycle, final-summary restoration,
audio ownership, touch scrolling, screenshot ownership, GC build flags,
menu allocation caches, cinematic preload and table allocation probes also pass
against the regenerated 26.15 mobile sources. These automated checks establish
source/build compatibility; the physical iPad remains the acceptance authority
for perceptible crackle, stutter and frame-rate fluctuation.

### 26.9 build 20 physical-iPad handoff (2026-10-15)

The ARM64 physical-device build from source `a63dc7b0` was signed, installed
over the existing App and launched normally on the iPad Pro 11-inch (M5),
iPadOS 26.4.2. The device's installed-App query confirms version 26.9/build 20.
Diagnostic launch overrides were removed. Temporary Mullvad LAN access was
restored to its previous setting after installation.

At the user's request, final acceptance is their own listening and interaction
on the physical iPad: whether crackles, stutter or accompanying frame-rate
fluctuations are perceptible at home, during play and on the summary screen.
Simulator measurements remain supporting development records, not the final
acceptance criterion. Further simulator captures were stopped; the user's
physical-device verdict is pending. Do not describe installation success as
confirmation that every symptom is resolved.

### 26.9 builds 18–19 follow-up (2026-10-15)

The registered collector builder now explicitly compiles and verifies `-O2`.
Earlier builds 14–17 accidentally inherited an unoptimized native collector
after the build recipe overrode configure flags. Build 18 corrects that mistake;
the original SDK is unchanged. Its executable SHA256 is
`9e7877aa8f1d317908670e8ebf4e675b92c3831a63eff46aa7bb56eeb824ef3a`.

Three normal simulator runs retained the original five-second warm-up, 180-second
complete frame/PCM overlap and 80% scene-coverage gates, without the CPU-stage
diagnostic. Independent source-alignment review found no new silence, sustained
skip/repeat or independently confirmed click in these finite audio windows.
Mixed game effects still create source-matching blind regions.

| Scene / private run ID | Complete steady frame/PCM overlap | Frame interval p99 / maximum | Intervals >25 / >33.3 ms |
| --- | --- | --- | --- |
| Home / `sim18-menu-final-01` | 288.583 s | 17.299 / 49.780 ms | 2 / 1 |
| Ten-player game / `sim18-table-final-01` | 313.467 s | 17.238 / 37.197 ms | 5 / 2 |
| Real terminal summary / `sim18-summary-final-01` | 300.800 s | 17.300 / 66.059 ms | 2 / 1 |

The full frame records also contain a 92.404 ms home frame and a 547.402 ms
summary frame before their PCM windows. Both have steady-state flags and are
preserved as unexplained outliers, not classified as cold start or removed from
the raw evidence. Their audio behavior is unknown. Therefore this work does not
yet establish that all audio faults and frame hitches are resolved.

On the same build 18 process, locked hosting advanced from observed hand 6 to
hand 11 across the lock/unlock sequence and restored the live table. A 60-second
audio capture lay wholly inside the approximately 224-second confirmed lock
window; its zero region matches the original music's loop lead-in. This is a
finite simulator regression, not physical-device endurance acceptance. Fresh
host checks passed 14 audio-topology cases, 38 summary-restore cases and the
lifecycle suite with 10,000 background events and 52 projection fields.

Build 19 adds diagnostic attribution without changing the default audio policy.
Its real-summary run `sim19-summary-attr-01` retained 21,596 valid main-thread CPU
frames. Two long renders took 70.877 / 47.016 ms wall time and 59.655 / 41.338 ms
CPU time, with GC-count growth. Summary cache prepare/replay was brief and no
summary repaint occurred in those events. Most time fell in the then-combined
entry and prelude buckets. The runtime's GC counter getters acquire the GC
allocation lock, so those measurements do not distinguish game work from
counter-observer cost. Build 20 splits those paths before further causal claims.
Neither diagnostic run is normal performance or audio acceptance evidence.

### 26.9 build 16 audio/frame verification (2026-10-15)

Product changes are committed through
`610146f35188f3e84029d0452bb0fc7cfc60bcf0`: registered-thread GC suspension,
bounded table uppercase/locale reuse, and ordered table queries without the
observed per-frame stream allocations. Java collection remains enabled. The
native collector change addresses the confirmed mechanism that suspended
unrelated native audio I/O threads during Java collection; it does not remove
Java stop-the-world pauses or guarantee continuous output throughout the OS.

The following are finite, normal-game observations on the local ARM64 iPad
simulator (iOS 26.5), not physical-iPad acceptance. All three runs used the same
build 16 executable, SHA256
`4fd1666febd788a87fc9322c7d527454273a5924a3b6ac54e618195fb781e2db`, the default
AVAudioPlayer backend and registered collector, with the music-clock diagnostic
disabled. Exact-App audio capture excluded microphones and other applications.
Five-second warm-up gates, at least 180 seconds of complete frame/PCM overlap,
and the original 80% scene-coverage requirement were retained.

| Normal scene | Private run ID | Complete steady frame/PCM overlap | Frame interval p99 / maximum | Intervals >25 / >33.3 ms | Independent PCM review |
| --- | --- | --- | --- | --- | --- |
| Home idle | `sim16-menu-final-03` | 213.601 s | 17.304 / 22.932 ms | 0 / 0 | 270.016 s; no zero samples or sustained source-position step; three residual candidates explained by source transients and sub-sample alignment |
| Ten-player game | `sim16-table-final-02` | 305.017 s | 17.301 / 50.010 ms | 9 / 5 | 360 s; no zero samples; the small phase candidate did not persist in independent high-correlation matches; mixed effects limit reference-only click classification |
| Real terminal summary | `sim16-summary-final-01` | 299.601 s | 18.224 / 25.682 ms | 1 / 0 | 360.011 s; only 17.771 ms of zeros at the capture start, outside the steady gate; no subsequent zero frames or sustained source-position step |

These results support improvement and completed simulator regression windows,
not a claim that every crackle or hitch has been eliminated. The home maximum
above is steady-state: its full record also contains an 83.439 ms startup frame.
The game still has occasional long frames. Source matching has blind regions
when normal effects mix with music; residual-detector counts are not counts of
audible crackles. Capture drift readback was explicitly composition-only: the
private tap configuration was verified, not the HAL resampler implementation.

Earlier build 16 evidence remains unresolved and is not overwritten: one game
capture recorded a 201.042 ms zero region and an approximately 111-sample source
step. Its simultaneous frames stayed at 16.038–17.593 ms, without a GC or long
frame near the event. A separate locked capture contained a 53.333 ms phase-step
candidate. Later normal-game and two simultaneous exact-App 300-second captures
did not reproduce these events. The latter also had 240 seconds of bounded
player-clock/control evidence with stable playback and volume. Non-reproduction
does not establish whether the earlier anomalies arose in the App, decoder,
native I/O or capture graph.

Functional locked hosting passed on the same build 16 process: after about
260 seconds locked, the game advanced from hand 8 to hand 11 and unlocking
restored the live table. A 60-second audio record was wholly inside the locked
window, with the phase candidate noted above; this is not a claim of flawless
locked audio or a 900-second physical-device endurance pass. Fresh host checks
against unchanged build 16 classes passed 14 audio-topology cases, 38 summary
restoration cases, and the lifecycle suite with 10,000 background events and
52 projection fields. Raw recordings, reports, hashes and failed setup attempts
remain in ignored local `validation/` directories.

### 26.9 upgrade verification (2026-10-14)

Pinned to upstream `v26.9`, commit
`2618d08d8ee451fa43b9b2685c885d6ad585d8e8`; native build 8 signed and installed.
All official product sources remain unchanged. The upgrade is local and has not
been pushed. Older evidence below is version-specific.

| Scope | Observed result |
| --- | --- |
| Official source parity | 3,464 original source/resource/build-tool files match the captured v26.9 baseline; `git diff v26.9 -- src modules tools coronaupdater .mvn pom.xml` is empty |
| Original desktop build | Offline pinned-toolchain Maven package succeeded |
| Original Maven verification | Final full verify: 1,035 tests, 0 failures/errors, 1 Windows-only skip across 157 suites. The earlier terminal-state wait timeout and its isolated passing rerun remain recorded; assertions and official code were not modified |
| Original mobile boundary contracts | 327 passed, including admission/traffic guards, hot join, state projection, spectator rebuy and network block controls; classes are verified to load from the mobile build |
| Original mobile crypto contracts | 238 passed against a complete mobile class snapshot, with unchanged mathematical/attack assertions |
| Port regression probes | 40 touch/scroll/lock-input checks, 10 text-input checks, 14 audio-topology checks, 70 scene/projection checks, 38 summary-restore checks and 25 framebuffer-orientation checks passed; clipping, screenshot/PNG, sound, evaluator and cinematic probes passed |
| Native ARM64 App | Build 8 compiled, signed and installed; 984 application classes have classfile major 52. The intro bridge passed 3,269,295 exact seeded draw checks. Earlier failed startup/native-ICU runs are retained and excluded from acceptance |
| Physical path/GC stress | Three threads completed/joined: 4,608 Unicode path roundtrips, 6,912 malformed-path rejections and 48 collection requests; actual RoboVM GC count advanced from 7 to 9. The per-thread encoder cache retains path normalization and REPORT behavior; the earlier corrupted ICU callback's exact GC/free timing remains unobserved |
| Physical offline nine bots | Three completed hands, 72 valid signatures and 72 rejected amount mutations; all ten players retain 30 balance rows, funds 100/100. 45 USB and 48 independent TCP observations passed freshness and real compute checks; no mock/silent/instant mode |
| Official desktop hosts / iPad joins | SHA-verified official 26.9 JAR completed five hands. iPad joined during hand 1 FLOP: 27 warming samples with three public cards, then admitted exactly at hand 2. Native/desktop lifecycle audit passed. All 36 record/signature pairs and betting/balance fields of shared hands 2–5 match; each shared hand conserves 30/30 funds |
| iPad hosts / official desktop joins | Three hands completed normally with funds 20/20; 42 independent TCP observations passed. Both complete databases contain the same 17 valid signatures, with all record/signature pairs, betting fields and per-player balances identical; run/time/roster and 605 continuous-event checks passed |
| Physical acceptance still pending | Spectator rebuy, native keyboard/touch/avatar/voice/screenshot/clipboard, and at least 900 seconds of continuously audible music under physical lock followed by foreground/summary/log restoration. Current foreground telemetry has not confirmed audible playback; historical 25.86 lock evidence does not establish 26.9 acceptance |

The 26.9 UI keeps the original seat ring, vector summary navigation and rebuy
eligibility. Mobile-generated variants retain native keyboard and media,
separate static geometry from live seat motion, and clear held input on lock.
Audio route enumeration stays off the render update thread.

Private signing, devices, databases, telemetry and personally signed binaries
remain ignored. Test launchers are opt-in; normal installation starts the user's
regular game. This upgrade does not claim migration of old unfinished games.

### Historical 25.86 upgrade verification (2026-10-10)

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
mvn -s ports/ios/config/maven-settings.xml -Dmaven.repo.local="$(pwd)/.m2/repository" verify
python3 ports/ios/tools/build_game.py --compile-only
python3 ports/ios/tools/run_boundary_tests.py
python3 ports/ios/tools/run_crypto_tests.py
python3 ports/ios/tools/run_scene_probe.py
python3 ports/ios/tools/run_scroll_probe.py
python3 ports/ios/tools/run_audio_topology_probe.py
python3 ports/ios/tools/run_intro_random_probe.py --native-jar ports/ios/build/game/native.jar
python3 ports/ios/tools/status_freshness_probe.py
python3 ports/ios/tools/run_acceptance_evidence_tests.py
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
