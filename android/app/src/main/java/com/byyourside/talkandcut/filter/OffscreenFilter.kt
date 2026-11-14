package com.byyourside.talkandcut.filter

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * 웹 frames.js의 오프스크린 FilterRenderer. 원본 컷(비트맵)에 보정을 다시 적용해서
 * 새 비트맵으로 돌려준다(비파괴). 전용 스레드 하나에 EGL pbuffer 컨텍스트를 둔다.
 *
 * 컷은 이미 거울 모드로 저장돼 있어 웹과 달리 flip하지 않는다.
 */
class OffscreenFilter {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "offscreen-filter") }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var srcTex = 0
    private var dstTex = 0
    private var fbo = 0
    private var fboW = 0
    private var fboH = 0
    private val u = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** shot을 w×h로 cover-crop + 보정. seed는 그레인 패턴(웹: 칸 i * 3.1). */
    suspend fun render(shot: Bitmap, w: Int, h: Int, params: FilterParams, seed: Float = 0f): Bitmap =
        withContext(dispatcher) { draw(shot, w, h, params, seed) }

    /** 보정 적용된 사진들의 평균 색(0~1). 프레임 추천 입력. 웹 averageColor와 같이 24×18로 줄여서 잰다. */
    suspend fun averageColor(shots: List<Bitmap?>, params: FilterParams): DoubleArray? = withContext(dispatcher) {
        val valid = shots.filterNotNull()
        if (valid.isEmpty()) return@withContext null
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        val px = IntArray(24 * 18)
        for (s in valid) {
            val small = draw(s, 24, 18, params, 0f)
            small.getPixels(px, 0, 24, 0, 0, 24, 18)
            small.recycle()
            for (c in px) {
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
            }
        }
        doubleArrayOf(r / n / 255, g / n / 255, b / n / 255)
    }

    fun release() {
        executor.execute {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }
        executor.shutdown()
    }

    private fun ensureGl() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }

        program = FilterShader.buildProgram(VS, FilterShader.fragment(external = false))
        for (n in UNIFORMS) u[n] = GLES20.glGetUniformLocation(program, n)
        val ids = IntArray(3)
        GLES20.glGenTextures(2, ids, 0)
        srcTex = ids[0]
        dstTex = ids[1]
        GLES20.glGenFramebuffers(1, ids, 2)
        fbo = ids[2]
        for (t in listOf(srcTex, dstTex)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
    }

    private fun draw(shot: Bitmap, w: Int, h: Int, params: FilterParams, seed: Float): Bitmap {
        ensureGl()
        if (w != fboW || h != fboH) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dstTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dstTex, 0)
            fboW = w
            fboH = h
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, shot, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniform1i(u.getValue("u_tex"), 0)
        // 웹 render()와 같은 cover-crop
        val srcAR = shot.width.toFloat() / shot.height
        val dstAR = w.toFloat() / h
        var sx = 1f
        var sy = 1f
        if (srcAR > dstAR) sx = dstAR / srcAR else sy = srcAR / dstAR
        GLES20.glUniform2f(u.getValue("u_scale"), sx, sy)
        GLES20.glUniform2f(u.getValue("u_offset"), (1 - sx) / 2, (1 - sy) / 2)
        FilterShader.setParams(u, params, seed)

        val loc = GLES20.glGetAttribLocation(program, "a_pos")
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
    }

    companion object {
        private val UNIFORMS = listOf("u_tex", "u_scale", "u_offset") + FilterShader.PARAM_UNIFORMS

        // glReadPixels는 아래 행부터 읽으므로 프레임버퍼를 위아래 뒤집어 그린다.
        // 그러면 읽은 버퍼가 그대로 위가 위인 비트맵이 된다.
        // v_screen(비네팅 기준)도 비트맵 기준 좌상단 원점으로 둔다.
        private const val VS = """
attribute vec2 a_pos;
uniform vec2 u_scale;
uniform vec2 u_offset;
varying vec2 v_uv;
varying vec2 v_screen;
void main() {
  vec2 uv = vec2((a_pos.x + 1.0) * 0.5, (a_pos.y + 1.0) * 0.5);
  v_screen = uv;
  v_uv = uv * u_scale + u_offset;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}"""
    }
}
