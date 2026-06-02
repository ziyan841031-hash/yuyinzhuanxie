# 实时语音转写工具（FUN-ASR）

点击收音 → 阿里百炼 FUN-ASR 实时转写 → 可配置聚合 → 成批送分析后端（占位）。
本仓库负责「前半段」：从转写到把提取内容送到后台。整体设计见 [`docs/设计方案.md`](docs/设计方案.md)。

```
React(16k PCM) ──WS──> Spring Boot 代理 ──WSS──> 百炼 FUN-ASR
                          └─ 聚合触发器 ──HTTP──> 分析后端(占位 /mock/analysis)
```

## 目录
- `backend/`  Spring Boot ASR 代理（WS 透传 + DashScope 客户端 + 可配置聚合 + 投递 + 占位后端）
- `frontend/` React + AudioWorklet（麦克风重采样 16k/Int16）+ 实时回显
- `docs/`     设计方案

## 运行

### 1. 后端（需 JDK 17+）
```bash
export DASHSCOPE_API_KEY=sk-xxxx          # 阿里百炼 API Key，仅服务端
cd backend
mvn spring-boot:run                        # 监听 8080，WS 端点 /ws/asr，占位后端 /mock/analysis
```

### 2. 前端（需 Node 18+）
```bash
cd frontend
npm install
npm run dev                                # http://localhost:5173 ，/ws 已代理到 8080
```

打开页面点「开始收音」，浏览器会请求麦克风权限（注意：getUserMedia 需 https 或 localhost）。

## 配置怎么配
**完整逐项说明见 [`docs/配置说明.md`](docs/配置说明.md)**（含默认值、调优场景、按会话覆盖示例）。下面是速览。

三个配置来源：环境变量（仅 `DASHSCOPE_API_KEY`）→ `application.yml`（全局默认）→ 前端 `useAsrRecorder({...})`（按会话临时覆盖，即时生效）。

聚合触发器 `asr.aggregator.*`（决定攒多少、何时送后端，`<=0` 关闭）：

| 配置 | 含义 | 默认 |
|---|---|---|
| `charThreshold` | 累计字数达到即上报 | 200 |
| `sentenceThreshold` | 累计句数（0=关） | 0 |
| `intervalMs` | 周期上报间隔 | 8000 |
| `silenceFlushMs` | 说话停顿断点 | 1500 |
| `maxBufferMs` | 最大滞留兜底（强制） | 15000 |
| `minCharsToFlush` | 时间/停顿触发的最小批字数 | 10 |
| `overlapSentences` | 批间上下文重叠句数 | 1 |

识别调优 `asr.dashscope.*`（M2/M3，默认不下发，按需开）：`vocabularyId` 热词、`semanticPunctuationEnabled` 语义断句、`disfluencyRemovalEnabled` 顺滑去口水词、`inverseTextNormalizationEnabled` ITN 规整、`languageHints` 语种、`maxSentenceSilence` 断句静音阈值。

按会话覆盖示例（前端）：
```ts
useAsrRecorder({
  charThreshold: 300, silenceFlushMs: 1200,        // 聚合触发器
  recognition: { vocabularyId: 'vocab-xxx', disfluencyRemovalEnabled: true }, // 识别参数
});
```

完整性保证：只在 `sentence_end` 定稿句处切批（不切半句）+ 批间 overlap 上下文 + 结束强制 flush。

## 稳定性（M4）
| 能力 | 说明 | 关键配置 |
|---|---|---|
| 代理↔DashScope 自动重连 | 断线退避重连，间隙音频缓冲后补发，聚合缓冲不丢；超过上限才上报致命错误 | `reconnect-base-ms` / `reconnect-max-ms` / `max-reconnect-attempts` / `audio-buffer-frames` |
| 滚动会话 | 接近单任务时长上限前预启动新任务，就绪后无缝切换（overlap），防被动断流 | `session-max-seconds` |
| 前端↔代理自动重连 | 前端链路断开后退避重连并继续送音频；用新 sessionId 防 batchId 冲突 | 前端 `MAX_RECONNECT` 等常量 |
| 应用层心跳 | 前端每 15s `ping`，服务端 `pong`；DashScope 侧 `heartbeat=true` 静音保活 | `asr.dashscope.heartbeat` |
| 投递持久化队列 | 内存快重试耗尽后落盘，进程重启自动续传，保证内容不丢；幂等键 `X-Batch-Id` | `queue-dir` / `queue-flush-ms` |
| 并发安全发送 | `ConcurrentWebSocketSessionDecorator` 序列化多线程对同一连接的写 | — |

## 里程碑
- [x] **M1** 打通链路 + 可配置聚合触发器 + 占位投递
- [x] **M2/M3** 综合触发器 + 识别调优（热词/语义标点/顺滑/ITN/语种，可配置、可按会话覆盖）
- [x] **M4** 双链路重连、心跳、滚动会话、投递磁盘队列
- [ ] 对接真实分析后端（改 `asr.delivery.backend-url` 即可）
