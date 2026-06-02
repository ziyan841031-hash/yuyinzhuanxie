import { useCallback, useRef, useState } from 'react';
import { Recorder } from '../audio/recorder';
import { AsrSocket, AggregatorConfig, ServerMessage } from '../lib/asrSocket';

export type AsrStatus =
  | 'idle'
  | 'connecting'
  | 'recording'
  | 'stopping'
  | 'error';

export interface FinalSentence {
  sentenceId: number;
  text: string;
}

const WS_URL =
  (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/asr';

export function useAsrRecorder(config?: AggregatorConfig) {
  const [status, setStatus] = useState<AsrStatus>('idle');
  const [partialText, setPartialText] = useState('');
  const [finalSentences, setFinalSentences] = useState<FinalSentence[]>([]);
  const [error, setError] = useState<string | null>(null);

  const socketRef = useRef<AsrSocket | null>(null);
  const recorderRef = useRef<Recorder | null>(null);

  const cleanup = useCallback(async () => {
    await recorderRef.current?.stop();
    recorderRef.current = null;
    socketRef.current?.close();
    socketRef.current = null;
  }, []);

  const handleMessage = useCallback(
    (msg: ServerMessage) => {
      switch (msg.type) {
        case 'ready':
          setStatus('recording');
          break;
        case 'partial':
          setPartialText(msg.text);
          break;
        case 'final':
          setFinalSentences((prev) => [...prev, { sentenceId: msg.sentenceId, text: msg.text }]);
          setPartialText('');
          break;
        case 'flushed':
          // 可在此做“已上报 N 字”提示
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

  const start = useCallback(async () => {
    setError(null);
    setPartialText('');
    setFinalSentences([]);
    setStatus('connecting');

    const sessionId = 'sess_' + Date.now().toString(36);
    const socket = new AsrSocket(WS_URL, {
      onOpen: () => socket.start(sessionId, config),
      onMessage: handleMessage,
      onClose: () => {
        if (recorderRef.current) void cleanup();
      },
      onError: () => {
        setError('WebSocket 连接错误');
        setStatus('error');
        void cleanup();
      },
    });
    socketRef.current = socket;
    socket.connect();

    try {
      const recorder = new Recorder();
      recorderRef.current = recorder;
      await recorder.start((pcm) => socket.sendAudio(pcm), { frameMs: 100 });
    } catch (e) {
      setError('麦克风启动失败：' + (e as Error).message);
      setStatus('error');
      await cleanup();
    }
  }, [config, handleMessage, cleanup]);

  const stop = useCallback(async () => {
    setStatus('stopping');
    await recorderRef.current?.stop();
    recorderRef.current = null;
    socketRef.current?.stop(); // 等服务端回 done 再彻底清理
  }, []);

  return { status, partialText, finalSentences, error, start, stop };
}
