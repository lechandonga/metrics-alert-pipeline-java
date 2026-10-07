# metrics-alert-pipeline-java（进程内指标采集、窗口聚合与告警服务）

纯进程内实现的指标流水线：指标上报、校验、按时间窗口聚合、告警规则判定、状态机流转与
通知投递全部在单个 JVM 内完成，**不依赖任何外部服务、数据库或消息中间件**。
仅使用 Spring Boot Web 暴露 REST 接口，存储与调度均为内存结构。

## 1. 指标模型

### 1.1 指标类型

| 类型 | 含义 | 窗口聚合统计量 |
| --- | --- | --- |
| `COUNTER` | 增量计数（上报的是增量值） | `count`、`sum` |
| `GAUGE` | 瞬时值（温度、内存等） | `count`、`avg`、`min`、`max`、`last` |
| `DISTRIBUTION` | 分布观测（延迟等） | `count`、`sum`、`avg`、`min`、`max`、`p50/p90/p95/p99` |

### 1.2 样本结构

```json
{
  "metric": "http_requests",
  "type": "COUNTER",
  "timestampMillis": 1700000001000,
  "value": 3.0,
  "tags": { "path": "/api/a", "region": "cn" }
}
```

- 序列（series）= 指标名 + 标签集合；标签在内部按 key 排序规范化，因此
  `{a,b}` 与 `{b,a}` 是同一条序列。
- 样本一旦通过校验即为**不可变对象**（record + 不可变标签 Map），读端不可能看到半更新。
- 指标需先注册（`POST /metrics/register`）；重复注册同名不同类型会被 `TYPE_MISMATCH` 拒绝。

### 1.3 拒绝原因（绝不静默丢弃）

批量上报中每个非法样本都会返回其在批次中的下标、可区分错误码与说明：

`METRIC_NAME_MISSING`、`METRIC_NAME_ILLEGAL`、`TYPE_MISSING`、`TYPE_UNKNOWN`、
`TYPE_MISMATCH`、`VALUE_MISSING`、`VALUE_NOT_FINITE`、`TIMESTAMP_MISSING`、
`TIMESTAMP_ILLEGAL`、`TIMESTAMP_IN_FUTURE`、`SAMPLE_TOO_LATE`、`TAG_NOT_OBJECT`、
`TAG_KEY_ILLEGAL`、`TAG_VALUE_ILLEGAL`。

- 指标名：`[a-zA-Z_:][a-zA-Z0-9_:]*`；标签 key：`[a-zA-Z_][a-zA-Z0-9_.-]*`；
  标签 value 必须是非空字符串。
- 批量接口对合法样本全部接受、非法样本全部带原因返回，HTTP 状态
  `200`（全接受）/ `207`（存在拒绝）；单样本接口对非法样本返回 `400`。

### 1.4 并发模型

- 指标注册表使用 `ConcurrentHashMap`；每条序列各自一把锁，内部用时间戳有序的
  `TreeMap` 存放样本。
- 写入在序列锁内追加（不丢失、不重复）；查询在同一把锁内做区间快照，
  不同序列之间完全并行。

## 2. 窗口与迟到语义

- **滚动窗口（tumbling）**，长度 `metrics.pipeline.window-size-millis`（默认 10s），
  窗口边界相对 epoch 对齐：`windowStart = floor(ts / windowSize) * windowSize`。
- 以事件时间（样本时间戳）归属窗口，因此**同一批样本无论什么顺序到达，聚合结果一致**。
- 允许迟到：`metrics.pipeline.allowed-lateness-millis`（默认 5s）。
  - 样本被接受的条件：
    - `ts <= now`（拒绝未来样本 `TIMESTAMP_IN_FUTURE`）
    - `ts > now - windowSize - allowedLateness`
  - 在允许范围内迟到：样本归入**其所属的旧窗口**，不会混入当前窗口。
  - 超出范围：明确以 `SAMPLE_TOO_LATE` 拒绝，错误信息中给出所属窗口起点。
