import { useAsrRecorder } from '../hooks/useAsrRecorder';
import { Transcript } from './Transcript';

export function RecorderPanel() {
  // 触发器可在此按需覆盖后端默认值；留空则用后端 application.yml 配置
  const { status, partialText, finalSentences, error, start, stop } = useAsrRecorder({
    charThreshold: 200,
    intervalMs: 8000,
    silenceFlushMs: 1500,
  });

  const active =
    status === 'recording' ||
    status === 'connecting' ||
    status === 'reconnecting' ||
    status === 'stopping';

  return (
    <div style={{ maxWidth: 720, margin: '40px auto', fontFamily: 'system-ui, sans-serif' }}>
      <h2>实时语音转写</h2>
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
        {!active ? (
          <button onClick={() => void start()} style={btn('#1677ff')}>
            🎙️ 开始收音
          </button>
        ) : (
          <button
            onClick={() => void stop()}
            style={btn('#ff4d4f')}
            disabled={status === 'connecting' || status === 'reconnecting' || status === 'stopping'}
          >
            ⏹️ 停止
          </button>
        )}
        <StatusBadge status={status} />
      </div>
      {error && <div style={{ color: '#ff4d4f', marginBottom: 12 }}>⚠️ {error}</div>}
      <Transcript finalSentences={finalSentences} partialText={partialText} />
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    idle: '空闲',
    connecting: '连接中…',
    recording: '● 收音中',
    reconnecting: '↻ 重连中…',
    stopping: '停止中…',
    error: '错误',
  };
  return <span style={{ color: '#666' }}>{map[status] ?? status}</span>;
}

function btn(bg: string): React.CSSProperties {
  return {
    background: bg,
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '8px 16px',
    fontSize: 15,
    cursor: 'pointer',
  };
}
