// 음성 인식(Web Speech API) + 음성 안내(TTS).
// 보드 버전에서는 이 모듈만 Silero VAD + SenseVoice 파이프라인으로 교체한다.

const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

export class Voice {
  constructor({ onFinal, onState }) {
    this.onFinal = onFinal;
    this.onState = onState;
    this.supported = !!SR;
    this.active = false;
    this.running = false;
    this.utterance = null;
    this.ttsEnabled = true;
    if (!this.supported) return;

    const rec = new SR();
    rec.lang = 'ko-KR';
    rec.continuous = true;
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    rec.onstart = () => {
      if (this.active && !this.utterance) this.onState('listening');
    };
    rec.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (!this.active || this.utterance) return;
        const r = e.results[i];
        if (r.isFinal) this.onFinal(r[0].transcript.trim());
      }
    };
    rec.onerror = ({ error }) => {
      if (!this.active || error === 'no-speech' || error === 'aborted') return;
      // 네트워크나 장치 오류는 자동으로 반복하지 않고 메뉴에서 재시도한다.
      this.active = false;
      this.onState(error === 'not-allowed' || error === 'service-not-allowed' ? 'denied' : 'error');
    };
    rec.onend = () => {
      this.running = false;
      this.listen();
    };
    this.rec = rec;
  }

  start() {
    if (!this.supported) {
      this.onState('unsupported');
      return;
    }
    if (this.active) return;
    this.active = true;
    this.onState(this.utterance ? 'speaking' : 'connecting');
    this.listen();
  }

  listen() {
    if (!this.active || this.running || this.utterance) return;
    try {
      this.running = true;
      this.rec.start();
    } catch {
      this.running = false;
      this.active = false;
      this.onState('error');
    }
  }

  stop() {
    this.active = false;
    this.cancelSpeech();
    this.abortListening();
    this.onState(this.supported ? 'off' : 'unsupported');
  }

  abortListening() {
    if (this.running) {
      try { this.rec.abort(); } catch { this.running = false; }
    }
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
      if (this.active) this.onState('connecting');
      this.listen();
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
      if (this.active) this.onState('connecting');
      this.listen();
    };
    u.onend = resume;
    u.onerror = resume;
    if (this.active) {
      this.onState('speaking');
      this.abortListening();
    }
    try { window.speechSynthesis.speak(u); } catch { resume(); }
  }
}
