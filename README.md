# 弈步 0.8.2 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-0.8.2-arm64.apk)

手机浏览器先登录 GitHub 账号 `well49112`，进入页面后点击 **Download raw file** 下载。约 22.3 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia 模型与音效内置；Stockfish 分析和最强对手需要联网。

本版改动：

- 自动记录分析分段耗时：HTTP、DNS、连接与 TLS、结果处理、保存事务、全部棋谱 JSON 解码，以及界面下一帧回调。
- 顶部信息按钮新增“导出分析耗时”，可在手机分享 JSON，无需电脑。记录保留本次进程最近 120 次，导出前不要重启 App。
- 每次分析携带随机 X-Request-ID，朋友可用同一编号对照搜索、排队和服务端总处理时间；服务端未提供的数据显示为 null。
- 导出不含 Access Token、请求/响应正文、完整棋谱、SSID 或具体 IP。搜索参数和按步保存顺序沿用 0.8.1，便于公平比较。
- 保留详细失误讲解、最多 16 着手动关键点复盘，以及内置 Maia 匹配对弈与原有音效。

安装后打开慢的棋谱，选择不同的 3–5 步，分别点“复评本步”。保持 App 在前台，再点顶部信息按钮 → “导出分析耗时”，将 `yibu-analysis-timings.json` 分享给开发者或朋友。已经缓存的整盘复盘可能不会发出新请求。

同包名、同签名，versionCode 为 15，可直接覆盖旧版安装，保留棋谱、个人 Elo 和设置。AI 思考等待仍为 1–2 秒，复盘没有模拟思考等待。

仅验证本次相关功能：17 项 JVM／Compose 检查通过，原签名 release APK 构建完成。未运行全量回归或全量 lint，尚未在 Xiaomi 真机或生产服务测量真实耗时；上传后不重复下载验证。

[完整记录方法与服务端说明](https://github.com/well49112/yibu-chess/blob/v0.8.2/docs/ANALYSIS-TIMING.md) · [本版改动与验证](https://github.com/well49112/yibu-chess/blob/v0.8.2/docs/RELEASE-0.8.2.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v0.8.2) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v0.8.2)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
