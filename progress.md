# Progress

## 当前 APK 能力边界（重要）

- 已经可以聊天/传文件：主机 App + 浏览器客户端（Web 聊天页）。
- 主机 App 的「进入聊天」是 WebView 打开本机服务器，主机自己也是客户端。
- 其他手机/iPhone 用浏览器打开 `http://主机IP:8520/`，不需要装 App（当前版本）。
- **未完成**：前台服务/后台保活、安卓原生加入 + 相机扫码、PIN/自动发现、完整压测。
- 主机 App 当前需保持打开/前台；FGS 加完后才能可靠后台运行。

## 2026-10-20 · Phase 0–3 首版

- Phase 0 完成：三模块工程、Gradle/AGP/Kotlin/Compose、compileSdk 37、APK 构建；用户真机 health 验证通过。
- Phase 1 完成：`ChatEngine`、JSONL 日志、文件存储、房间 epoch；JVM 测试 5/5 通过。
- Phase 2 完成首版：Ktor HTTP/WS、Web 聊天页、上传/下载/Range、安全头、Origin 校验。
- 浏览器 E2E（Puppeteer + Chrome）通过：
  - 3 个独立浏览器上下文加入，用户数 3
  - 文字互发并显示发送者/时间
  - 文件上传，双方看到文件卡片，下载返回正确内容
  - 刷新/自动重连后快照恢复历史
  - 修复 `.bubble` CSS 冲突后复验
- Phase 3 最小版：
  - MainActivity 启动 `LanServer`（8520）、二维码、LAN URL、在线人数
  - ChatActivity WebView 加载本机聊天页；文件选择器、DownloadManager、返回退出
  - 18MB debug APK；归档到 `APK输出/茶室/2026-10-20_102413/`
- 测试工具/临时目录已清理（Puppeteer/ws/Chrome profile/npm cache）；保留 `/tmp/toutouliao-sdk` 与 `/tmp/toutouliao-gradle-home` 供下次构建。
- 下一步：用户真机安装 APK 验证聊天/文件；通过后加前台服务与原生加入/扫码。

## 2026-10-26 · 局域网连接问题 / 诊断版

- 用户反馈：主机 App 显示房间已开启、主机 WebView 能聊天；但 iPhone/另一台设备打开 IP 时浏览器提示无法连接。
- 已确认：主机本机聊天正常 → Ktor 已在运行；问题在“局域网访问”这一段（IP 选错、端口、VPN/多网卡、AP 隔离），不是聊天 UI。
- 关键差异：Phase 0 健康检查用 18520，正式聊天版用 8520；旧地址/旧书签会连不上。
- 已做 `0.1.1-diag` 诊断版：
  - 通过 `ConnectivityManager` + `NetworkInterface` 列出所有 IPv4 候选；VPN/移动网络仅展示不推荐，默认优先 Wi-Fi/热点/以太网。
  - 显示网卡名、私有/非私网、推荐地址；网络变化和回到前台自动刷新。
  - 增加 `127.0.0.1:8520` 和当前 IP:8520 的本机 health 自检。
  - 增加“系统浏览器测试”（用主机自带浏览器打开 LAN URL）。
  - 增加“复制诊断信息”。
  - 旧端口提示；ChatActivity 也保持亮屏。
- APK：14MB，versionCode 2 / 0.1.1-diag；归档 `APK输出/茶室/2026-10-26_113106_diag/`。
- 构建环境：`/tmp/toutouliao-sdk` 的 Android 37 platform/build-tools 曾被清空，已从 dl.google.com 重新补齐（license 已接受）；未下载模拟器。SDK 压缩包已删，保留 SDK 目录。
- 待用户回报：默认地址、本机自检结果、系统浏览器测试、另一台设备 `/api/health`、复制诊断文本。

## 2026-10-27 · 0.1.5：自动发现 + 扫码加入

- 版本重置为 0.1.5 / versionCode 7；旧版归档保留。
- `core/discovery`：UDP 18522 room beacon/probe、广播地址枚举、hostId 去重、7 秒 TTL；新增 7 个 JVM 测试。
- App 拆分为首页 `MainActivity`、主机 `HostActivity`、加入 `JoinActivity`、扫码 `ScanQrActivity`。
- 主机开房后启动 `LanBeaconPublisher`；加入端 `LanBeaconScanner` + `MulticastLock` 自动列房间。
- 加入端支持扫码、手动地址、`/api/health` 校验；扫码用 CameraX + ZXing。
- Manifest 增加 CAMERA、CHANGE_WIFI_MULTICAST_STATE、相机 optional feature。
- 保留 Web 键盘布局修复；版本化 APK `茶室-v0.1.5-vc7-*.apk`。
- 构建环境：Google Maven 直连 TLS 偶发被断开，settings 增加 Aliyun Google Maven 镜像，Google 仍为 fallback。
- 待真机验收：两台设备自动发现/加入；多设备扩展；扫码权限拒绝兜底。

## 2026-10-28 · 0.1.5.2（未构建）

- Web 聊天页右上角二级菜单：文件、清除聊天记录。
- 文件库独立于消息日志：7 天保留、启动/定时/查询清理、右滑延期 7 天。
- 清除聊天记录只清消息和文件卡片，文件库/物理文件保留；下载改为按文件库解析。
- 新增 `/api/library`、`/api/library/{id}/extend`、`/api/room/clear`、WS `files-changed`。
- `:core:test` 通过；devserver 浏览器验证菜单/文件页/右滑/清除历史；未构建 APK、未创建 Release。

## 2026-10-28 · 0.1.5.3（未构建）

- 设计并落地茶室 App 图标：深茶绿底、奶油色茶杯、聊天气泡尾巴、薄荷色热气。
- 新增自适应图标、圆形图标、Android 13 单色主题图标；前景按安全区缩放。
- 新增 `docs/icon/` 预览与设计说明。
- `xmllint` 与 `aapt2 compile` 校验资源；未构建 APK、未创建 Release。

## 2026-10-28 · 0.1.6（构建）

- 接入 io.github.kyant0:backdrop:2.0.0（Kotlin 2.3.21 / Compose 1.11 兼容）。
- 新增 `ui/glass/TeaGlass.kt`：环境光背景、GlassCard、GlassButton、GlassSurface。
- 首页、加入房间、主机控制台改为原生 Liquid Glass UI；Web 聊天页不变。
- 构建 versionCode 8 / 0.1.6；Release 只写更新日志，不上传 APK。

## 2026-10-28 · 0.1.7（构建）

- Web 聊天页加入 CSS 液态玻璃：顶栏、输入栏、菜单/文件/加入面板；旧浏览器回退实色。
- 聊天页加入环境漂移、消息入场、面板/弹窗进场、发送按钮按压等轻量动画。
- HostActivity 增加 HostKeepAliveService：前台服务通知 + PARTIAL_WAKE_LOCK + Wi-Fi 高性能锁。
- Android 13+ 请求通知权限；构建 versionCode 9 / 0.1.7；Release 只写更新日志。

## 2026-10-28 · 0.1.7.1（未构建）

- 回退 Web 聊天页液态玻璃和动画，恢复 `a258fb4` 的 Web CSS；文件库/清除聊天等功能保留。
- 保留原生 Liquid Glass 与主机前台服务保活。
- 未构建 APK、未创建 Release。

## 2026-10-28 · 0.1.7.2（未构建）

- 移除原生 Liquid Glass 和 `backdrop` 依赖；首页/加入/主机/扫码恢复 Material UI。
- 保留主机前台服务保活、文件库、清除聊天、茶杯图标。
- Web 保持无玻璃/无动画版本；未构建 APK、未创建 Release。
