# CoronaPoker iPadOS — 27.90 (build 23)

Native ARM64 iPadOS port of [CoronaPoker v27.90](https://github.com/tonikelope/coronapoker/releases/tag/v27.90), developed by tonikelope and contributors. This unofficial port shares the desktop game's Java/libGDX code and network protocol.

这个版本已在 iPad Pro 11 英寸（M5）上安装，玩家已完成真机试用。联机时请使用 **27.90** 电脑端。iPad 可以加入电脑主持的房间、自己主持房间，或离线与机器人对战。

- 主页、等候室和牌桌均可打开游戏内 YouTube 窗口。
- 交互统计可查看以往牌局。聊天图库支持图片搜索和收藏，牌桌内可阅读随游戏附带的规则手册。
- 有声锁屏主持需要持续播放可听见的游戏音乐。本版已在模拟器与原版电脑端的联机测试中验证锁屏后牌局继续、解锁后恢复结算页。

## Build and install

在 Apple Silicon Mac 上安装 Xcode、Python 3.12+ 和 Maven 3.9+，使用自己的 Apple ID 给 App 签名。最低部署目标为 iPadOS 18.0。构建和设备准备步骤见 [iPad 构建与安装指南](ports/ios/README.md)。

```sh
git clone --branch ipados-27.90 https://github.com/AustrianArtPainter/coronapoker-ipados.git
cd coronapoker-ipados
python3 ports/ios/tools/bootstrap.py
python3 ports/ios/tools/build_game.py
python3 ports/ios/tools/install_ipad.py --device YOUR_DEVICE_ID --team YOUR_TEAM_ID --refresh-profile --launch
```

The game and port retain their upstream credits and GPL license. See the [original project README](README.upstream.md), [iPadOS attribution](IPADOS-NOTICE.md), and [license](LICENSE).
