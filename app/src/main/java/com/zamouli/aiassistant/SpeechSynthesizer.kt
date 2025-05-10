package com.intelliai.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * مخلق الكلام
 * يستخدم واجهة TextToSpeech الخاصة بأندرويد (مجانية بالكامل)
 * يدعم اللغة العربية
 */
class SpeechSynthesizer(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechSynthesizer"
    }
    
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var currentLanguage = "ar"
    
    /**
     * تهيئة مخلق الكلام
     * 
     * @return true إذا تمت التهيئة بنجاح
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && textToSpeech != null) {
            return@withContext true
        }
        
        suspendCancellableCoroutine { continuation ->
            try {
                textToSpeech = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val result = setLanguage(currentLanguage)
                        isInitialized = result
                        
                        if (isInitialized) {
                            setupTts()
                        }
                        
                        if (continuation.isActive) {
                            continuation.resume(isInitialized)
                        }
                    } else {
                        Log.e(TAG, "فشل في تهيئة TextToSpeech: $status")
                        isInitialized = false
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
                
                continuation.invokeOnCancellation {
                    shutdown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تهيئة TextToSpeech: ${e.message}", e)
                isInitialized = false
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
    
    /**
     * إعداد خيارات TextToSpeech
     */
    private fun setupTts() {
        textToSpeech?.let { tts ->
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                tts.setAudioAttributes(audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                tts.setAudioStreamType(AudioManager.STREAM_MUSIC)
            }
            
            // تعيين مستمع تقدم النطق
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "بدأ نطق النص: $utteranceId")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "انتهى نطق النص: $utteranceId")
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "خطأ في نطق النص: $utteranceId")
                }
                
                override fun onError(utteranceId: String?, errorCode: Int) {
                    super.onError(utteranceId, errorCode)
                    Log.e(TAG, "خطأ في نطق النص: $utteranceId، رمز الخطأ: $errorCode")
                }
            })
        }
    }
    
    /**
     * تعيين لغة النطق
     * 
     * @param languageCode رمز اللغة (ar للعربية، en للإنجليزية)
     * @return true إذا تم تعيين اللغة بنجاح
     */
    fun setLanguage(languageCode: String): Boolean {
        if (!isInitialized || textToSpeech == null) {
            return false
        }
        
        val locale = when (languageCode) {
            "ar" -> Locale("ar")
            "ar-SA" -> Locale("ar", "SA")
            "ar-EG" -> Locale("ar", "EG")
            "ar-SD" -> Locale("ar", "SD") // دعم اللهجة السودانية
            "en" -> Locale.ENGLISH
            "en-US" -> Locale.US
            "en-GB" -> Locale.UK
            else -> Locale(languageCode)
        }
        
        val result = textToSpeech?.setLanguage(locale)
        return when (result) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                currentLanguage = languageCode
                true
            }
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.e(TAG, "بيانات اللغة مفقودة: $languageCode")
                false
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "اللغة غير مدعومة: $languageCode")
                false
            }
            else -> {
                Log.e(TAG, "خطأ غير معروف في تعيين اللغة: $result")
                false
            }
        }
    }
    
    /**
     * الحصول على الأصوات المتاحة
     * 
     * @return قائمة بأسماء الأصوات المتاحة
     */
    fun getAvailableVoices(): List<String> {
        if (!isInitialized || textToSpeech == null) {
            return emptyList()
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.voices?.map { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    /**
     * تعيين صوت محدد (إذا كان متاحًا)
     * 
     * @param voiceName اسم الصوت
     * @return true إذا تم تعيين الصوت بنجاح
     */
    fun setVoice(voiceName: String): Boolean {
        if (!isInitialized || textToSpeech == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }
        
        val voices = textToSpeech?.voices
        val voice = voices?.find { it.name == voiceName }
        
        return if (voice != null) {
            textToSpeech?.voice = voice
            true
        } else {
            Log.e(TAG, "الصوت غير متاح: $voiceName")
            false
        }
    }
    
    /**
     * تعيين سرعة الكلام
     * 
     * @param rate سرعة الكلام (1.0f هي السرعة العادية)
     */
    fun setSpeechRate(rate: Float) {
        if (!isInitialized || textToSpeech == null) {
            return
        }
        
        textToSpeech?.setSpeechRate(rate)
    }
    
    /**
     * تعيين طبقة الصوت
     * 
     * @param pitch طبقة الصوت (1.0f هي الطبقة العادية)
     */
    fun setPitch(pitch: Float) {
        if (!isInitialized || textToSpeech == null) {
            return
        }
        
        textToSpeech?.setPitch(pitch)
    }
    
    /**
     * نطق نص بصوت
     * 
     * @param text النص المراد نطقه
     * @param utteranceId معرف فريد للنطق
     * @return true إذا بدأ النطق بنجاح
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        if (!isInitialized || textToSpeech == null) {
            return false
        }
        
        // التأكد من أن TextToSpeech جاهز
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
        
        val params = HashMap<String, String>().apply {
            put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        }
        
        return result == TextToSpeech.SUCCESS
    }
    
    /**
     * نطق نص بصوت بشكل متزامن
     * 
     * @param text النص المراد نطقه
     * @return true إذا تم النطق بنجاح
     */
    suspend fun speakSync(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized || textToSpeech == null) {
            return@withContext false
        }
        
        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // لا شيء للتنفيذ هنا
                }
                
                override fun onDone(utteranceId: String?) {
                    textToSpeech?.setOnUtteranceProgressListener(null)
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    textToSpeech?.setOnUtteranceProgressListener(null)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
                
                override fun onError(utteranceId: String?, errorCode: Int) {
                    super.onError(utteranceId, errorCode)
                    textToSpeech?.setOnUtteranceProgressListener(null)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
            
            textToSpeech?.setOnUtteranceProgressListener(listener)
            
            val params = HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
            }
            
            if (result != TextToSpeech.SUCCESS) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
            
            continuation.invokeOnCancellation {
                textToSpeech?.stop()
                textToSpeech?.setOnUtteranceProgressListener(null)
            }
        }
    }
    
    /**
     * التحقق مما إذا كان الكلام قيد التقدم
     * 
     * @return true إذا كان الكلام قيد التقدم
     */
    fun isSpeaking(): Boolean {
        return isInitialized && textToSpeech?.isSpeaking == true
    }
    
    /**
     * إيقاف الكلام الحالي
     */
    fun stop() {
        if (isInitialized && textToSpeech != null) {
            textToSpeech?.stop()
        }
    }
    
    /**
     * إغلاق مخلق الكلام وتحرير الموارد
     */
    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إغلاق TextToSpeech: ${e.message}", e)
        }
    }
}