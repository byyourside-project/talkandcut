// 음성 인식(Web Speech API) + 음성 안내(TTS).
// 보드 버전에서는 이 모듈만 Silero VAD + SenseVoice 파이프라인으로 교체한다.

const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const START_TIMEOUT_MS = 10000;

export class Voice {
  constructor({ onFinal, onState }) {
    this.onFinal = onFinal;
    this.onState = onState;
    this.supported = !!SR;
    this.active = false;
    this.running = false;
    this.pausing = false;
    this.rec = null;
    this.startTimer = null;
    this.utterance = null;
    this.ttsEnabled = true;
  }

  createRecognition() {
    const rec = new SR();
    rec.lang = 'ko-KR';
    rec.continuous = true;
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    rec.onstart = () => {
      if (this.rec !== rec || !this.active || this.utterance || this.pausing) return;
      this.clearStartTimer();
      this.onState('listening');
    };
    rec.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (this.rec !== rec || !this.active || this.utterance || this.pausing) return;
        const r = e.results[i];
        if (r.isFinal) this.onFinal(r[0].transcript.trim());
      }
    };
    rec.onerror = ({ error }) => {
      if (this.rec !== rec || !this.active || error === 'no-speech' || error === 'aborted') return;
      this.fail(error);
    };
    rec.onend = () => {
      if (this.rec !== rec) return;
      const endedBeforeStart = this.startTimer !== null && !this.pausing;
      this.running = false;
      this.pausing = false;
      this.clearStartTimer();
      if (endedBeforeStart) {
        this.fail('start-ended');
        return;
      }
      this.listen();
    };
    return rec;
  }

  start() {
    if (!this.supported) {
      this.onState('unsupported');
      return;
    }
    if (this.active) return;
    this.active = true;
    if (this.utterance) this.onState('speaking');
    this.listen();
  }

  listen() {
    if (!this.active || this.running || this.utterance) return;
    try {
      this.rec ??= this.createRecognition();
      this.running = true;
      this.startTimer = setTimeout(() => this.fail('timeout'), START_TIMEOUT_MS);
      this.onState('connecting');
      this.rec.start();
    } catch (error) {
      this.fail(error?.name === 'NotAllowedError' ? 'not-allowed' : 'start-failed');
    }
  }

  clearStartTimer() {
    clearTimeout(this.startTimer);
    this.startTimer = null;
  }

  fail(reason) {
    this.active = false;
    this.releaseRecognition();
    this.onState(reason === 'not-allowed' ? 'denied' : 'error', reason);
  }

  stop() {
    this.active = false;
    this.cancelSpeech();
    this.releaseRecognition();
    this.onState(this.supported ? 'off' : 'unsupported');
  }

  releaseRecognition() {
    const rec = this.rec;
    const running = this.running;
    this.clearStartTimer();
    this.rec = null;
    this.running = false;
    this.pausing = false;
    if (running) {
      try { rec.abort(); } catch { /* 종료된 인식 세션 */ }
    }
  }

  pauseListening() {
    this.clearStartTimer();
    if (!this.running || this.pausing) return;
    this.pausing = true;
    try { this.rec.abort(); } catch { this.releaseRecognition(); }
  }

  resumeListening() {
    if (this.active && this.pausing) {
      this.startTimer = setTimeout(() => this.fail('timeout'), START_TIMEOUT_MS);
      this.onState('connecting');
    }
    this.listen();
  }

  cancelSpeech() {
    if (!this.utterance) return;
    this.utterance = null;
    window.speechSynthesis.cancel();
  }

  setTtsEnabled(enabled) {
    this.ttsEnabled = enabled;
    if (!enabled && this.utterance) {
      this.cancelSpeech();
      this.resumeListening();
    }
  }

  /** 안내 중에는 인식을 멈춰 부스의 음성을 명령으로 듣지 않게 한다. */
  say(text) {
    if (!this.ttsEnabled || !window.speechSynthesis || !text) return;
    this.cancelSpeech();
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'ko-KR';
    u.rate = 1.1;
    this.utterance = u;
    const resume = () => {
      if (this.utterance !== u) return;
      this.utterance = null;
      this.resumeListening();
    };
    u.onend = resume;
    u.onerror = resume;
    if (this.active) {
      this.onState('speaking');
      this.pauseListening();
    }
    try { window.speechSynthesis.speak(u); } catch { resume(); }
  }
}
