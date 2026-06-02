import { useCallback, useRef, useState } from 'react';
import { Recorder } from '../audio/recorder';
import { AsrSocket, AggregatorConfig, ServerMessage } from '../lib/asrSocket';

export type AsrStatus =
  | 'idle'
  | 'connecting'
  | 'recording'
  | 'reconnecting'
  | 'stopping'
  | 'error';

export interface FinalSentence {
  sentenceId: number;
  text: string;
}

const WS_URL =
  (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/asr';

const MAX_RECONNECT = 8;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 16000;

export function useAsrRecorder(config?: AggregatorConfig) {
  const [status, setStatus] = useState<AsrStatus>('idle');
  const [partialText, setPartialText] = useState('');
  const [finalSentences, setFinalSentences] = useState<FinalSentence[]>([]);
  const [error, setError] = useState<string | null>(null);

  const socketRef = useRef<AsrSocket | null>(null);
  const recorderRef = useRef<Recorder | null>(null);
  const baseSessionRef = useRef<string>('');
  const reconnectRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const shouldReconnectRef = useRef(false); // 录音中链路意外断开才重连

  const cleanup = useCallback(async () => {
    shouldReconnectRef.current = false;
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    await recorderRef.current?.stop();
    recorderRef.current = null;
    socketRef.current?.close();
    socketRef.current = null;
  }, []);

  const handleMessage = useCallback(
    (msg: ServerMessage) => {
      switch (msg.type) {
        case 'ready':
        case 'reconnected':
          reconnectRef.current = 0;
          setStatus('recording');
          break;
        case 'partial':
          setPartialText(msg.text);
          break;
        case 'final':
          setFinalSentences((prev) => [...prev, { sentenceId: msg.sentenceId, text: msg.text }]);
          setPartialText('');
          break;
        case 'reconnecting':
          // 代理↔DashScope 内部重连中（前端链路仍在），仅提示
          setStatus('reconnecting');
          break;
        case 'flushed':
        case 'pong':
          break;
        case 'done':
          setStatus('idle');
          setPartialText('');
          void cleanup();
          break;
        case 'error':
          setError(`${msg.code}: ${msg.message}`);
          setStatus('error');
          void cleanup();
          break;
      }
    },
    [cleanup]
  );

  const openSocket = useCallback(
    (sessionId: string) => {
      const socket = new AsrSocket(WS_URL, {
        onOpen: () => socket.start(sessionId, config),
        onMessage: handleMessage,
        onClose: () => {
          // 前端↔代理链路意外断开 → 退避重连（录音仍在进行）
          if (shouldReconnectRef.current && recorderRef.current) {
            scheduleReconnect();
          }
        },
        onError: () => {
          /* 紧随其后会触发 onClose，由其统一处理 */
        },
      });
      socketRef.current = socket;
      socket.connect();
    },
    [config, handleMessage]
  );

  const scheduleReconnect = useCallback(() => {
    reconnectRef.current += 1;
    if (reconnectRef.current > MAX_RECONNECT) {
      setError('连接已断开，重连多次失败');
      setStatus('error');
      void cleanup();
      return;
    }
    const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** (reconnectRef.current - 1));
    setStatus('reconnecting');
    reconnectTimerRef.current = setTimeout(() => {
      if (!shouldReconnectRef.current) return;
      socketRef.current?.close();
      // 新 sessionId 防止 batchId 跨重连冲突，保留 base 便于关联
      openSocket(`${baseSessionRef.current}_r${reconnectRef.current}`);
    }, delay);
  }, [cleanup, openSocket]);

  const start = useCallback(async () => {
    setError(null);
    setPartialText('');
    setFinalSentences([]);
    setStatus('connecting');
    reconnectRef.current = 0;
    shouldReconnectRef.current = true;
    baseSessionRef.current = 'sess_' + Date.now().toString(36);

    try {
      const recorder = new Recorder();
      recorderRef.current = recorder;
      // 帧通过 ref 发送，重连换 socket 后自动指向新连接
      await recorder.start((pcm) => socketRef.current?.sendAudio(pcm), { frameMs: 100 });
    } catch (e) {
      setError('麦克风启动失败：' + (e as Error).message);
      setStatus('error');
      await cleanup();
      return;
    }

    openSocket(baseSessionRef.current);
  }, [cleanup, openSocket]);

  const stop = useCallback(async () => {
    setStatus('stopping');
    shouldReconnectRef.current = false;
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    await recorderRef.current?.stop();
    recorderRef.current = null;
    socketRef.current?.stop(); // 等服务端回 done 再彻底清理
  }, []);

  return { status, partialText, finalSentences, error, start, stop };
}
