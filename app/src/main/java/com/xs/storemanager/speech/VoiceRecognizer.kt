package com.xs.storemanager.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 按住说话语音识别，基于 Android 系统 SpeechRecognizer（需系统语音服务，如输入法 / Google）。
 * 支持两种模式：
 *  - 未指定引擎：用系统默认语音识别服务（通常即客户当前输入法引擎）
 *  - 指定引擎：用 RecognitionServices 里客户选中的某个输入法引擎（API 33+ 生效；低版本回退默认）
 * 用法：按住时 start()，松开时 stop()，结果通过回调 onResult 返回。
 */
class VoiceRecognizer(private val context: Context) {

    interface Callback {
        fun onResult(text: String)
        fun onError(msg: String)
        fun onStartListening()
        fun onEndListening()
    }

    private var speech: SpeechRecognizer? = null
    private var callback: Callback? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callback?.onStartListening()
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            callback?.onEndListening()
        }

        override fun onError(error: Int) {
            callback?.onEndListening()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，请靠近麦克风说话"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，无法语音识别"
                SpeechRecognizer.ERROR_CLIENT -> "语音服务未就绪，请检查输入法语音设置"
                else -> "语音识别失败($error)"
            }
            callback?.onError(msg)
        }

        override fun onResults(results: Bundle?) {
            callback?.onEndListening()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (matches.isNotEmpty()) callback?.onResult(matches[0])
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 创建 SpeechRecognizer。
     * - 若客户在设置里指定了某输入法引擎，且系统支持指定组件(API 33+)，则用该引擎
     * - 否则用系统默认（当前输入法）
     */
    private fun createRecognizer(): SpeechRecognizer {
        val sel = RecognitionServices.getSelection(context)
        if (sel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val component = ComponentName(sel.first, sel.second)
            try {
                return SpeechRecognizer.createSpeechRecognizer(context, component)
            } catch (e: Exception) {
                // 指定失败则回退默认
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun start(cb: Callback) {
        callback = cb
        speech?.destroy()
        val recognizer = createRecognizer()
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech = recognizer
        recognizer.startListening(intent)
    }

    fun stop() {
        speech?.stopListening()
    }

    fun cancel() {
        speech?.cancel()
    }

    fun destroy() {
        speech?.destroy()
        speech = null
    }
}
