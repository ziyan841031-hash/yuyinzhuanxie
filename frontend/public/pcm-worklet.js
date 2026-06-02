// AudioWorklet：把麦克风 Float32(浏览器原生采样率，通常 48k) 重采样到 16k，
// 转成 Int16 小端 PCM，按 frameMs 一帧 post 回主线程。
// 注意：本文件必须放在 public/ 下以独立 URL 加载（不能被打包进模块）。

class PcmDownsampler extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    this.targetRate = opts.targetRate || 16000;
    this.frameSamples = Math.round(this.targetRate * ((opts.frameMs || 100) / 1000));
    this.ratio = sampleRate / this.targetRate; // sampleRate 是 worklet 全局：输入采样率
    this.residual = new Float32Array(0);        // 尚未消费完的输入样本
    this.readPos = 0;                            // 在 residual 中的分数读取位置
    this.out = new Int16Array(this.frameSamples);
    this.outLen = 0;
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0 || !input[0]) return true;
    const ch = input[0];

    // 拼接残留 + 新输入
    const buf = new Float32Array(this.residual.length + ch.length);
    buf.set(this.residual, 0);
    buf.set(ch, this.residual.length);

    let pos = this.readPos;
    // 线性插值重采样
    while (pos + 1 < buf.length) {
      const i = Math.floor(pos);
      const frac = pos - i;
      const sample = buf[i] * (1 - frac) + buf[i + 1] * frac;
      let s = Math.max(-1, Math.min(1, sample));
      this.out[this.outLen++] = s < 0 ? s * 0x8000 : s * 0x7fff;
      if (this.outLen >= this.frameSamples) {
        // 拷贝一份转移所有权，避免复用缓冲
        const frame = this.out.slice(0, this.frameSamples);
        this.port.postMessage(frame.buffer, [frame.buffer]);
        this.out = new Int16Array(this.frameSamples);
        this.outLen = 0;
      }
      pos += this.ratio;
    }

    // 保留未消费的尾部
    const consumed = Math.floor(pos);
    this.residual = buf.slice(consumed);
    this.readPos = pos - consumed;
    return true;
  }
}

registerProcessor('pcm-downsampler', PcmDownsampler);
