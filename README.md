# 弈步 0.8.3 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-0.8.3-arm64.apk)

手机浏览器先登录 GitHub 账号 `well49112`，进入页面后点击 **Download raw file** 下载。约 22.3 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia 模型与音效内置；Stockfish 分析和最强对手需要联网。

本版改动：

- 整合朋友 PR #2，对弈时持续在后台缓存复盘，人类和 Maia 落子不再取消上一着分析。
- “对局设置”新增后台搜索预算，默认“极速”：明确请求 lightning、目标深度 22、500ms 搜索预算，保留两个候选供评级。可选择“深入”使用服务器较长预算。
- 修正后台缓存引擎版本检查、重复分析循环、AI 并发更新覆盖复盘及 Room 延迟打开单例问题。切到深入会刷新极速结果。
- 保留分段耗时导出，记录实际 profile 与极速预算；可在手机分享 JSON，不包含 Access Token 或完整棋谱。
- 保留详细失误讲解、最多 16 着手动关键点复盘、内置离线 Maia 匹配对弈和原有音效。

安装后直接开始新局，后台使用极速档逐步缓存。赛后复盘复用已完成的有效结果，缺少的步骤才请求深入档。未缓存的旧棋谱和手动“复评本步”仍采用 deep，可能等待数秒。500ms 是搜索预算，不保证手机端总等待为 0.5 秒或每个局面完成 22 层。

同包名、同签名，versionCode 为 16，可直接覆盖旧版安装，保留棋谱、个人 Elo、口令与设置。AI 思考等待仍为 1–2 秒，复盘没有模拟思考等待。需要记录实际耗时时，顶部信息按钮 → “导出分析耗时”；导出前不要重启 App。

仅验证本次相关功能：21 项检查通过，原签名 release APK 构建完成。未运行全量回归或全量 lint，尚未在 Xiaomi 真机或生产服务测量真实耗时；上传后不重复下载验证。

[完整记录方法与服务端说明](https://github.com/well49112/yibu-chess/blob/v0.8.3/docs/ANALYSIS-TIMING.md) · [本版改动与验证](https://github.com/well49112/yibu-chess/blob/v0.8.3/docs/RELEASE-0.8.3.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v0.8.3) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v0.8.3)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
