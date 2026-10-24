// 음성 인식(Web Speech API) + 음성 안내(TTS).
// 보드 버전에서는 이 모듈만 Silero VAD + SenseVoice 파이프라인으로 교체한다.

const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

export class Voice {
  constructor({ onFinal, onInterim, onState }) {
    this.onFinal = onFinal;
    this.onInterim = onInterim;
    this.onState = onState;
    this.supported = !!SR;
    this.active = false;
    this.speaking = false;
    this.ttsEnabled = true;
    if (!this.supported) return;

    const rec = new SR();
    rec.lang = 'ko-KR';
    rec.continuous = true;
    rec.interimResults = true;
    rec.maxAlternatives = 1;
    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) this.onFinal(r[0].transcript.trim());
        else interim += r[0].transcript;
      }
      if (interim) this.onInterim(interim.trim());
    };
    rec.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        this.active = false;
        this.onState('denied');
      } else if (e.error !== 'no-speech' && e.error !== 'aborted') {
        this.onState('error', e.error);
      }
    };
    rec.onend = () => {
      // continuous 모드여도 브라우저가 주기적으로 끊기 때문에 자동 재시작
      if (this.active && !this.speaking) {
        try { rec.start(); } catch { /* 이미 시작됨 */ }
      } else if (!this.active) {
        this.onState('off');
      }
    };
    this.rec = rec;
  }

  start() {
    if (!this.supported) return;
    this.active = true;
    try { this.rec.start(); } catch { /* 이미 시작됨 */ }
    this.onState('listening');
  }

  stop() {
    if (!this.supported) return;
    this.active = false;
    this.rec.stop();
    this.onState('off');
  }

  /** 짧은 음성 안내. 말하는 동안은 인식을 멈춰 자기 목소리를 명령으로 듣지 않게 한다. */
  say(text) {
    if (!this.ttsEnabled || !('speechSynthesis' in window) || !text) return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'ko-KR';
    u.rate = 1.1;
    const resume = () => {
      this.speaking = false;
      if (this.active) { try { this.rec.start(); } catch { /* noop */ } }
    };
    u.onend = resume;
    u.onerror = resume;
    if (this.active && this.rec) {
      this.speaking = true;
      try { this.rec.abort(); } catch { /* noop */ }
    }
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }
}