- 告警引擎水位 = `now - allowedLateness`；每个完全关闭的窗口按窗口顺序**恰好评估一次**，
  水位只进不退，因此评估结果也与样本到达顺序无关。
- 样本保留期 `metrics.pipeline.retention-millis`（默认 1h），到期由本地定时任务清理。

查询示例：

```bash
curl 'http://localhost:8080/metrics/windows?metric=http_requests&tag.path=/api/a'
curl 'http://localhost:8080/metrics/samples?metric=cpu&fromMillis=...&toMillis=...'
```

## 3. 告警状态机

四种状态：`INACTIVE`（未激活）、`PENDING`（待定）、`FIRING`（触发）、
`RECOVERING`（恢复待定）。

```
INACTIVE  --连续 breach 数达到 triggerWindows-->   FIRING      (发 TRIGGERED)
INACTIVE  --首个 breach-->                        PENDING
PENDING   --恢复正常-->                           INACTIVE
PENDING   --连续 breach 达标-->                   FIRING      (发 TRIGGERED)
FIRING    --首个正常窗口-->                       RECOVERING
RECOVERING--再次 breach-->                        FIRING      (抖动，不通知)
RECOVERING--连续正常数达到 recoverWindows-->      INACTIVE    (发 RECOVERED)
FIRING    --连续正常数达到 recoverWindows-->      INACTIVE    (recoverWindows=1 时)
```

- 去抖由 `triggerWindows` / `recoverWindows` 两个可配阈值实现；单窗口抖动既不会重复触发，
  也不会提前恢复。
- 通知只在两条状态边产生：`INACTIVE→FIRING`（TRIGGERED）、
  `RECOVERING/FIRING→INACTIVE`（RECOVERED），每个生命周期最多各一条。
- 无样本的空窗口按“正常”处理，不会误报。

规则示例（`POST /alerts/rules`）：

```json
{
  "name": "cpu-high",
  "metric": "cpu",
  "tagFilter": { "host": "h1" },
  "statistic": "AVG",
  "operator": "GT",
  "threshold": 80,
  "triggerWindows": 2,
  "recoverWindows": 2
}
```

`statistic`：`COUNT/SUM/AVG/MIN/MAX/LAST/P50/P90/P95/P99`；
`operator`：`GT/GTE/LT/LTE/EQ`。

## 4. 通知与故障处理（状态与投递分离）

- 状态边产生时，在**同一把规则锁内**向发件箱（outbox）追加一条通知记录，
  因此“告警状态已变”与“存在待投递通知”永远一致，不可能互相矛盾。
- 真正的投递在锁外进行，投递结果只写通知记录（`PENDING/FAILED/DELIVERED`、
  尝试次数、最后错误、送达时间），**不会回滚或改变告警状态**。
- 本地通道 `InMemoryNotificationChannel`：
  - 成功即记录并打印 `[NOTIFY:DELIVERED]`；
  - 按 notificationId 幂等，重复投递打印 `[NOTIFY:DUPLICATE-SUPPRESSED]`；
  - 可通过 `POST /alerts/test/fail-next {"count":n}` 注入 n 次通道故障。
- 重试：
  - `POST /alerts/notifications/{id}/retry`：单条重试；已送达返回
    `NOTIFICATION_ALREADY_DELIVERED`，不存在返回 `NOTIFICATION_NOT_FOUND`；
    并发重试通过记录级锁保证至多成功一次。
  - `POST /alerts/notifications/retry-pending`：批量重试所有未送达通知。

