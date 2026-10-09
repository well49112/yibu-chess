# 弈步 0.8.1 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-0.8.1-arm64.apk)

手机浏览器先登录 GitHub 账号 `well49112`，进入页面后点击 **Download raw file** 下载。约 22.3 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia 模型与音效内置；Stockfish 分析和最强对手需要联网。

本版改动：

- 全部 19 类音效重新合成，落子改为接近 Chess.com 风格的清脆触板声，吃子更低沉，易位两次落子，将军提示更短。始终开启，跟随媒体音量。
- 讲解说明失误为什么不好：对手反击、被攻击的棋子与格子、是否能回吃、净子力损失、漏防或错过将杀，以及推荐走法如何改善局面。
- 每着注释加入下一着参考应对，全局复盘每个关键点由最多 3 着延长到最多 16 着（8 回合），保持手动前进、倒退与换点。
- 打开旧讲解时用保存的分析在本机更新，避免重复搜索；复评改变结论时清除过期讲解，即使最佳走法没变也会更新。
- 沿用 0.8.0 云端 Stockfish 服务与现有口令，Maia 匹配对弈保持离线。

同包名、同签名，versionCode 为 14，可直接覆盖旧版安装，保留棋谱、个人 Elo 和设置。AI 思考等待仍为 1–2 秒，计算更久时不追加等待。

仅验证本次相关功能：30 项 JVM／Compose 检查通过，原签名 release APK 构建完成。未运行全量回归或全量 lint，也未重新联调生产服务；上传后不重复下载验证。变化长度由引擎返回的合法路线决定，短变化与将杀会提前结束。

[本版改动与验证](https://github.com/well49112/yibu-chess/blob/v0.8.1/docs/RELEASE-0.8.1.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v0.8.1) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v0.8.1)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
