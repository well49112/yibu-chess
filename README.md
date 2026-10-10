# 弈步 1.0.4 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-1.0.4-arm64.apk)

手机浏览器先登录 GitHub，进入页面后点击 **Download raw file** 下载。约 22.5 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia、开局课程与音效内置；Chess.com 导入和 Stockfish 分析需要联网。

本版改动：

- 整盘深度复评、全局关键点缺失的分析和后台棋谱队列接入 `/sf/v1/review`，提交完整 UCI 历史，服务器使用 lightning 并行搜索 22 层。
- 批量响应为摘要，计算后用相同缓存参数、4 路并发读取完整详情，保留失误讲解和后续棋盘演示。手机仍使用原评级算法，缺少第二候选时保守处理 ! / !!。
- 中断保留结果，后台继续缺失的步骤；单步失败保留其他成功结果，删除的棋谱不会被恢复，不覆盖较新分析或重复结算 Elo。
- 手动复盘、最强对手优先，可以中断后台等待；当前正在下的棋局仍增量分析新步骤。等待服务器返回时如实显示等待，随后按实际保存进度更新。

同包名、同个人签名，versionCode 25，可覆盖安装并保留棋谱、课程/练习记录、个人 Elo、口令与设置，无数据库迁移。单步复评与最强对手原接口保留，教学仍一屏完成。

30 项针对性检查通过，已构建原签名 release APK；只验证本次修改，未运行全量回归或全量 lint。公开服务端响应与缓存键已核对，Cloud 无服务口令，未做已部署服务鉴权联调或 Xiaomi 真机测试；实际速度取决于网络、棋局、缓存和服务器。上传后不重复下载验证。

[本版改动与验证](https://github.com/well49112/yibu-chess/blob/v1.0.4/docs/RELEASE-1.0.4.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v1.0.4) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v1.0.4)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
