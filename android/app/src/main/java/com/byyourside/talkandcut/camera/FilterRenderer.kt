package com.byyourside.talkandcut.camera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.filter.Param
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 웹 src/glfilter.js 보정 렌더러 이식. 카메라 프레임(SurfaceTexture, OES 텍스처)을
 * 뷰 비율로 가운데 cover-crop 해서 보정 후 그린다.
 *
 * 셰이더는 웹과 uniform 이름·계산식이 같다. 차이는 카메라 입력을 위한 것뿐:
 *  - sampler2D → samplerExternalOES
 *  - VS에 u_texMatrix(SurfaceTexture 변환), u_rot(센서 → 화면 회전, 시계 방향 도) 추가
 */
class FilterRenderer(
    private val view: GLSurfaceView,
    /** 새 SurfaceTexture가 만들어졌을 때(메인 스레드). 카메라 Preview를 여기에 연결한다. */
    private val onSurfaceTexture: (SurfaceTexture) -> Unit,
    /** 첫 프레임이 들어왔을 때(메인 스레드). */
    private val onFirstFrame: () -> Unit,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val main = Handler(Looper.getMainLooper())

    @Volatile var params: FilterParams = FilterParams.NEUTRAL
    /** 버퍼를 화면에 바로 세우기 위해 셰이더에서 더 돌려야 하는 각도와 좌우반전 여부. */
    @Volatile private var shaderRotation = 0
    @Volatile private var flip = true
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var rotation = 0
    @Volatile private var gotFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var texture = 0
    private var viewW = 0
    private var viewH = 0
    private val texMatrix = FloatArray(16)
    private val u = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** 카메라 버퍼 크기. CameraX SurfaceRequest에서 받는다. */
    fun setSource(width: Int, height: Int) { srcW = width; srcH = height }

    /**
     * CameraX TransformationInfo.
     * hasCameraTransform이면 SurfaceTexture 변환 행렬에 센서 회전과 전면 거울 반전이 이미 들어 있다
     * (TextureView와 같은 방식). 이때는 셰이더에서 더 돌리거나 뒤집지 않는다.
     * 아니면 rotationDegrees만큼 돌리고 웹처럼 flip으로 거울 모드를 만든다.
     */
    fun setTransform(rotationDegrees: Int, hasCameraTransform: Boolean) {
        rotation = ((rotationDegrees % 360) + 360) % 360
        shaderRotation = if (hasCameraTransform) 0 else rotation
        flip = !hasCameraTransform
    }

    /** 카메라가 멈췄을 때. 다음 프레임이 오면 onFirstFrame을 다시 부른다. */
    fun resetStreaming() { gotFrame = false }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VS, FS)
        for (n in UNIFORMS) u[n] = GLES20.glGetUniformLocation(program, n)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture?.release()
        val st = SurfaceTexture(texture).also { it.setOnFrameAvailableListener(this) }
        surfaceTexture = st
        gotFrame = false
        main.post { onSurfaceTexture(st) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (!gotFrame) {
            gotFrame = true
            main.post(onFirstFrame)
        }
        view.requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // 회전을 반영한 소스 크기(화면 기준)
        val rot = rotation
        val w = if (rot % 180 == 0) srcW else srcH
        val h = if (rot % 180 == 0) srcH else srcW
        if (w == 0 || h == 0 || viewW == 0 || viewH == 0) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(u.getValue("u_tex"), 0)

        // 웹 render()와 같은 cover-crop
        val srcAR = w.toFloat() / h
        val dstAR = viewW.toFloat() / viewH
        var sx = 1f
        var sy = 1f
        if (srcAR > dstAR) sx = dstAR / srcAR else sy = srcAR / dstAR
        GLES20.glUniform2f(u.getValue("u_scale"), sx, sy)
        GLES20.glUniform2f(u.getValue("u_offset"), (1 - sx) / 2, (1 - sy) / 2)
        GLES20.glUniform1f(u.getValue("u_flip"), if (flip) 1f else 0f)
        GLES20.glUniformMatrix4fv(u.getValue("u_texMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(u.getValue("u_rot"), shaderRotation.toFloat())

        val p = params
        GLES20.glUniform1f(u.getValue("u_bri"), p[Param.BRIGHTNESS] / 100f)
        GLES20.glUniform1f(u.getValue("u_con"), p[Param.CONTRAST] / 100f)
        GLES20.glUniform1f(u.getValue("u_sat"), p[Param.SATURATION] / 100f)
        GLES20.glUniform1f(u.getValue("u_warm"), p[Param.WARMTH] / 100f)
        GLES20.glUniform1f(u.getValue("u_tint"), p[Param.TINT] / 100f)
        GLES20.glUniform1f(u.getValue("u_fade"), p[Param.FADE] / 100f)
        GLES20.glUniform1f(u.getValue("u_vig"), p[Param.VIGNETTE] / 100f)
        GLES20.glUniform1f(u.getValue("u_grain"), p[Param.GRAIN] / 100f)
        GLES20.glUniform1f(u.getValue("u_seed"), (System.currentTimeMillis() % 1000) / 100f)

        val loc = GLES20.glGetAttribLocation(program, "a_pos")
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** GL 스레드에서 호출. */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun buildProgram(vs: String, fs: String): Int {
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

    companion object {
        private val UNIFORMS = listOf(
            "u_tex", "u_scale", "u_offset", "u_flip", "u_texMatrix", "u_rot",
            "u_bri", "u_con", "u_sat", "u_warm", "u_tint", "u_fade", "u_vig", "u_grain", "u_seed",
        )

        private const val VS = """
attribute vec2 a_pos;
uniform vec2 u_scale;
uniform vec2 u_offset;
uniform float u_flip;
uniform mat4 u_texMatrix;
uniform float u_rot;
varying vec2 v_uv;
varying vec2 v_screen;
// 화면(바로 선) 좌표 → 카메라 버퍼 좌표. 둘 다 좌상단 원점.
vec2 toBuffer(vec2 p) {
  if (u_rot > 225.0) return vec2(1.0 - p.y, p.x);
  if (u_rot > 135.0) return vec2(1.0 - p.x, 1.0 - p.y);
  if (u_rot > 45.0) return vec2(p.y, 1.0 - p.x);
  return p;
}
void main() {
  vec2 uv = vec2((a_pos.x + 1.0) * 0.5, 1.0 - (a_pos.y + 1.0) * 0.5);
  v_screen = uv;
  if (u_flip > 0.5) uv.x = 1.0 - uv.x;
  uv = uv * u_scale + u_offset;
  vec2 b = toBuffer(uv);
  v_uv = (u_texMatrix * vec4(b.x, 1.0 - b.y, 0.0, 1.0)).xy;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}"""

        private const val FS = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 v_uv;
varying vec2 v_screen;
uniform samplerExternalOES u_tex;
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
}