## 5. REST 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/metrics/register` | 注册指标 |
| GET  | `/metrics` | 已注册指标 |
| POST | `/metrics/samples` | 单样本上报（非法 → 400 + 原因） |
| POST | `/metrics/samples/batch` | 批量上报（200/207，逐条拒绝原因） |
| GET  | `/metrics/samples?metric=&fromMillis=&toMillis=&tag.k=v` | 时间范围查询 |
| GET  | `/metrics/windows?...` | 滚动窗口聚合 |
| POST | `/alerts/rules` | 创建告警规则 |
| GET  | `/alerts/rules` | 规则列表 |
| POST | `/alerts/evaluate` | 手动推进水位并评估 |
| GET  | `/alerts/rules/{id}/states` | 各序列告警状态 |
| GET  | `/alerts/notifications?status=PENDING|FAILED|DELIVERED` | 通知列表 |
| GET  | `/alerts/notifications/{id}` | 通知投递详情 |
| POST | `/alerts/notifications/{id}/retry` | 单条重试 |
| POST | `/alerts/notifications/retry-pending` | 批量重试 |
| POST | `/alerts/test/fail-next` | 注入通知通道故障 |

## 6. 本地验证

要求 JDK 17+（工程按 Java 21 编译）与 Maven 3.6+，无需任何外部服务。

```bash
mvn test          # 运行全部测试
mvn spring-boot:run
```

测试日志按“业务模块 → 用例 → 结果”输出，例如：

```
========== [业务模块] 告警生命周期：待定/触发/恢复去抖，抖动不重复通知 ==========
  [用例] pendingRequiresConsecutiveBreachesBeforeFiring() —— 开始
  [用例] pendingRequiresConsecutiveBreachesBeforeFiring() —— 通过
  ...
  [结果] 模块「告警生命周期...」汇总: 通过=4, 失败=0
```

### 测试覆盖的业务与边界场景

| 测试类 | 覆盖业务 |
| --- | --- |
| `ConcurrentIngestionTest` | 16 线程并发上报不丢失/不重复；并发读写无半更新；13 类非法样本拒绝原因可区分；并发上报与拒绝互不干扰 |
| `LateOutOfOrderTest` | 允许范围内迟到归入旧窗口；超范围迟到被 `SAMPLE_TOO_LATE` 拒绝且不污染任何窗口；正序/逆序/乱序聚合一致；乱序到达的告警结论与通知一致 |
| `AlertLifecycleTest` | 连续窗口才触发（PENDING 去抖）；连续正常才恢复（RECOVERING 去抖）；抖动不重复通知；重复评估幂等；空窗口不误报 |
| `NotificationRetryTest` | 通道故障时 FIRING 状态保留、通知 FAILED 独立可查；恢复后显式重试且只送达一次；12 并发重试至多一次成功；批量重试；未知/已送达重试被明确拒绝 |
| `MetricsApiIntegrationTest` | REST 端到端：注册冲突 400、批量 207 拒绝码、窗口查询、规则生命周期、故障注入与重试 |

### 手工冒烟

```bash
curl -XPOST localhost:8080/metrics/register -H 'Content-Type: application/json' \
  -d '{"metric":"cpu","type":"GAUGE"}'
curl -XPOST localhost:8080/metrics/samples -H 'Content-Type: application/json' \
  -d '{"metric":"cpu","type":"GAUGE","timestampMillis":'"$(date +%s%3N)"',"value":91,"tags":{"host":"h1"}}'
curl -XPOST localhost:8080/alerts/rules -H 'Content-Type: application/json' \
  -d '{"name":"cpu-high","metric":"cpu","statistic":"AVG","operator":"GT","threshold":80,"triggerWindows":1,"recoverWindows":1}'
curl -XPOST localhost:8080/alerts/evaluate
curl localhost:8080/alerts/rules/rule-1/states
```

## 7. 配置项

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `metrics.pipeline.window-size-millis` | 10000 | 滚动窗口长度 |
| `metrics.pipeline.allowed-lateness-millis` | 5000 | 允许迟到范围 |
| `metrics.pipeline.retention-millis` | 3600000 | 样本保留期 |
| `metrics.pipeline.evaluation-period-millis` | 1000 | 水位推进/规则评估周期 |
