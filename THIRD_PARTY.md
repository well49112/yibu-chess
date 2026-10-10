# 开源组件与版本

弈步 1.0.4 应用源码与包含 Maia-3 的组合使用 AGPL-3.0；第三方组件保留原有许可。完整源码、Maia 模型原始检查点、转换脚本和离线权重与本版本一同交付。

## Stockfish（远端服务）

- 上游：https://github.com/official-stockfish/Stockfish
- 客户端对接版本：19；实际版本以服务响应的 engine.name / engine.version 为准。
- 许可证：GPL-3.0-or-later，全文见 vendor/stockfish/Copying.txt。
- 0.8.0 APK 不包含本地 Stockfish 二进制、JNI 桥接或 NNUE。服务器由朋友单独部署，客户端通过 HTTP 请求分析。
- vendor/stockfish 保留旧版 17.1 的历史源码（03e27488f3d21d8ff4dbf3065603afa21dbd0ef3），不是远端 19 的实现来源。

## chesslib

- 上游：https://github.com/bhlangonijr/chesslib
- 版本：1.3.7
- 提交：2e9b4e1a71a84d4fe147e762cf39c82a8cd4c251
- 许可证：Apache-2.0，全文见 vendor/chesslib/LICENSE。
- 上游源码未修改。应用单独实现保守的死局子力判断，避免把“不能强制将杀”误当作“不可能将杀”。

## Maia-3

- 上游：https://github.com/CSSLab/maia3
- 提交：1e13597c42d4858b7cfd7cfdae01e297263364b2
- 模型：https://huggingface.co/UofTCSSLab/Maia3-5M
- 检查点修订：b6559de2398d7140b985f28fd2c19fb5e47ddabe
- 原始权重 SHA-256：ba14208b2992d85502f5fb501934abf6aaaeb355e9f3fdf90e326911f562524f
- ONNX SHA-256：40d819d93f4d59f9b3f1017e1287d8c58f8240a27fb34f41d238f1e9b12b394b
- 许可证：AGPL-3.0，全文见 vendor/maia3/LICENSE。
- vendor/maia3 保留上游 Python 源码和原始 5M 检查点；tools/export-maia.py 构建策略专用 ONNX 并量化。移动端编码与抽样在 core，运行在 app/engine/MaiaModel.kt。

## ONNX Runtime

- 上游：https://github.com/microsoft/onnxruntime
- 版本：1.24.3，标签 v1.24.3（提交记录见 vendor/onnxruntime/UPSTREAM.json）。
- 使用 Maven Central 官方 Android ARM64 运行时；宿主测试使用相同版本 Linux JVM 运行时。
- 许可证：MIT；完整许可与第三方通知见 vendor/onnxruntime/LICENSE 和 ThirdPartyNotices.txt。

## 其他依赖

- AndroidX Compose / Activity / Lifecycle / Room：Apache-2.0
- Apache Commons Lang 3.18.0：Apache-2.0
- Kotlin / kotlinx.coroutines / kotlinx.serialization：Apache-2.0
- OkHttp 4.12.0 / Okio 3.6.0：Apache-2.0，完整许可同附带的 Apache-2.0 文本。
- Gradle Wrapper：Apache-2.0

精确版本由 Gradle 文件固定。测试签名文件、构建缓存和本机 SDK 不属于源码包。

## 开发用设计技能

- 上游：https://github.com/emilkowalski/skills
- 提交：e8a175de22ae1e49370fc144c1f3bb9aeedf988d
- 安装位置：.agents/skills，包含上游技能与参考文件。
- 许可证：MIT，全文见 .agents/skills/LICENSE-emilkowalski。
- 本次界面使用 emil-design-eng 原则，图标为应用自有 Compose / Android 矢量绘制；技能文件不作为运行时依赖打包到 APK。

本版 19 类音效由本项目 tools/generate-sounds.py 原创合成，无外部采样，随应用采用 AGPL-3.0。0.8.1 使用滤波瞬态与不成整数倍的衰减共振模拟棋子触板，没有使用 Chess.com 的录音或声音文件。音频生成说明见 app/src/main/assets/licenses/ORIGINAL_SOUNDS.txt。
