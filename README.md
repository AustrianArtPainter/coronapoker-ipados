# CoronaPoker iPadOS — 25.86

Native ARM64 iPadOS port of **[tonikelope/coronapoker](https://github.com/tonikelope/coronapoker)**.
**The original CoronaPoker game is developed by tonikelope and its contributors.**
This is an unofficial GPL fork, based on upstream **v25.86**; it preserves the
original source history, game rules, desktop layout and networking protocol.

本仓库将 Java/libGDX 版 CoronaPoker **25.86** 移植为可安装的原生 iPad App。
复用原版代码，通过 MobiVM 提前编译为 ARM64；在设备上不需要 JVM 或 JIT。

- 加入原版 25.86 电脑端房间，在 iPad 本机开房，或离线与机器人对战。
- 保持桌面布局，补齐系统键盘、触控、头像文件选择、语音及截图剪贴板。
- 持续播放实际可听见的游戏音乐时，25.86 build 5 已通过本机 15 分钟锁屏主持、
  原版电脑端联机及解锁后的结算恢复验证。
  音频中断、强制退出和系统终止会影响这个条件。
- 最低部署目标为 iOS/iPadOS 18.0；**iPhone 及 iOS 18 实机验证尚未完成**。

公开内容是源码、构建/签名脚本及说明。请使用自己的 Apple ID 和设备编译签名；
仓库不提供个人签名 IPA、证书、配置文件、设备编号或私人测试数据库。

Current iPad branch: **`ipados-25.86`** (native build 5).
The previous `ipados-25.79` branch remains available for historical builds.

This update includes animation preloading, event-driven table rendering,
touch scrolling, and fixes for summary retention and orientation after unlocking.
The nine-bot full-table test still has occasional very slight hitch/crackle;
the measured results and remaining validation limits are documented below.

## Build and install

需要 Apple Silicon Mac、完整 Xcode、Python 3.12+ 和 Maven 3.9+。
已验证构建环境：Xcode 26.6 / iOS SDK 26.5。

```sh
git clone --branch ipados-25.86 https://github.com/AustrianArtPainter/coronapoker-ipados.git
cd coronapoker-ipados
python3 ports/ios/tools/bootstrap.py
python3 ports/ios/tools/build_game.py
python3 ports/ios/tools/install_ipad.py \
  --device YOUR_DEVICE_ID --team YOUR_TEAM_ID --refresh-profile --launch
```

完整环境准备、免费开发签名续期、架构、依赖校验、后台条件和验证边界：
**[iPadOS build and validation guide / 构建与验证说明](ports/ios/README.md)**。

## Upstream and license

Original repository: **[tonikelope/coronapoker](https://github.com/tonikelope/coronapoker)**.
Upstream base: **[v25.86](https://github.com/tonikelope/coronapoker/tree/v25.86)**,
commit `15ddcfc73bdc15ed6da6bd272f9f9211e23d766f`.

The original game sources and Maven modules remain unchanged. Port-specific code
is in `ports/ios/`; mobile variants are generated during the build. Desktop
players must use **25.86** for the original strict version handshake.

This fork retains the upstream **[GNU GPLv3 license](LICENSE)** and all existing
credits. New port code is GPL-3.0-or-later; third-party files retain their own
notices. See **[iPadOS attribution and modification notice](IPADOS-NOTICE.md)**.
The original project description is preserved in **[README.upstream.md](README.upstream.md)**.

This port is maintained in [AustrianArtPainter/coronapoker-ipados](https://github.com/AustrianArtPainter/coronapoker-ipados)
and is not an official upstream release.
