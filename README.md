# 弈步 1.0.3 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-1.0.3-arm64.apk)

手机浏览器先登录 GitHub，进入页面后点击 **Download raw file** 下载。约 22.5 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia、开局课程与音效内置；Chess.com 导入和 Stockfish 分析需要联网。

本版改动：

- 复盘、训练、棋谱删除顶部弈步栏，只有对弈页保留。
- 开局教学一屏展示目标进度、大棋盘、完整当前讲解和操作，无需上下滑动；提示、错误反馈和答后解释不会移动棋盘。
- 进入课程收起底部导航，保留返回与路线选择，返回课程列表后恢复。棋盘仍与对弈同宽。
- 去掉重复文字，完整答后原因保留；40 条开局路线、两级提示、示范、课末再练和旧学习记录继续保留。逐步讲解、自由试走、不计分陪练和导入实战例子在更多中。

同包名、同个人签名，versionCode 24，可覆盖安装并保留棋谱、课程/练习记录、个人 Elo、口令与设置，无数据库迁移。保留 Chess.com 批量导入和全部棋谱自动分析/后台服务。

9 项受影响界面检查通过，包括 320dp 小屏、392dp / 1.2 倍字体、40 条路线各阶段完整文字及白黑连续落子。已构建原签名 release APK；只验证本次修改，未运行全量回归或全量 lint。Cloud 未在 Xiaomi 真机测试，上传后不重复下载验证。

[本版改动与验证](https://github.com/well49112/yibu-chess/blob/v1.0.3/docs/RELEASE-1.0.3.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v1.0.3) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v1.0.3)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
