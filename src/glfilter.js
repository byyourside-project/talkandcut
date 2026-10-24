// WebGL 보정 렌더러. 덧씌우는 오버레이가 아니라 픽셀 단위로 밝기·대비·채도·색온도 등을 조절한다.
// 보드 버전에서는 같은 셰이더를 Adreno GPU(OpenGL ES)에 그대로 올릴 수 있다.

const VS = `
attribute vec2 a_pos;
uniform vec2 u_scale;
uniform vec2 u_offset;
uniform float u_flip;
varying vec2 v_uv;
varying vec2 v_screen;
void main() {
  vec2 uv = vec2((a_pos.x + 1.0) * 0.5, 1.0 - (a_pos.y + 1.0) * 0.5);
  v_screen = uv;
  if (u_flip > 0.5) uv.x = 1.0 - uv.x;
  v_uv = uv * u_scale + u_offset;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

const FS = `
precision mediump float;
varying vec2 v_uv;
varying vec2 v_screen;
uniform sampler2D u_tex;
uniform float u_bri, u_con, u_sat, u_warm, u_tint, u_fade, u_vig, u_grain, u_seed;

float rand(vec2 c) {
  return fract(sin(dot(c, vec2(12.9898, 78.233)) + u_seed) * 43758.5453);
}

void main() {
  vec3 c = texture2D(u_tex, v_uv).rgb;
  // 노출(밝기): 곱셈 방식이라 하이라이트가 덜 날아간다
  c *= pow(2.0, u_bri * 0.9);
  // 대비
  c = (c - 0.5) * (1.0 + u_con * 0.8) + 0.5;
  // 채도
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, 1.0 + u_sat);
  // 색온도(따뜻/차갑) & 색조(마젠타/그린)
  c += vec3(u_warm * 0.09, -u_tint * 0.06, -u_warm * 0.09);
  c.rb += u_tint * 0.025;
  // 페이드: 검정을 들어 올리고 흰색을 살짝 내림
  c = c * (1.0 - u_fade * 0.24) + u_fade * 0.13;
  // 비네팅
  float d = length((v_screen - 0.5) * vec2(1.0, 1.1));
  c *= 1.0 - u_vig * 0.7 * smoothstep(0.25, 0.8, d);
  // 그레인
  c += (rand(gl_FragCoord.xy) - 0.5) * u_grain * 0.2;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}`;

export class FilterRenderer {
  constructor(canvas) {
    this.canvas = canvas;
    const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true, premultipliedAlpha: false });
    if (!gl) throw new Error('WebGL을 쓸 수 없는 브라우저예요');
    this.gl = gl;
    const prog = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, VS], [gl.FRAGMENT_SHADER, FS]]) {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      gl.attachShader(prog, s);
    }
    gl.linkProgram(prog);
    gl.useProgram(prog);
    this.prog = prog;

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, 'a_pos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

    this.tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);

    this.u = {};
    for (const n of ['u_scale', 'u_offset', 'u_flip', 'u_bri', 'u_con', 'u_sat', 'u_warm', 'u_tint', 'u_fade', 'u_vig', 'u_grain', 'u_seed']) {
      this.u[n] = gl.getUniformLocation(prog, n);
    }
  }

  setSize(w, h) {
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
  }

  /**
   * source를 캔버스 비율에 맞춰 가운데 cover-crop 해서 보정 후 그린다.
   * @param {TexImageSource} source
   * @param {number} srcW
   * @param {number} srcH
   * @param {object} params -100..100 범위 파라미터
   * @param {{flip?:boolean, seed?:number}} opt
   */
  render(source, srcW, srcH, params, opt = {}) {
    const gl = this.gl;
    const { width: W, height: H } = this.canvas;
    if (!srcW || !srcH) return;
    gl.viewport(0, 0, W, H);
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);

    const srcAR = srcW / srcH, dstAR = W / H;
    let sx = 1, sy = 1;
    if (srcAR > dstAR) sx = dstAR / srcAR; else sy = srcAR / dstAR;
    gl.uniform2f(this.u.u_scale, sx, sy);
    gl.uniform2f(this.u.u_offset, (1 - sx) / 2, (1 - sy) / 2);
    gl.uniform1f(this.u.u_flip, opt.flip ? 1 : 0);

    const p = params;
    gl.uniform1f(this.u.u_bri, (p.brightness ?? 0) / 100);
    gl.uniform1f(this.u.u_con, (p.contrast ?? 0) / 100);
    gl.uniform1f(this.u.u_sat, (p.saturation ?? 0) / 100);
    gl.uniform1f(this.u.u_warm, (p.warmth ?? 0) / 100);
    gl.uniform1f(this.u.u_tint, (p.tint ?? 0) / 100);
    gl.uniform1f(this.u.u_fade, (p.fade ?? 0) / 100);
    gl.uniform1f(this.u.u_vig, (p.vignette ?? 0) / 100);
    gl.uniform1f(this.u.u_grain, (p.grain ?? 0) / 100);
    gl.uniform1f(this.u.u_seed, opt.seed ?? 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
}
