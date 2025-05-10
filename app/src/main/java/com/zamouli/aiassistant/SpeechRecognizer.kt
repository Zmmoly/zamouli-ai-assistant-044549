package com.intelliai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * معرّف التعرف على الكلام
 * تستخدم واجهة SpeechRecognizer الخاصة بأندرويد (مجانية بالكامل)
 * تدعم اللغة العربية ومجموعة واسعة من اللهجات
 */
class SpeechRecognizerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechRecognizerManager"
    }
    
    // التحقق من دعم التعرف على الكلام
    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
    
    /**
     * بدء الاستماع للكلام وإعادة النص المتعرف عليه
     * 
     * @param languageModel لغة الكلام المتوقعة (ar_SA لدعم اللغة العربية)
     * @return تدفق من نتائج التعرف على الكلام
     */
    fun startListening(languageModel: String = "ar_SA"): Flow<SpeechRecognitionResult> = callbackFlow {
        if (!isSpeechRecognitionAvailable()) {
            close(Exception("التعرف على الكلام غير متاح على هذا الجهاز"))
            return@callbackFlow
        }
        
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        
        val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechRecognitionResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechRecognitionResult.Started)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechRecognitionResult.AudioLevel(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // لم يتم تنفيذ معالجة خاصة
            }

            override fun onEndOfSpeech() {
                trySend(SpeechRecognitionResult.Completed)
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                trySend(SpeechRecognitionResult.Error(errorMessage))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechRecognitionResult.Success(matches))
                } else {
                    trySend(SpeechRecognitionResult.Error("لم يتم التعرف على أي كلام"))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechRecognitionResult.PartialSuccess(matches))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // لم يتم تنفيذ معالجة خاصة
            }
        }

        speechRecognizer.setRecognitionListener(recognitionListener)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageModel)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
        }
        
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في بدء الاستماع: ${e.message}", e)
            close(e)
        }
        
        awaitClose {
            try {
                speechRecognizer.stopListening()
                speechRecognizer.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إيقاف الاستماع: ${e.message}", e)
            }
        }
    }
    
    /**
     * تنفيذ التعرف على الكلام بشكل متزامن وإعادة النتيجة كقائمة نصية
     */
    suspend fun recognizeSpeech(languageModel: String = "ar_SA"): List<String> = suspendCancellableCoroutine { continuation ->
        if (!isSpeechRecognitionAvailable()) {
            continuation.resumeWithException(Exception("التعرف على الكلام غير متاح على هذا الجهاز"))
            return@suspendCancellableCoroutine
        }
        
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        
        val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            
            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                speechRecognizer.destroy()
                if (continuation.isActive) {
                    continuation.resumeWithException(Exception(errorMessage))
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                speechRecognizer.destroy()
                if (continuation.isActive) {
                    if (!matches.isNullOrEmpty()) {
                        continuation.resume(matches)
                    } else {
                        continuation.resumeWithException(Exception("لم يتم التعرف على أي كلام"))
                    }
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        
        speechRecognizer.setRecognitionListener(recognitionListener)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageModel)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        
        try {
            speechRecognizer.startListening(intent)
            
            continuation.invokeOnCancellation {
                try {
                    speechRecognizer.stopListening()
                    speechRecognizer.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "خطأ في إلغاء الاستماع: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            speechRecognizer.destroy()
            Log.e(TAG, "خطأ في بدء الاستماع: ${e.message}", e)
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * تحويل رمز الخطأ إلى رسالة نصية
     */
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "خطأ في المدخلات الصوتية"
            SpeechRecognizer.ERROR_CLIENT -> "خطأ في جانب العميل"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "تصاريح غير كافية"
            SpeechRecognizer.ERROR_NETWORK -> "خطأ في الشبكة"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "انتهت مهلة الشبكة"
            SpeechRecognizer.ERROR_NO_MATCH -> "لم يتم التعرف على الكلام"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "معرف الكلام مشغول"
            SpeechRecognizer.ERROR_SERVER -> "خطأ في الخادم"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "انتهت مهلة الكلام"
            else -> "خطأ غير معروف: $errorCode"
        }
    }
}

/**
 * نتائج التعرف على الكلام كتدفق مستمر من الحالات
 */
sealed class SpeechRecognitionResult {
    object Ready : SpeechRecognitionResult()
    object Started : SpeechRecognitionResult()
    object Completed : SpeechRecognitionResult()
    data class AudioLevel(val level: Float) : SpeechRecognitionResult()
    data class Error(val message: String) : SpeechRecognitionResult()
    data class Success(val matches: List<String>) : SpeechRecognitionResult()
    data class PartialSuccess(val partialMatches: List<String>) : SpeechRecognitionResult()
}