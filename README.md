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

## 聚合触发器（可配置）
后端默认值在 `backend/src/main/resources/application.yml` 的 `asr.aggregator`；
前端也可在 `start` 消息的 `config` 里按会话覆盖（见 `useAsrRecorder` 调用处）。

| 配置 | 含义 | 默认 |
|---|---|---|
| `charThreshold` | 累计字数达到即上报 | 200 |
| `sentenceThreshold` | 累计句数（0=关） | 0 |
| `intervalMs` | 周期上报间隔 | 8000 |
| `silenceFlushMs` | 说话停顿断点 | 1500 |
| `maxBufferMs` | 最大滞留兜底（强制） | 15000 |
| `minCharsToFlush` | 时间/停顿触发的最小批字数 | 10 |
| `overlapSentences` | 批间上下文重叠句数 | 1 |

完整性保证：只在 `sentence_end` 定稿句处切批（不切半句）+ 批间 overlap 上下文 + 结束强制 flush。

## 里程碑
- [x] **M1** 打通链路 + 可配置聚合触发器 + 占位投递
- [ ] M2/M3 更多触发补充与调优
- [ ] M4 双链路重连、心跳、滚动会话、投递本地队列
- [ ] 对接真实分析后端（改 `asr.delivery.backend-url` 即可）
