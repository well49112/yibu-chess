# 远端 Stockfish 对接（更新至 1.0.4）

本版采用 PR #1 提供的接口格式，与最初的 REMOTE-STOCKFISH-API.md 提案存在字段差异。手机不要求后端改成提案格式。

- 服务地址：`https://chess.jeefy.top`。
- 前缀：`/sf/v1`；鉴权头：`X-Access-Token: <朋友提供的口令>`。
- Token 由用户在对局设置输入，仅保存在应用本地，不包含在 APK 或对局导出中。
- 每次请求携带 `position.initialFen`（标准起始六字段 FEN）和完整 UCI `position.moves`。不依赖服务端棋局会话。
- Stockfish 19 在服务端运行；手机保留 Maia-3、棋谱数据库、Elo、评级和基于 PV 的中文讲解。

## 1.0.4 整盘批量分析

依据朋友公开服务端 [UniChessServer](https://github.com/jeefies/UniChessServer) 的 `sf_router.py`、`sf_analyzer.py` 确认协议；核对的源码提交为 `ce2c25d998240df250b2af45fa698588baaf2c2e`。接口是一次性 JSON 响应，没有流式进度。

```http
POST /sf/v1/review
Content-Type: application/json
X-Access-Token: <TOKEN>
```

```json
{"moves":["e2e4","e7e5","g1f3","b8c6"],"initialFen":null,"profile":"lightning","concurrency":null}
```

实际请求额外携带随机 `requestId`，详情各自使用带步数后缀的标识，便于服务端区分取消请求；不含口令或棋谱标识。每次提交一盘棋的完整历史，保留重复局面判定。多盘在后台队列逐盘提交；盘内并发由服务器决定，20 核主机默认 16 路。App 使用推荐的 UCI 数组，不额外上传 PGN；当前棋谱均从标准初始局面开始。

响应包含 `totalPlies`、`analyzedPlies`、`cacheHits`、`elapsedMs`、`summary` 和 `moves[]`。每行的 `ply` 从 1 开始，`move` 为实际 UCI、`turn` 为 white/black。按序号、走法和颜色验证对应关系，拒绝错局、重复序号及错配行。

重要：批量行仅含最佳着、最佳分值、损失、准确率和评语，没有完整的 `best`/`played`/`second`/`previousBest` 变化。直接把摘要存为原分析会丢失失误证明、后续棋盘讲解和将杀语义。因此先批量计算，再以最多 4 路并发读取完整缓存：

```http
POST /sf/v1/analyze-move
```

```json
{"position":{"initialFen":null,"moves":["e2e4"]},"playedMove":"e7e5","profile":"lightning","multiPv":1,"maxPvPlies":10}
```

这里不传 `limits`。缓存键为初始 FEN（null 对应空值）、完整历史、实战着、22 层、无时间预算、MultiPV=1、PV=10 半步和引擎身份。所有这些值与批量计算一致；不能使用旧单步的标准 FEN 字符串 / 500ms / MultiPV=2 / PV=12，否则会重新计算。服务器缓存失效或引擎变更仍可能触发补算，不能保证所有详情都零搜索。

手机仍按原评分棋力算法生成评级，忽略摘要的 `judgment` 与 `accuracy`。真实详情必须满足原合法变化、实战着匹配、同深度和 `canCompare` 检查。Lightning 批量只有一条候选，缺少第二候选时不能证实 ! / !!，按原算法保守评级。完整后续变化最多 10 半步，原讲解和手动棋盘步进继续可用。

手动整盘强制刷新双方；全局关键点只读取并保存缺失的玩家步骤；后台只读取并保存缺失步骤。每条完整结果保存后才计为完成。单行失败或摘要缺行时保留其他成功步骤，未完成步骤重试；整盘 HTTP 失败不制造完成结果。请求取消向全部详情协程和 OkHttp 传播，后台手动优先会及时让出共享锁，口令变化停止旧任务。

整盘与缓存详情读/总请求超时为 15 分钟；后台计算期间持有带超时的 partial wake lock，结束或取消后释放，Android 15 前台服务时限保持原处理。进度在整盘返回前明确显示等待服务器，随后按实际保存数量更新，不用假进度。计时导出记录 `batch_http_ms`、服务端批量耗时和缓存命中步数，`search_budget_ms=null`、`multi_pv=1`。

当前正在下的未结束对局继续单步增量分析；单步复评、讲解缺失的单步分析及最强对手原接口保留。单步 lightning 仍显式 22 层 / 500ms / MultiPV=2，35 秒超时。

此版本使用 MockWebServer + Room + ViewModel 检查请求与保存流程，并对照公开服务端缓存键。Cloud 未得到真实口令，公开接口文档返回 403，未完成已部署服务的鉴权联调或 Xiaomi 真机测试。

## 原有单步请求与响应

`GET /sf/v1/health` 返回 `status` 和可选的 `engine.name`、`engine.version`。ready / ok / healthy 表示就绪；失败或未就绪不能显示连接成功。

`POST /sf/v1/evaluate` 请求：

```json
{
  "position": {
    "initialFen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "moves": ["e2e4"]
  },
  "profile": "standard",
  "multiPv": 1
}
```

响应包含 `completedDepth`、`best`、可选 `candidates` 与 `engine`。最强对手从合法的 `best.pv[0]` 取得走法。

`POST /sf/v1/analyze-move` 使用相同的 `position`，增加 `playedMove`；`profile` 为 fast 或 deep，`multiPv` 为 2。position 是该实战着尚未走出的完整历史。响应示例中的分值只用于说明结构：

```json
{
  "best": {
    "move": "e7e5", "depth": 22,
    "score": {"type": "cp", "value": 34},
    "wdl": {"win": 110, "draw": 830, "loss": 60},
    "pv": ["e7e5", "g1f3", "b8c6"]
  },
  "played": {
    "move": "c7c5", "depth": 22,
    "score": {"type": "cp", "value": 28},
    "pv": ["c7c5", "g1f3", "d7d6"]
  },
  "second": null,
  "previousBest": null,
  "comparison": {"canCompare": true, "commonDepth": 22},
  "engine": {"name": "Stockfish", "version": "19"}
}
```

## 结果约束

- 分数固定为请求根局面的行棋方视角，黑方行棋时也不改变这一约定。type 为 cp（厘兵）或 mate（Stockfish 将杀着数）。
- best 和 played 都必须有真实分值及合法变化。played 缺失不能复制 best，played.pv 第一着必须匹配 playedMove。
- 仅当 canCompare 为 true、best.depth = played.depth = commonDepth，且都是精确分值时确认评级。若 score.bound 给出 lower / upper，保持待复评。
- second 必须与最佳同深度；previousBest 必须来自更早的完整深度。缺少这些证据时，保守处理 ! / !!。
- WDL 若存在必须每项为 0–1000 且总和为 1000；缺失不会伪造成“100% 和棋”。
- actual depth 低于目标时如实显示，原有个人 Elo 评分模型仍由手机执行。

## 网络与缓存

- 单步请求总超时 35 秒，读超时 30 秒。离开计算页面、新局、切后台或修改口令时取消旧请求；旧结果不会写入新棋局。
- 0.8.0 的整盘复盘按步调用 analyze-move；自 1.0.4 起使用上面的批量 + 缓存详情流程，暂停后仍保留已完成结果。
- 本地深度分析按引擎版本及评分 Elo 复用；17.1 的结果仍可查看，不冒充 19 的新分析。
- 未配置口令或服务断开时，Maia 匹配对弈仍可使用；远端复盘给出明确错误。最强对手不会自动变成 Maia。
- 401 / 403 提示更新口令，429 提示服务器忙，503 / 504 提示稍后重试。请求取消向协程正常传播。

## 本次验证范围

使用本地 MockWebServer 验证真实 HTTP 请求、JSON、白黑视角、缺失字段、非法 PV、比较标记、错误响应、取消后再次请求，以及设置保存和离线 Maia 对局。沿用签名，构建 arm64 release。

Cloud 请求朋友服务地址被网络层拒绝，因此未完成带真实口令的服务联调，也未在 Xiaomi 真机上运行。安装后可在对局设置中用朋友提供的口令点击“测试连接”。
