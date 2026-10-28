# 茶室（TeaTalk）

同一片 Wi-Fi 下的局域网群聊 + 文件传输：一台安卓手机开房间，其他安卓可自动搜索加入，iPhone/电脑用浏览器加入。

## 当前形态

- `app/`：安卓 App（Kotlin + Jetpack Compose，包名 `com.vertin.teatalk`）
  - 首页选择「开房间」或「加入房间」
  - 主机启动内嵌 Ktor 服务器（默认端口 `8520`），显示 URL 和二维码
  - 主机用 WebView 打开聊天页；加入端也用同一个 Web 聊天页
  - 加入端通过 **UDP 广播自动发现**附近房间；兜底支持 **CameraX 扫二维码** 和手动输入地址
  - 支持文字、文件上传/下载、图片预览
  - 聊天页右上角菜单：「文件」历史文件库、「清除聊天记录」
  - 文件保留 7 天，右滑文件可延期 7 天；清除聊天记录不会删除文件
  - 茶室品牌图标：深茶绿底、奶油色茶杯 + 聊天气泡尾巴，含自适应/单色图标
  - 原生页面使用 Liquid Glass：玻璃卡片、玻璃按钮、环境茶色背景
  - Web 聊天页保持轻量稳定样式（0.1.7.1 已回退玻璃/动画）
- `core/`：纯 JVM 引擎、服务器、发现协议
  - `ChatEngine`：用户、消息、独立文件库、房间 epoch
  - JSONL 消息日志、文件落盘、文件库索引、WebSocket hub
  - Ktor HTTP/WS：`/api/health`、`/api/upload`、`/api/files/...`、`/api/library`、`/api/room/clear`、`/ws`
  - 发现协议：UDP `18522`，room beacon + probe
- `devserver/`：本地 JVM 开发服务器，方便用浏览器调试 Web 聊天页
- `web/`：唯一一份 Web 聊天 UI（主机 WebView 与所有浏览器共用）

> 项目目录保留 `toutouliao`，但 Android 包名/品牌是 `com.vertin.teatalk` / 茶室。当前版本没有真实用户迁移负担。

## 加入方式

1. **自动发现**：加入端打开「附近茶室」，同一 Wi-Fi 下主机会在 3 秒内出现，点选即可。
2. **扫码**：扫描主机屏幕上的二维码；首次使用会请求相机权限。
3. **手动地址**：输入 `IP:端口`，例如 `192.168.3.46:8520`。

自动发现需要同一 Wi-Fi/热点，路由器不能开启 AP 隔离；UDP 被发现拦截时用扫码或手输。发现只在 App 前台工作。

## 构建

```bash
./gradlew :app:assembleDebug
```

每次构建会自动生成带版本号和时间戳的 APK，不覆盖旧包：

```text
app/build/outputs/apk/debug/versioned/茶室-v<versionName>-vc<versionCode>-<时间>-debug.apk
APK输出/茶室/v<versionName>_vc<versionCode>/茶室-v<versionName>-vc<versionCode>-<时间>-debug.apk
```

需要 Android SDK 37 / build-tools 37.0.0，`local.properties` 指向本机 SDK。仓库不包含 SDK、`build/`、APK 等产物。

## 版本规则

- 显示版本从 `0.1.0` 基线重新开始。
- **不构建 APK 的源码更新：只递增第四位**，例如 `0.1.5.1`、`0.1.5.2`……
- **真正构建 APK 时：递增第三位**，例如 `0.1.6`，第四位归零。
- 第二位只有用户明确要求时才更新（例如 `0.2.0`），同时第三位归 0。
- `versionCode` 是 Android 安装顺序号，每次准备构建时单调递增，不随显示版本重置。
- 当前状态：最近构建版本是 `0.1.7`；当前源码（未构建）是 `0.1.7.1`（Web 玻璃/动效已回退）。

## 发布 / Release

每次版本更新都要同时完成：

1. 更新 `CHANGELOG.md` 和 `docs/release-notes/<version>.md`
2. `./gradlew :app:assembleDebug`，确认版本化 APK
3. 提交源码并 push
4. 创建 GitHub Release，**只放更新日志，不上传 APK**：

```bash
gh release create "v<version>" \
  --title "茶室 <version>" \
  --notes-file "docs/release-notes/<version>.md" \
  --target main
```

Release 使用独立 tag，不覆盖旧 release。APK 不推送到 GitHub，只保留在本机 `APK输出/茶室/`。

## 本地开发 Web UI

```bash
./gradlew :devserver:installDist
devserver/build/install/devserver/bin/devserver
# http://127.0.0.1:18520/
```

## 边界

- 端口 `8520`，发现端口 `18522`；局域网明文 HTTP，仅适合可信网络。
- 主机开房时启动前台服务保活，支持切后台/锁屏；通知栏常驻「茶室正在开房」，极端省电策略下仍可能被系统停止。
- 发现包未签名、未做 PIN；显示后需要用户点选，加入前会做 `/api/health` 校验。
- 文件保存在主机 App 私有目录，默认保留 7 天；过期文件在服务器运行时定时清理，主机重启后补清理。
- 「清除聊天记录」只清文字/文件消息卡片，不删除文件库；文件仍可在「文件」页下载或延期。
- iOS/浏览器端继续使用二维码或手动地址，不参与 UDP 自动发现。

## 文档

- `task_plan.md`：阶段状态
- `findings.md`：技术事实与已修复问题
- `progress.md`：开发/验证记录
- `docs/screenshots/web-chat.png`：Web 页面截图
- `docs/icon/`：App 图标预览、色板与 Android 资源说明
