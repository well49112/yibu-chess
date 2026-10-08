# 弈步 0.8.0 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-0.8.0-arm64.apk)

手机浏览器先登录 GitHub 账号 `well49112`，进入页面后点击 **Download raw file** 下载。约 22.5 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia 模型与音效内置；Stockfish 分析和最强对手需要联网。

本版改动：

- Stockfish 分析与最强对手改用朋友的远端服务，移除本地 Stockfish 和 NNUE；Maia 匹配对弈保持离线。
- 在“对局设置”填写朋友提供的 Access Token，可“测试连接”并“保存设置”，当前棋局继续保留。
- 严格校验同深度最佳／实战结果、服务端比较标记、分值与合法变化，缺失实战结果不会复制最佳分数。
- 按引擎版本复用分析，切页／后台／修改口令取消旧请求，已完成复盘保存在手机。
- 缺少口令时最强对手提示配置，不会悄悄替换成 Maia。手动复盘、动画、王碎裂和常开音效继续保留。

同包名、同签名，versionCode 为 13，可直接覆盖旧版安装，保留棋谱和个人 Elo。AI 思考等待仍为 1–2 秒，计算更久时不追加等待。

仅验证本次相关功能：19 项 JVM／Compose 检查通过，原签名 release APK 构建完成。Cloud 网络无法访问实际服务地址，真实口令联调需安装后“测试连接”。未运行全量回归或全量 lint；上传后不重复下载验证。

[远端接入说明](https://github.com/well49112/yibu-chess/blob/v0.8.0/docs/REMOTE-STOCKFISH-INTEGRATION.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v0.8.0) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v0.8.0)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
