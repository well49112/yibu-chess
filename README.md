# 弈步 1.0.1 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-1.0.1-arm64.apk)

手机浏览器先登录 GitHub，进入页面后点击 **Download raw file** 下载。约 22.5 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia、开局课程与音效内置；Chess.com 导入和 Stockfish 分析需要联网。

本版改动：

- 打开 App 自动补齐所有未完成分析的棋谱：Chess.com 导入、本地对弈、开局陪练和未结束对局，双方每步都分析；新导入与新落子自动入队。
- 统一使用 lightning，复用合格缓存；手动复盘和最强对手优先。每步完成立即保存，失败延后重试，网络恢复后继续，重新打开可接着分析。
- 独立 Android 前台服务支持切页、切到其他 App 和锁屏后继续。棋谱页显示剩余盘数/步数，可暂停/继续、显示进度通知、打开后台运行设置。
- 并发保存保留新落子、手动分析、终局与 Elo；删除棋局不会被队列重新写入。详细讲解仍按需生成。

小米手机若限制后台运行，可在“后台运行设置”将省电策略设为无限制，并允许后台自启动。通知权限只在主动点击时请求，不授权也能启动服务。系统强行停止、重启或资源限制可能中断服务，已完成结果会保留，下次打开继续。Android 15 及以上限制此类服务后台累计 6 小时 / 24 小时，达到时限会保存并停止，打开 App 后继续。

同包名、同个人签名，versionCode 22，可覆盖安装并保留棋谱、课程/练习记录、个人 Elo、口令与设置，无数据库迁移。保留 1.0.0 的批量导入与 10 套开局课程。

23 项针对性检查通过，构建原签名 release APK。只验证此次新增和直接受影响的行为，未运行全量回归或全量 lint；Cloud 未在 Xiaomi 真机测试，上传后不重复下载验证。

[本版改动与验证](https://github.com/well49112/yibu-chess/blob/v1.0.1/docs/RELEASE-1.0.1.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v1.0.1) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v1.0.1)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
