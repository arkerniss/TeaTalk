# 偷偷聊（TouTouLiao）

同一片 Wi-Fi 下的局域网群聊 + 文件传输：一台安卓手机开房间，iPhone / 其他安卓 / 电脑用浏览器加入。

## 当前形态

- `app/`：安卓主机 App（Kotlin + Jetpack Compose）
  - 启动内嵌 Ktor 服务器（默认端口 `8520`）
  - 显示局域网 URL 与二维码
  - 主机自己用 WebView 打开聊天页；其他设备用浏览器加入
  - 支持文字、文件上传/下载、图片预览
- `core/`：纯 JVM 引擎与服务器
  - `ChatEngine`：用户、消息、文件、房间 epoch
  - JSONL 消息日志、文件落盘、WebSocket hub
  - Ktor HTTP/WS：`/api/health`、`/api/upload`、`/api/files/...`、`/ws`
- `devserver/`：本地 JVM 开发服务器，方便用浏览器调试 Web 聊天页
- `web/`：唯一一份 Web 聊天 UI（主机 WebView 与所有浏览器共用）

## 构建

```bash
./gradlew :app:assembleDebug
```

每次构建会自动生成带版本号和时间戳的 APK，不覆盖旧包：

```text
app/build/outputs/apk/debug/versioned/偷偷聊-v<versionName>-vc<versionCode>-<时间>-debug.apk
APK输出/偷偷聊/v<versionName>_vc<versionCode>/偷偷聊-v<versionName>-vc<versionCode>-<时间>-debug.apk
```

需要本机安装 Android SDK 37 / build-tools 37.0.0，`local.properties` 指向本机 SDK。仓库不包含 SDK、`build/`、APK 等产物。

## 本地开发 Web UI

```bash
./gradlew :devserver:installDist
devserver/build/install/devserver/bin/devserver
# http://127.0.0.1:18520/
```

## 边界

- 端口 `8520`，同一局域网明文 HTTP，仅适合可信网络。
- 当前版本未做前台服务/后台保活；主机 App 需保持前台。
- 当前未做安卓原生加入/相机扫码、PIN、UDP 发现、加密；其他安卓先用浏览器。
- 文件保存在主机 App 私有目录；清空/卸载会删除。

## 文档

- `task_plan.md`：阶段状态
- `findings.md`：技术事实与已修复问题
- `progress.md`：开发/验证记录
- `docs/screenshots/web-chat.png`：Web 聊天页截图
