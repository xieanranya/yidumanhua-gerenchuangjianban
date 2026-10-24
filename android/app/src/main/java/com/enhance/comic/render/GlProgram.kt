package com.enhance.comic.render

import android.opengl.GLES31
import android.util.Log

/** Thin wrapper over a linked GL program with uniform location caching. */
class GlProgram private constructor(val id: Int) {

    private val uniforms = HashMap<String, Int>()

    fun use() {
        GLES31.glUseProgram(id)
    }

    fun u(name: String): Int = uniforms.getOrPut(name) {
        GLES31.glGetUniformLocation(id, name)
    }

    companion object {
        private const val TAG = "GlProgram"

        fun link(vertexSrc: String, fragmentSrc: String): GlProgram? {
            val vs = compile(GLES31.GL_VERTEX_SHADER, vertexSrc) ?: return null
            val fs = compile(GLES31.GL_FRAGMENT_SHADER, fragmentSrc) ?: return null
            return linkAll(intArrayOf(vs, fs))
        }

        fun compute(src: String): GlProgram? {
            val cs = compile(GLES31.GL_COMPUTE_SHADER, src) ?: return null
            return linkAll(intArrayOf(cs))
        }

        private fun linkAll(shaders: IntArray): GlProgram? {
            val program = GLES31.glCreateProgram()
            if (program == 0) return null
            shaders.forEach { GLES31.glAttachShader(program, it) }
            GLES31.glLinkProgram(program)
            val status = IntArray(1)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0)
            shaders.forEach { GLES31.glDeleteShader(it) }
            if (status[0] == 0) {
                Log.e(TAG, "link failed: ${GLES31.glGetProgramInfoLog(program)}")
                GLES31.glDeleteProgram(program)
                return null
            }
            return GlProgram(program)
        }

        private fun compile(type: Int, src: String): Int? {
            val shader = GLES31.glCreateShader(type)
            if (shader == 0) return null
            GLES31.glShaderSource(shader, src)
            GLES31.glCompileShader(shader)
            val status = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "compile failed: ${GLES31.glGetShaderInfoLog(shader)}")
                GLES31.glDeleteShader(shader)
                return null
            }
            return shader
        }
    }
}
