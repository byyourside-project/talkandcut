package com.byyourside.talkandcut.filter

import android.opengl.GLES20

/**
 * 웹 src/glfilter.js 프래그먼트 셰이더. 미리보기(카메라 OES 텍스처)와
 * 합성(오프스크린, 비트맵 2D 텍스처)이 같은 계산식을 쓰도록 한 곳에 둔다.
 */
object FilterShader {
    val PARAM_UNIFORMS = listOf("u_bri", "u_con", "u_sat", "u_warm", "u_tint", "u_fade", "u_vig", "u_grain", "u_seed")

    fun fragment(external: Boolean): String {
        val head = if (external) {
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform samplerExternalOES u_tex;\n"
        } else {
            "precision mediump float;\nuniform sampler2D u_tex;\n"
        }
        return head + BODY
    }

    /** 보정값 uniform을 웹 render()와 같이 /100 해서 넣는다. */
    fun setParams(u: Map<String, Int>, p: FilterParams, seed: Float) {
        GLES20.glUniform1f(u.getValue("u_bri"), p[Param.BRIGHTNESS] / 100f)
        GLES20.glUniform1f(u.getValue("u_con"), p[Param.CONTRAST] / 100f)
        GLES20.glUniform1f(u.getValue("u_sat"), p[Param.SATURATION] / 100f)
        GLES20.glUniform1f(u.getValue("u_warm"), p[Param.WARMTH] / 100f)
        GLES20.glUniform1f(u.getValue("u_tint"), p[Param.TINT] / 100f)
        GLES20.glUniform1f(u.getValue("u_fade"), p[Param.FADE] / 100f)
        GLES20.glUniform1f(u.getValue("u_vig"), p[Param.VIGNETTE] / 100f)
        GLES20.glUniform1f(u.getValue("u_grain"), p[Param.GRAIN] / 100f)
        GLES20.glUniform1f(u.getValue("u_seed"), seed)
    }

    fun buildProgram(vs: String, fs: String): Int {
        val prog = GLES20.glCreateProgram()
        for ((type, src) in listOf(GLES20.GL_VERTEX_SHADER to vs, GLES20.GL_FRAGMENT_SHADER to fs)) {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error(GLES20.glGetShaderInfoLog(s))
            GLES20.glAttachShader(prog, s)
        }
        GLES20.glLinkProgram(prog)
        return prog
    }

    private const val BODY = """varying vec2 v_uv;
varying vec2 v_screen;
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
}"""
}
