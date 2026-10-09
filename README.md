# 弈步 1.0.0 安装包

[打开 APK 下载页面](https://github.com/well49112/yibu-chess/blob/apk-downloads/yibu-1.0.0-arm64.apk)

手机浏览器先登录 GitHub，进入页面后点击 **Download raw file** 下载。约 22.4 MiB，支持 Xiaomi 17 Pro 等 ARM64 手机，最低 Android 8.0。Maia、开局课程与音效内置；Chess.com 导入和 Stockfish 分析需要联网。

本版改动：

- Chess.com 多棋局导入：用户名填一次后记住，支持最近 30 盘、100 盘或全部历史。逐月保存，自动识别白黑与原分数，跳过重复和已删除棋局；途中停止或失败保留已导入内容。不需要密码，不改变个人 Elo。
- 10 套开局课程：执白为意大利、西班牙、苏格兰、伦敦、后翼弃兵；执黑为双王兵、卡罗康、法兰西、西西里、后兵稳固防守。每套有主线与常见分支，棋盘逐步教学、具体原因、练习与答后解释；手动播放。
- 自由试走、撤回、离线 Maia 回应和从当前位置不计分陪练。学习进度本地保存，课程预置步骤不算自己的弱点与关键点。可打开与课程匹配的导入实战。
- 棋谱页聚焦导入、来源筛选、打开与删除；弱点统计和错题练习移到“训练 → 我的训练”，开局课程按执白/执黑分组。

同包名、同个人签名，versionCode 21，可覆盖安装并保留棋谱、练习记录、个人 Elo、口令与设置，无数据库迁移。Stockfish 保留 lightning 复评，匹配对局默认随机白黑，音效始终开启。

28 项针对性检查通过，构建原签名 release APK。只验证此次新增和直接受影响的行为，未运行全量回归或全量 lint；Cloud 未在 Xiaomi 真机测试，上传后不重复下载验证。

[本版改动与验证](https://github.com/well49112/yibu-chess/blob/v1.0.0/docs/RELEASE-1.0.0.md) · [完整源码](https://github.com/well49112/yibu-chess/tree/v1.0.0) · [版本说明](https://github.com/well49112/yibu-chess/releases/tag/v1.0.0)

本分支保留新旧安装包及 SHA-256 文件；签名密钥不在 Git 仓库中。
