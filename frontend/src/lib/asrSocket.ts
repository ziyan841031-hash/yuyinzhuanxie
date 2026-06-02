// 与 Java 代理 /ws/asr 的 WebSocket 封装。

/** 识别侧调优（按会话覆盖后端默认），留空则用 application.yml 配置。 */
export interface RecognitionConfig {
  maxSentenceSilence?: number;          // VAD 断句静音阈值(ms)
  vocabularyId?: string;                // 热词表 ID
  semanticPunctuationEnabled?: boolean; // 语义断句
  disfluencyRemovalEnabled?: boolean;   // 顺滑(去口水词)
  inverseTextNormalizationEnabled?: boolean; // ITN 规整
  languageHints?: string[];             // 语种提示，如 ['zh','en']
}

export interface AggregatorConfig {
  charThreshold?: number;
  sentenceThreshold?: number;
  intervalMs?: number;
  silenceFlushMs?: number;
  maxBufferMs?: number;
  minCharsToFlush?: number;
  overlapSentences?: number;
  recognition?: RecognitionConfig;
}

export type ServerMessage =
  | { type: 'ready' }
  | { type: 'partial'; text: string; beginTime: number; endTime: number }
  | { type: 'final'; sentenceId: number; text: string }
  | { type: 'flushed'; batchId: string; batchSeq: number; chars: number; triggeredBy: string }
  | { type: 'reconnecting'; attempt: number }
  | { type: 'reconnected' }
  | { type: 'pong' }
  | { type: 'done' }
  | { type: 'error'; code: string; message: string };

export interface AsrSocketHandlers {
  onOpen?: () => void;
  onMessage: (msg: ServerMessage) => void;
  onClose?: (ev: CloseEvent) => void;
  onError?: (ev: Event) => void;
}

export class AsrSocket {
  private ws: WebSocket | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private url: string,
    private handlers: AsrSocketHandlers,
    private heartbeatMs = 15000
  ) {}

  connect(): void {
    this.ws = new WebSocket(this.url);
    this.ws.binaryType = 'arraybuffer';
    this.ws.onopen = () => {
      this.startHeartbeat();
      this.handlers.onOpen?.();
    };
    this.ws.onmessage = (e) => {
      try {
        this.handlers.onMessage(JSON.parse(e.data) as ServerMessage);
      } catch {
        /* 忽略非 JSON */
      }
    };
    this.ws.onclose = (e) => {
      this.stopHeartbeat();
      this.handlers.onClose?.(e);
    };
    this.ws.onerror = (e) => this.handlers.onError?.(e);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    if (this.heartbeatMs > 0) {
      this.pingTimer = setInterval(() => this.sendJson({ type: 'ping' }), this.heartbeatMs);
    }
  }

  private stopHeartbeat(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  start(sessionId: string, config?: AggregatorConfig): void {
    this.sendJson({ type: 'start', sessionId, config: config ?? {} });
  }

  sendAudio(pcm: ArrayBuffer): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(pcm);
  }

  stop(): void {
    this.sendJson({ type: 'stop' });
  }

  close(): void {
    this.stopHeartbeat();
    this.ws?.close();
    this.ws = null;
  }

  get isOpen(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  private sendJson(obj: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }
}
