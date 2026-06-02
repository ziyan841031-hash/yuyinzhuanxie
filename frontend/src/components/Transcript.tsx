import { FinalSentence } from '../hooks/useAsrRecorder';

export function Transcript({
  finalSentences,
  partialText,
}: {
  finalSentences: FinalSentence[];
  partialText: string;
}) {
  return (
    <div
      style={{
        minHeight: 200,
        border: '1px solid #ddd',
        borderRadius: 8,
        padding: 16,
        lineHeight: 1.8,
        fontSize: 16,
        background: '#fafafa',
      }}
    >
      {finalSentences.map((s) => (
        <span key={s.sentenceId} style={{ color: '#111' }}>
          {s.text}
        </span>
      ))}
      {partialText && <span style={{ color: '#999' }}>{partialText}</span>}
      {finalSentences.length === 0 && !partialText && (
        <span style={{ color: '#bbb' }}>点击“开始收音”后，实时转写文本会显示在这里…</span>
      )}
    </div>
  );
}
