# Findings

## 2026-10-20 · 当前 APK 可聊天

- 主机 App + WebView/浏览器客户端已经可以聊天和传文件。
- 主机自己通过 WebView 打开 `http://127.0.0.1:8520/?name=房主&embed=1`；其他设备浏览器打开 `http://主机IP:8520/`。
- Web UI 是唯一聊天实现；原生只负责服务器、控制台、二维码、WebView、文件选择/下载桥接。
- 当前没有前台服务：主机 App 需保持打开/前台；锁屏/后台不保证存活。
- 当前没有原生「加入房间」/扫码：其他安卓先用浏览器。

## 2026-10-20 · E2E 证据

- `:core:test` 5/5 通过（文本持久化、文件元数据、重复 clientId、clear、after）。
- 浏览器 E2E（Puppeteer + 系统 Chrome）：
  - 3 个独立上下文：浏览器A/B/C，用户数 3。
  - A 发文字，A 收到自己的 echo；B 发文字，A 收到。
  - B 上传 `hello.txt`，A/B 都看到文件卡片。
  - 下载 URL `/api/files/<id>/hello.txt` 返回 200 和正确内容。
  - C 首次进入显示昵称面板，输入昵称后加入。
  - 消息发送者/时间在修复后可见。
- 截图：`docs/screenshots/web-chat.png`。

## 2026-10-20 · 已修复问题

| 问题 | 根因 | 修复 |
|---|---|---|
| 消息气泡位置错乱、发送者/时间被盖住 | 加入页 logo 用了 `.bubble` 全局类，`position:absolute` 命中消息气泡 | 限定为 `.join-mark .bubble*` |
| Chrome 404 控制台错误 | 缺 favicon | `<link rel="icon" href="data:,">` |
| 编译需要 compileSdk 37 | Compose 1.12.1/core 1.19.1 AAR 元数据 | 临时 SDK 37 + compileSdk 37 |
| Kotlin daemon 沙箱写 home 失败 | 客户端 marker 写 `~/Library/Application Support` | in-process 编译 |

## 构建/交付事实

- APK：`app/build/outputs/apk/debug/app-debug.apk`（18MB，debug 签名）
- 归档：`APK输出/偷偷聊/2026-10-20_102413/app-debug.apk`
- 包名/标签：`com.vertin.teatalk` / 偷偷聊；minSdk 26 / targetSdk 36 / compileSdk 37
- 端口：8520；二维码内容：`http://<LAN IP>:8520/`
- 服务端根：`filesDir/toutouliao/{meta.json,messages.jsonl,files,tmp}`
- 测试工具已清理：`/tmp/toutouliao-e2e`、`/tmp/toutouliao-ws-test`、npm cache、Chrome profile。
- 保留：`/tmp/toutouliao-sdk`（496MB，Android 37 编译平台）、`/tmp/toutouliao-gradle-home`（约 990MB，Gradle/依赖缓存）。

## 下载/缓存清单

| 路径 | 大小 | 用途 | 状态 |
|---|---:|---|---|
| `/tmp/toutouliao-sdk` | 496MB | Android 37 platform + build-tools | 保留 |
| `/tmp/toutouliao-gradle-home` | 990MB | Gradle 9.8.1 + 构建依赖 | 保留 |
| `/tmp/toutouliao-android-home` | 4KB | debug keystore | 保留 |
| 测试工具/e2e | — | Puppeteer/ws/Chrome profile | 已删除 |
| 模拟器下载缓存 | 945MB | emulator + system image | 已删除 |

## Ktor 3.6 API 事实

- `EmbeddedServer.start(wait)` / `stop()` 非 suspend。
- `respondOutputStream` 没有 contentLength 参数；大文件下载用 `OutgoingContent.WriteChannelContent` 自定义 `contentLength`。
- WebSockets 配置字段：`pingPeriodMillis`、`timeoutMillis`、`maxFrameSize`。
- `receiveChannel().toInputStream()` 可用（`ktor-utils` JVM）。

## 设计/命名

- App 显示名「偷偷聊」；包名 `com.vertin.teatalk`；服务端 app id `toutouliao`。
- 名字是玩梗，不代表加密；README/设置页必须写“局域网内明文传输”。

## 2026-10-26 · 局域网连不上的证据

- 用户反馈：主机 App「房间已开启」、主机 WebView 聊天正常；iPhone/另一台设备浏览器提示无法连接/服务器停止响应；两台设备同一 Wi-Fi，主机前台。
- 结论边界：服务器已在运行（本机 127.0.0.1 可聊），问题在 LAN 可达性。候选根因按概率：
  1. 旧地址/端口：Phase 0 用 18520，聊天版用 8520；旧书签/旧 QR 会失败。
  2. 选错 IP：VPN/移动网络/模拟器地址被当成 Wi-Fi 地址；旧代码只取第一个 site-local IPv4，没有网卡标签。
  3. 路由器/访客 Wi-Fi AP 隔离；主机本机可访问但另一台不行。
  4. 主机 App 被后台/锁屏影响（当前无 FGS），但用户说保持前台，优先级低。
- 诊断版已加：Wi-Fi/热点优先的候选地址、网卡名、VPN 警告、本机 health 自检、系统浏览器测试、复制诊断、网络变化自动刷新。
- 关键验证：诊断版里若「当前IP:8520」自检失败，是 Ktor 绑定/接口问题；若本机自检 ✓ 且主机系统浏览器能打开，但另一台不行，是网络隔离/VPN，不是 App。
- 构建环境事故：`/tmp/toutouliao-sdk` 的 platform/build-tools 被清空，导致一次构建失败；已重新下载 Android 37 platform/build-tools 并补齐 license，未下载模拟器；下载 zip 已删除。
- 诊断版：`0.1.1-diag`，versionCode 2，14MB；`APK输出/偷偷聊/2026-10-26_113106_diag/`。
