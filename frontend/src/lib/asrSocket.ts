// 与 Java 代理 /ws/asr 的 WebSocket 封装。
export interface AggregatorConfig {
  charThreshold?: number;
  sentenceThreshold?: number;
  intervalMs?: number;
  silenceFlushMs?: number;
  maxBufferMs?: number;
  minCharsToFlush?: number;
  overlapSentences?: number;
}

export type ServerMessage =
  | { type: 'ready' }
  | { type: 'partial'; text: string; beginTime: number; endTime: number }
  | { type: 'final'; sentenceId: number; text: string }
  | { type: 'flushed'; batchId: string; batchSeq: number; chars: number; triggeredBy: string }
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

  constructor(private url: string, private handlers: AsrSocketHandlers) {}

  connect(): void {
    this.ws = new WebSocket(this.url);
    this.ws.binaryType = 'arraybuffer';
    this.ws.onopen = () => this.handlers.onOpen?.();
    this.ws.onmessage = (e) => {
      try {
        this.handlers.onMessage(JSON.parse(e.data) as ServerMessage);
      } catch {
        /* 忽略非 JSON */
      }
    };
    this.ws.onclose = (e) => this.handlers.onClose?.(e);
    this.ws.onerror = (e) => this.handlers.onError?.(e);
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
