# Upstream attribution and iPadOS modification notice

**CoronaPoker is originally developed by [tonikelope](https://github.com/tonikelope).**
Original project: **https://github.com/tonikelope/coronapoker**.

This repository is an **unofficial iPadOS port**, maintained at
[AustrianArtPainter/coronapoker-ipados](https://github.com/AustrianArtPainter/coronapoker-ipados).
It does not imply upstream endorsement. The game rules, desktop interface,
artwork, audio and networking originate from the upstream project and retain
their existing authorship and notices.

The port is based on upstream **v25.86**, commit
`15ddcfc73bdc15ed6da6bd272f9f9211e23d766f`. The original game sources and Maven
modules are retained. iPad additions live in `ports/ios/`; mobile variants are
generated during the build rather than overwriting the desktop sources.
The original README is preserved as [README.upstream.md](README.upstream.md).

Modification date: **2026-10-10**. Additions include the ARM64 AOT launcher,
native platform adapters, build-time runtime/compiler adaptations, background
presentation handling, touch/keyboard and media integration, signing/build
scripts, event-driven rendering and animation preloading, foreground summary
restoration and orientation fixes, and port-specific validation tools and
documentation. The GC native libraries retain their pinned upstream hashes.

The upstream [GNU GPL version 3 license](LICENSE) remains intact. New port code
and build scripts are provided under **GPL-3.0-or-later**, consistent with the
upstream source notices, except where a file carries its own third-party notice.
Existing copyright, license, asset and attribution notices must be retained.

Third-party build inputs are fetched from their public upstream locations and
verified with the hashes in [downloads.json](ports/ios/config/downloads.json).
Their licenses and notices remain applicable: the MobiVM distribution includes
its compiler license and separate runtime/module licenses (including Apache 2.0
and GPL with Classpath Exception material); OpenJDK source adaptations retain
their original notices; libGDX, MetalANGLE, Bouncy Castle, Xerial SQLite JDBC,
SQLite and JvmDowngrader retain their respective upstream terms. The downloaded
toolchains and native binary dependencies are not committed to this repository.
See [dependency and runtime notes](ports/ios/README.md#dependencies-and-runtime-patches)
for the source links and derivation scripts.

No personal signing certificate, provisioning profile, signed IPA, device ID,
private test database, recording or raw device log is distributed here. Each
builder signs and installs with their own Apple ID and connected device.
