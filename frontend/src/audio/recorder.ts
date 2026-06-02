// 麦克风采集：getUserMedia -> AudioContext -> pcm-worklet(重采样16k/Int16) -> onFrame
export interface RecorderOptions {
  targetRate?: number;
  frameMs?: number;
}

export class Recorder {
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private node: AudioWorkletNode | null = null;

  async start(onFrame: (pcm: ArrayBuffer) => void, opts: RecorderOptions = {}): Promise<void> {
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      this.ctx = new AudioContext();
      await this.ctx.audioWorklet.addModule('/pcm-worklet.js');

      const source = this.ctx.createMediaStreamSource(this.stream);
      this.node = new AudioWorkletNode(this.ctx, 'pcm-downsampler', {
        processorOptions: {
          targetRate: opts.targetRate ?? 16000,
          frameMs: opts.frameMs ?? 100,
        },
      });
      this.node.port.onmessage = (e: MessageEvent) => onFrame(e.data as ArrayBuffer);

      // 接一个零增益节点到 destination，保证 worklet 被驱动，同时不外放（防回声）
      const mute = this.ctx.createGain();
      mute.gain.value = 0;
      source.connect(this.node);
      this.node.connect(mute);
      mute.connect(this.ctx.destination);
    } catch (e) {
      await this.stop(); // 部分初始化失败也要释放麦克风/AudioContext
      throw e;
    }
  }

  async stop(): Promise<void> {
    this.node?.port.close();
    this.node?.disconnect();
    this.node = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    if (this.ctx) {
      await this.ctx.close();
      this.ctx = null;
    }
  }
}
