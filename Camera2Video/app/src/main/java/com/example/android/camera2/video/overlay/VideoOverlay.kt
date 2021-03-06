/*
# Copyright (c) 2021 Qualcomm Innovation Center, Inc.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted (subject to the limitations in the
# disclaimer below) provided that the following conditions are met:
#
#    * Redistributions of source code must retain the above copyright
#      notice, this list of conditions and the following disclaimer.
#
#    * Redistributions in binary form must reproduce the above
#      copyright notice, this list of conditions and the following
#      disclaimer in the documentation and/or other materials provided
#      with the distribution.
#
#    * Neither the name Qualcomm Innovation Center nor the names of its
#      contributors may be used to endorse or promote products derived
#      from this software without specific prior written permission.
#
# NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
# GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
# HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
# WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
# MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
# IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
# ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
# GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
# INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
# IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
# OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
# IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.example.android.camera2.video.overlay

import android.graphics.*
import android.opengl.*
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.math.roundToLong

enum class OverlayType {
    None, Image, Text, DateTime, Rectangle
}

class EglCore {
    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    constructor() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw Exception("unable to get EGL14 display");
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw Exception("unable to initialize EGL14")
        }
        val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE, 0,
                EGL14.EGL_NONE
        )
        val configs: Array<EGLConfig?> = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size,
                        numConfigs, 0)) {
            throw Exception("unable to find ES2 EGL config");
        }
        eglConfig = configs[0]

        val attribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                attribs, 0)

        var error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw Exception("eglCreateContext: EGL error: $error")
        }
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "makeCurrent without display")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw Exception("eglMakeCurrent failed")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface?, timestamp: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is SurfaceTexture) {
            throw Exception("invalid surface: $surface")
        }

        val surfaceAttribs = intArrayOf(
                EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface,
                surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == null) {
            throw Exception("surface was null")
        }
        return eglSurface
    }

    private fun checkEglError(msg: String) {
        var error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw Exception(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }

    companion object {
        private val TAG = this::class.simpleName
    }
}

class OverlayRenderer {
    private var overlayVertices: FloatBuffer
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var program = 0
    private var textureID = -12345
    private var overlayTextureId = -12345
    private var mvpMatrixHandle = 0
    private var stMatrixHandle = 0
    private var positionHandle = 0
    private var textureHandle = 0
    private var overlayTextureHandle = 0
    private var overlayUpdate = false
    private var rotationAngle = 0.0f
    private lateinit var overlayImage: Bitmap
    private var typeOverlay: OverlayType = OverlayType.None


    constructor() {
        overlayVertices = ByteBuffer.allocateDirect(
                verticesData.size * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        overlayVertices.put(verticesData).position(0)
        Matrix.setIdentityM(stMatrix, 0)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            throw Exception("failed creating program")
        }
        positionHandle = GLES20.glGetAttribLocation(program, "position")
        checkEglError("glGetAttribLocation aPosition")
        if (positionHandle == -1) {
            throw Exception("Could not get attrib location for position")
        }
        textureHandle = GLES20.glGetAttribLocation(program, "textureCoord")
        checkEglError("glGetAttribLocation aTextureCoord")
        if (textureHandle == -1) {
            throw Exception("Could not get attrib location for textureCoord")
        }
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "mvpMatrix")
        checkEglError("glGetUniformLocation mvpMatrix")
        if (mvpMatrixHandle == -1) {
            throw Exception("Could not get attrib location for mvpMatrix")
        }
        stMatrixHandle = GLES20.glGetUniformLocation(program, "stMatrix")
        checkEglError("glGetUniformLocation stMatrix")
        if (stMatrixHandle == -1) {
            throw Exception("Could not get attrib location for stMatrix")
        }

        overlayTextureHandle = GLES20.glGetUniformLocation(program, "overlayTexture")
        if (overlayTextureHandle == -1) {
            throw Exception("Could not get attrib location for overlayTexture")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureID = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        checkEglError("glBindTexture textureID")
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)
        checkEglError("glTexParameter")

        val overlayTextures = IntArray(1)
        GLES20.glGenTextures(1, overlayTextures, 0)
        overlayTextureId = overlayTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        checkEglError("glBindTexture overlayTextureId")
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)
        checkEglError("glGetUniformLocation overlayTexture")

    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkEglError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType:")
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkEglError("glCreateProgram")
        if (program == 0) {
            Log.e(TAG, "Could not create program")
        }
        GLES20.glAttachShader(program, vertexShader)
        checkEglError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkEglError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    fun getTexID() : Int {
        return textureID
    }

    fun drawFrame(st: SurfaceTexture) {
        checkEglError("DrawFrame")
        st.getTransformMatrix(stMatrix)
        checkEglError("getTransformMatrix")

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 0.0f)
        checkEglError("glClearColor")

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        checkEglError("glClear")

        GLES20.glUseProgram(program)
        checkEglError("glUseProgram")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(overlayTextureHandle, 1)

        overlayVertices.position(VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false,
                VERTICES_DATA_STRIDE_BYTES, overlayVertices)
        checkEglError("glVertexAttribPointer position")
        GLES20.glEnableVertexAttribArray(positionHandle)
        checkEglError("glEnableVertexAttribArray positionHandle")
        overlayVertices.position(VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false,
                VERTICES_DATA_STRIDE_BYTES, overlayVertices)
        checkEglError("glVertexAttribPointer textureHandle")
        GLES20.glEnableVertexAttribArray(textureHandle)
        checkEglError("glEnableVertexAttribArray textureHandle")
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.rotateM(mvpMatrix, 0, rotationAngle, 0.0f, 0.0f, 1.0f)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        if (::overlayImage.isInitialized && overlayUpdate) {
            if (typeOverlay != OverlayType.DateTime) {
                overlayUpdate = false
            } else {
                // Update Data and Time
                overlayImage.eraseColor(Color.BLACK)
                val canvas = Canvas(overlayImage)
                val paint = Paint()
                paint.textAlign = Paint.Align.LEFT
                paint.isAntiAlias = true
                paint.textSize = 100.0f
                paint.color = Color.WHITE
                paint.alpha = (0.5f * 255.0f).toInt()
                val sdf = SimpleDateFormat("dd/M/yyyy hh:mm:ss")
                val currentDate = sdf.format(Date())
                canvas.drawText(currentDate, 0.0f, 100.0f, paint)
                canvas.save()
                canvas.restore()
            }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayImage, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkEglError("glDrawArrays")
        GLES20.glFinish()
    }

    fun setRotation(rotation: Float) {
        rotationAngle = rotation
    }

    fun setImageOverlay(bitmap: Bitmap, type: OverlayType) {
        overlayImage = bitmap
        typeOverlay = type
        overlayUpdate = true
    }

    private fun checkEglError(msg: String) {
        var error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw Exception(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }

    companion object {
        private val TAG = this::class.simpleName
        private const val FLOAT_SIZE_BYTES = 4
        private const val VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val VERTICES_DATA_POS_OFFSET = 0
        private const val VERTICES_DATA_UV_OFFSET = 3

        private val verticesData = floatArrayOf(
                -1.0f, -1.0f, 0f, 0f, 0f,
                1.0f, -1.0f, 0f, 1f, 0f,
                -1.0f, 1.0f, 0f, 0f, 1f,
                1.0f, 1.0f, 0f, 1f, 1f)

        private const val VERTEX_SHADER =
                """uniform mat4 mvpMatrix;
               uniform mat4 stMatrix;
               attribute vec4 position;
               attribute vec4 textureCoord;
               varying vec2 vTexCoord;
               void main() {
                   gl_Position = mvpMatrix * position;
                   vTexCoord = (stMatrix * textureCoord).xy;
               }"""

        private const val FRAGMENT_SHADER =
                """#extension GL_OES_EGL_image_external : require
               precision mediump float;
               varying vec2 vTexCoord;
               uniform samplerExternalOES surfaceTexture;
               uniform sampler2D overlayTexture;
               void main() {
               gl_FragColor = texture2D(surfaceTexture, vTexCoord) + texture2D(overlayTexture, vTexCoord);
            }"""
    }
}

class VideoOverlay {
    private lateinit var eglCore: EglCore
    private lateinit var outputSurface: OutputOverlaySurface
    private lateinit var inputSurface: InputOverlaySurface
    private val overlaySyncObject = Object()
    private val overlaySemaphore = Semaphore(1)
    private var overlayRunning: Boolean
    private lateinit var overlayRenderer : OverlayRenderer
    private val widthImage: Int
    private val heightImage: Int
    private val overlayThread: Thread
    private val outputFrameInterval: Long
    private var outputTimestamp = 0L
    private val overlayBitmap: Bitmap

    constructor(surface: Surface, width: Int, height: Int, fps: Float, rotation: Float) {
        overlayRunning = true
        widthImage = width
        heightImage = height
        outputFrameInterval = (1000000000.0f / fps).toLong()
        overlayBitmap = Bitmap.createBitmap(widthImage, heightImage, Bitmap.Config.ARGB_8888)
        overlayThread = thread {
            handlerThread(surface, width, height, rotation)
        }
        synchronized(overlaySyncObject) {
            overlaySyncObject.wait()
        }
    }

    private fun FrameSkip(): Boolean {
        val inputTimestamp = inputSurface.getTimestamp()
        if (outputTimestamp == 0L) {
            outputTimestamp = inputTimestamp
        }

        val timestampDelta = outputTimestamp - inputTimestamp
        val maxDelta = (outputFrameInterval * 0.5f).roundToLong()

        if ((timestampDelta > 0) && (timestampDelta >= maxDelta)) {
            return true
        }
        return false
    }

    private fun handlerThread(surface: Surface, width: Int, height: Int, rotation: Float) {
        eglCore = EglCore()
        outputSurface = OutputOverlaySurface(eglCore, surface)
        outputSurface.makeCurrent()
        overlayRenderer = OverlayRenderer()
        overlayRenderer.setRotation(rotation)
        inputSurface = InputOverlaySurface(overlayRenderer.getTexID(), width, height)

        synchronized(overlaySyncObject) {
            overlaySyncObject.notifyAll();
        }

        while (overlayRunning) {
            if (!inputSurface.awaitFrame()) {
                break
            }
            if (FrameSkip()) {
                continue
            }
            overlayRenderer.drawFrame(inputSurface.getSurfaceTexture())
            outputSurface.setPresentationTime(outputTimestamp)
            outputSurface.swapBuffers()
            outputTimestamp += outputFrameInterval
        }
    }

    fun getInputSurface(): Surface {
        return inputSurface.getSurface()
    }

    fun release() {
        overlayRunning = false
        inputSurface.release()
        overlayThread.join()
        outputSurface.release()
    }

    fun setImageOverlay(bitmap: Bitmap, left: Float, top: Float) {
        val canvas = Canvas(overlayBitmap)
        canvas.drawBitmap(bitmap, left, top, null)
        overlayRenderer.setImageOverlay(overlayBitmap, OverlayType.Image)
    }

    fun setTextOverlay(msg: String, x: Float, y: Float, textSize: Float, color: Int, alpha: Float) {
        val canvas = Canvas(overlayBitmap)
        val paint = Paint()
        paint.textAlign = Paint.Align.LEFT
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.color = color
        paint.alpha = (alpha * 255.0f).toInt()
        canvas.drawText(msg, x, y, paint)
        canvas.save()
        canvas.restore()
        overlayRenderer.setImageOverlay(overlayBitmap, OverlayType.Text)
    }

    fun setDateAndTimeOverlay(x: Float, y: Float, size: Float, color: Int, alpha: Float) {
        val canvas = Canvas(overlayBitmap)
        val paint = Paint()
        paint.textAlign = Paint.Align.LEFT
        paint.isAntiAlias = true
        paint.textSize = size
        paint.color = color
        paint.alpha = (alpha * 255.0f).toInt()
        val sdf = SimpleDateFormat("dd/M/yyyy hh:mm:ss")
        val currentDate = sdf.format(Date())
        canvas.drawText(currentDate, x, y, paint)
        canvas.save()
        canvas.restore()
        overlayRenderer.setImageOverlay(overlayBitmap, OverlayType.DateTime)
    }

    fun setRectOverlay(width: Int, height: Int, color: Int, alpha: Float) {
        val rect = Rect(0, 0, width, height)
        val canvas = Canvas(overlayBitmap)
        val paint = Paint()
        paint.color = color
        paint.alpha = (alpha * 255.0f).toInt()
        canvas.drawRect(rect, paint)
        canvas.save()
        canvas.restore()
        overlayRenderer.setImageOverlay(overlayBitmap, OverlayType.Rectangle)
    }

    companion object {
        private val TAG = this::class.simpleName
    }
}
