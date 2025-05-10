package com.intelliai.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * محلل نبرة الصوت
 * يستخدم نموذج TensorFlow Lite محلي (مجاني بالكامل)
 * يتعرف على 7 حالات عاطفية: محايد، سعيد، حزين، غاضب، خائف، مقرف، متفاجئ
 */
class VoiceToneAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceToneAnalyzer"
        private const val MODEL_FILE = "voice_emotion_model.tflite"
        private const val INPUT_BUFFER_SIZE = 16000 * 2 // 1 ثانية من الصوت بتردد 16 كيلوهرتز، 16 بت
        private const val EMOTIONS = 7
    }
    
    private var interpreter: Interpreter? = null
    private val emotions = listOf("neutral", "happy", "sad", "angry", "fear", "disgust", "surprise")
    
    /**
     * تهيئة المحلل وتحميل النموذج
     * 
     * @return true إذا تمت التهيئة بنجاح
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (interpreter != null) {
                return@withContext true
            }
            
            // تحميل النموذج من ملفات الأصول
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تهيئة محلل نبرة الصوت: ${e.message}", e)
            false
        }
    }
    
    /**
     * تحليل عينة صوتية لاكتشاف النبرة العاطفية
     * 
     * @param audioSample عينة الصوت كمصفوفة بايت
     * @return نتيجة تحليل نبرة الصوت
     */
    suspend fun analyzeAudio(audioSample: ByteArray): EmotionAnalysisResult = withContext(Dispatchers.Default) {
        if (interpreter == null) {
            val initialized = initialize()
            if (!initialized) {
                return@withContext EmotionAnalysisResult(
                    dominantEmotion = "unknown",
                    emotionScores = emotions.associateWith { 0.0f },
                    confidence = 0.0f,
                    intensity = 0.0f
                )
            }
        }
        
        try {
            // تحضير بيانات الإدخال
            val inputBuffer = prepareAudioSample(audioSample)
            
            // تهيئة بيانات الإخراج
            val outputBuffer = Array(1) { FloatArray(EMOTIONS) }
            
            // تنفيذ النموذج
            interpreter?.run(inputBuffer, outputBuffer)
            
            // معالجة النتائج
            val emotionScores = outputBuffer[0]
            
            // البحث عن العاطفة المهيمنة
            var maxIndex = 0
            var maxScore = emotionScores[0]
            
            for (i in 1 until EMOTIONS) {
                if (emotionScores[i] > maxScore) {
                    maxScore = emotionScores[i]
                    maxIndex = i
                }
            }
            
            // حساب شدة العاطفة (الفرق بين أعلى قيمتين)
            val sortedScores = emotionScores.clone().apply { sort() }
            val intensity = if (sortedScores.size >= 2) {
                sortedScores[EMOTIONS - 1] - sortedScores[EMOTIONS - 2]
            } else {
                0.0f
            }
            
            // إنشاء خريطة من العواطف إلى درجاتها
            val emotionMap = emotions.mapIndexed { index, emotion ->
                emotion to emotionScores[index]
            }.toMap()
            
            EmotionAnalysisResult(
                dominantEmotion = emotions.getOrElse(maxIndex) { "unknown" },
                emotionScores = emotionMap,
                confidence = maxScore,
                intensity = intensity
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل الصوت: ${e.message}", e)
            
            // إعادة نتيجة افتراضية في حالة الخطأ
            EmotionAnalysisResult(
                dominantEmotion = "error",
                emotionScores = emotions.associateWith { 0.0f },
                confidence = 0.0f,
                intensity = 0.0f
            )
        }
    }
    
    /**
     * تحضير عينة الصوت للتحليل
     * 
     * @param audioData بيانات الصوت الخام
     * @return مخزن مؤقت جاهز للإدخال في النموذج
     */
    private fun prepareAudioSample(audioData: ByteArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE)
        buffer.order(ByteOrder.nativeOrder())
        
        // التأكد من أن البيانات لا تتجاوز الحجم المتوقع
        val dataSize = minOf(audioData.size, INPUT_BUFFER_SIZE)
        
        // نسخ البيانات إلى المخزن المؤقت
        buffer.put(audioData, 0, dataSize)
        
        // إعادة ضبط موضع القراءة
        buffer.rewind()
        
        return buffer
    }
    
    /**
     * إغلاق المفسر وتحرير الموارد
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إغلاق محلل نبرة الصوت: ${e.message}", e)
        }
    }
}

/**
 * نتيجة تحليل نبرة الصوت العاطفية
 */
data class EmotionAnalysisResult(
    val dominantEmotion: String,  // العاطفة المهيمنة
    val emotionScores: Map<String, Float>,  // قائمة بجميع العواطف ودرجاتها
    val confidence: Float,  // مستوى الثقة في التحليل
    val intensity: Float  // شدة العاطفة
)