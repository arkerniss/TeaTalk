# 茶室 · 阶段计划

> 完整实施计划：`../茶室-实施计划.md`
> 本文件只做阶段状态/持久恢复，不复制完整设计文档。

## Goal

交付一个安卓 APK：一台安卓手机当房间主机，iPhone / 其他安卓 / 电脑通过浏览器加入，多人聊天 + 文件互传。

## 当前 APK 能力（2026-10-20）

- ✅ 主机 App 启动内嵌 Ktor 服务器（端口 8520）
- ✅ 主机控制台：房间 URL、二维码、换地址/刷新地址、在线人数
- ✅ 主机 App 内 WebView 打开自己的聊天页，可发文字/文件/图片
- ✅ 其他手机用浏览器打开 `http://主机IP:8520/`，输入昵称即可聊天
- ✅ 文件上传（进度）、下载、图片内联；浏览器 E2E 3 用户通过
- ❌ 前台服务/后台保活未做：当前主机 App 需保持打开/前台
- ❌ 安卓原生「加入房间」和相机扫码未做：其他安卓先用浏览器
- ❌ 500MB 压测、完整安全边界、PIN/自动发现未做

**APK：`app/build/outputs/apk/debug/app-debug.apk`（18MB，debug 签名）**
归档：`../APK输出/茶室/2026-10-20_102413/app-debug.apk`

## Phases

### Phase 0 · 脚手架与栈验证 — ✅ 完成

- [x] 三模块工程、Gradle 9.8.1 / AGP 9.4.1 / Kotlin 2.3.21 / Compose BOM
- [x] compileSdk 37 + Android 37 platform（临时 SDK `/tmp/toutouliao-sdk`）
- [x] `:app:assembleDebug` 通过；`LanServer` health 在模拟器/真机验证由用户完成
- [x] 模拟器不下载（用户要求），缓存已删

### Phase 1 · core 引擎与存储 — ✅ 完成（V1 核心）

- [x] `ChatEngine`：join/leave/rename/text/file/clear；StateFlow + SharedFlow
- [x] `MessageLog` JSONL、`FileStore` 流式落盘、`RoomMeta` epoch
- [x] JVM 测试 5/5：文本持久化、文件元数据、重复 clientId、clear、after
- [ ] 完整安全/限额单测（后续补）

### Phase 2 · HTTP/WS + Web UI + E2E — ✅ 完成（首版）

- [x] Ktor HTTP + WebSocket、静态资源、上传/下载/Range、安全头、Origin 校验
- [x] Web 聊天页：昵称、在线用户、文字、文件进度、图片预览、下载、重连
- [x] 浏览器 E2E：3 用户、文字互发、文件上传/下载、刷新历史
- [x] 修复 `.bubble` 类名冲突（加入页 logo 把消息气泡设成 absolute）
- [ ] 500MB 压测、限流/穿越/HTML 全套安全用例、Playwright 正式脚本

### Phase 3 · 安卓主机壳 — 🚧 最小版完成，FGS 待补

- [x] 控制台 + 二维码 + LAN URL + 换地址/刷新
- [x] WebView 聊天、文件选择、DownloadManager、返回退出
- [x] 历史持久化、房间数据在 `filesDir/toutouliao`
- [ ] 前台服务 + 通知 + Wi-FiLock/唤醒锁（后台保活）
- [ ] 设置页、清空房间、更多错误态

### Phase 4 · 安卓加入 + 扫码 — pending

- [ ] JoinUrl/HealthCheck、原生加入 WebView
- [ ] CameraX + ZXing 扫码、相册兜底

### Phase 5 · 打磨、文档与交付 — pending

- [ ] 无障碍/Compose 测试、review、截图、README、release APK 归档

## Decisions

- 聊天 UI 用 WebView/Web，不写第二套原生 Compose。
- 浏览器走 HTTP；V1 无加密/无 PIN，V1.1 加 PIN。
- 设计方向：impeccable seed-126（纯白 + 深青 + 柿橙）。
- `adaptive` 因强制 Navigation 3 不用。
- 模拟器默认不下载；真机验证优先（用户明确要求）。
- 当前主机服务在 MainActivity 内，FGS 前不保证锁屏/后台存活；先用于功能验证。

## Errors Encountered

| Error | Attempt | Resolution |
|---|---|---|
| git clone / codeload 无法连接 GitHub | 1 | 改用 raw.githubusercontent.com 拉取 skill 文件 |
| 全局 `~/.agents/skills` 沙箱不可写 | 1 | 一次 danger-full-access 授权安装 |
| AAR metadata 要求 compileSdk 37 | 1 | 手动下载 Android 37 platform/build-tools 到 `/tmp/toutouliao-sdk` |
| Kotlin daemon 不能写 home | 1 | `kotlin.compiler.execution.strategy=in-process` |
| 模拟器下载不合需求 | 1 | 停止并删除 `/tmp/toutouliao-sdk/.sdk`（945MB） |
| `.bubble` CSS 类名冲突导致消息气泡 absolute、发送者名被盖住 | 1 | 加入页气泡改为 `.join-mark .bubble*`；浏览器截图复验 |
