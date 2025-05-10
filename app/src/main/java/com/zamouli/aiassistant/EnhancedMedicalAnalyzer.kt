package com.intelliai.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

/**
 * محلل طبي محسن
 * يستخدم نموذج TensorFlow Lite محلي (مجاني بالكامل)
 * قادر على تحليل نتائج الفحوصات الطبية وتقديم توصيات
 */
class EnhancedMedicalAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "EnhancedMedicalAnalyzer"
        private const val MODEL_FILE = "medical_analyzer_model.tflite"
        private const val MAX_INPUT_SIZE = 64  // عدد العناصر الطبية المدخلة
        private const val NUM_OUTPUTS = 32  // عدد التصنيفات والتوصيات الناتجة
    }
    
    private var interpreter: Interpreter? = null
    private val medicalDatabase = MedicalDatabase()
    
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
            val options = Interpreter.Options().apply { 
                setNumThreads(4)  // ضبط عدد مسارات التنفيذ لتحسين الأداء
            }
            interpreter = Interpreter(modelBuffer, options)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تهيئة المحلل الطبي: ${e.message}", e)
            false
        }
    }
    
    /**
     * تحليل نتائج الفحوصات الطبية
     * 
     * @param medicalTestResults نتائج الفحوصات الطبية كنص أو كقائمة من أزواج المفتاح/القيمة
     * @return نتيجة التحليل الطبي
     */
    suspend fun analyzeMedicalResults(medicalTestResults: String): MedicalAnalysisResult = withContext(Dispatchers.Default) {
        // تحليل نص نتائج الفحوصات إلى أزواج من اسم الفحص وقيمته
        val testResults = parseTestResults(medicalTestResults)
        return@withContext analyzeMedicalResultPairs(testResults)
    }
    
    /**
     * تحليل نتائج الفحوصات الطبية كأزواج
     * 
     * @param testPairs قائمة من أزواج اسم الفحص وقيمته
     * @return نتيجة التحليل الطبي
     */
    suspend fun analyzeMedicalResultPairs(testPairs: List<Pair<String, Float>>): MedicalAnalysisResult = withContext(Dispatchers.Default) {
        // تهيئة المحلل إذا لم يكن مهيأ بالفعل
        if (interpreter == null) {
            val initialized = initialize()
            if (!initialized) {
                return@withContext MedicalAnalysisResult(
                    observations = listOf("خطأ في تهيئة المحلل الطبي"),
                    abnormalValues = emptyMap(),
                    recommendations = emptyList(),
                    riskAssessment = emptyMap(),
                    summary = "غير قادر على تحليل النتائج بسبب خطأ في التهيئة"
                )
            }
        }
        
        try {
            // تحضير البيانات الطبية للإدخال في النموذج
            val inputBuffer = prepareMedicalData(testPairs)
            
            // تهيئة مصفوفة الإخراج
            val outputBuffer = Array(1) { FloatArray(NUM_OUTPUTS) }
            
            // تنفيذ النموذج
            interpreter?.run(inputBuffer, outputBuffer)
            
            // معالجة نتائج التحليل
            val results = outputBuffer[0]
            
            // تحديد القيم غير الطبيعية
            val abnormalValues = mutableMapOf<String, AbnormalValue>()
            for ((testName, value) in testPairs) {
                val referenceRange = medicalDatabase.getReferenceRange(testName)
                if (referenceRange != null) {
                    val (min, max) = referenceRange
                    val status = when {
                        value < min -> MedicalValueStatus.LOW
                        value > max -> MedicalValueStatus.HIGH
                        else -> MedicalValueStatus.NORMAL
                    }
                    
                    if (status != MedicalValueStatus.NORMAL) {
                        abnormalValues[testName] = AbnormalValue(
                            value = value,
                            status = status,
                            referenceRange = "$min - $max",
                            deviation = if (status == MedicalValueStatus.LOW) ((value - min) / min) * 100 else ((value - max) / max) * 100
                        )
                    }
                }
            }
            
            // استخلاص الملاحظات والتوصيات استنادًا إلى نتائج النموذج
            val observations = mutableListOf<String>()
            val recommendations = mutableListOf<String>()
            val riskAssessment = mutableMapOf<String, Float>()
            
            // إضافة الملاحظات بناءً على القيم غير الطبيعية
            for ((testName, abnormal) in abnormalValues) {
                val description = medicalDatabase.getTestDescription(testName) ?: testName
                val statusText = when (abnormal.status) {
                    MedicalValueStatus.LOW -> "منخفضة"
                    MedicalValueStatus.HIGH -> "مرتفعة"
                    else -> "غير طبيعية"
                }
                
                observations.add("قيمة $description $statusText (${abnormal.value})، المدى المرجعي: ${abnormal.referenceRange}")
                
                // إضافة توصيات محددة بناءً على الفحص
                val specificRecommendations = medicalDatabase.getRecommendations(testName, abnormal.status)
                if (specificRecommendations.isNotEmpty()) {
                    recommendations.addAll(specificRecommendations)
                }
            }
            
            // إضافة تقييمات المخاطر
            if (results.size >= 4) {  // نفترض أن أول 4 قيم في النتائج تمثل تقييمات المخاطر
                riskAssessment["القلب والأوعية الدموية"] = results[0]
                riskAssessment["السكري"] = results[1]
                riskAssessment["الكبد"] = results[2]
                riskAssessment["الكلى"] = results[3]
            }
            
            // إضافة توصيات عامة
            if (observations.isNotEmpty()) {
                recommendations.add("يرجى مراجعة الطبيب لمناقشة نتائج الفحوصات وتقييم الحالة الصحية بشكل شامل")
                
                if (riskAssessment["القلب والأوعية الدموية"] ?: 0f > 0.5f) {
                    recommendations.add("ينصح باتباع نظام غذائي منخفض الدهون المشبعة والصوديوم")
                    recommendations.add("ممارسة الرياضة المعتدلة لمدة 30 دقيقة يوميًا، 5 أيام في الأسبوع")
                }
                
                if (riskAssessment["السكري"] ?: 0f > 0.5f) {
                    recommendations.add("ينصح بمراقبة مستويات السكر في الدم بانتظام")
                    recommendations.add("التقليل من استهلاك الكربوهيدرات البسيطة والسكريات المضافة")
                }
            } else {
                recommendations.add("النتائج ضمن المدى الطبيعي، استمر في اتباع نمط حياة صحي")
            }
            
            // إنشاء ملخص
            val summary = generateSummary(observations, recommendations, riskAssessment)
            
            MedicalAnalysisResult(
                observations = observations,
                abnormalValues = abnormalValues,
                recommendations = recommendations.distinct(),
                riskAssessment = riskAssessment,
                summary = summary
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل النتائج الطبية: ${e.message}", e)
            
            // إعادة نتيجة بسيطة في حالة الخطأ
            MedicalAnalysisResult(
                observations = listOf("حدث خطأ أثناء تحليل النتائج الطبية"),
                abnormalValues = emptyMap(),
                recommendations = listOf("يرجى مراجعة الطبيب لتفسير نتائج الفحوصات"),
                riskAssessment = emptyMap(),
                summary = "غير قادر على تحليل النتائج بشكل كامل بسبب خطأ تقني"
            )
        }
    }
    
    /**
     * تحليل نص نتائج الفحوصات إلى أزواج من اسم الفحص وقيمته
     * 
     * @param resultsText نص نتائج الفحوصات
     * @return قائمة من أزواج اسم الفحص وقيمته
     */
    private fun parseTestResults(resultsText: String): List<Pair<String, Float>> {
        val resultPairs = mutableListOf<Pair<String, Float>>()
        
        // نمط للتطابق مع أزواج الاسم والقيمة في النص
        val pattern = Pattern.compile(
            "(\\b[A-Za-zأ-ي\\s،,\\-+()٪%]+\\b)\\s*[:\\=]?\\s*([\\d\\.]+)\\s*([a-zA-Z/\\%]*)",
            Pattern.UNICODE_CHARACTER_CLASS
        )
        
        val matcher = pattern.matcher(resultsText)
        
        while (matcher.find()) {
            val testName = matcher.group(1)?.trim() ?: continue
            val valueStr = matcher.group(2)?.trim() ?: continue
            
            try {
                val value = valueStr.toFloat()
                resultPairs.add(Pair(testName, value))
            } catch (e: NumberFormatException) {
                Log.w(TAG, "تعذر تحويل القيمة إلى رقم: $valueStr")
            }
        }
        
        return resultPairs
    }
    
    /**
     * تحضير البيانات الطبية للإدخال في النموذج
     * 
     * @param testPairs قائمة من أزواج اسم الفحص وقيمته
     * @return مخزن مؤقت يحتوي على البيانات المرمزة
     */
    private fun prepareMedicalData(testPairs: List<Pair<String, Float>>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MAX_INPUT_SIZE * 2 * 4) // 2 قيم لكل فحص (اسم ورقم)، 4 بايت لكل قيمة
        buffer.order(ByteOrder.nativeOrder())
        
        // ترميز المؤشرات الطبية
        for (i in 0 until minOf(testPairs.size, MAX_INPUT_SIZE)) {
            val (testName, value) = testPairs[i]
            
            // ترميز اسم الفحص كرقم (يمكن استخدام خريطة مسبقة الإعداد)
            val testCode = medicalDatabase.getTestCode(testName) ?: 0f
            buffer.putFloat(testCode)
            
            // إضافة قيمة الفحص
            buffer.putFloat(value)
        }
        
        // ملء باقي المخزن المؤقت بأصفار إذا كان عدد الفحوصات أقل من الحد الأقصى
        for (i in testPairs.size until MAX_INPUT_SIZE) {
            buffer.putFloat(0f) // رمز الفحص
            buffer.putFloat(0f) // قيمة الفحص
        }
        
        // إعادة ضبط موضع القراءة
        buffer.rewind()
        
        return buffer
    }
    
    /**
     * إنشاء ملخص للتحليل الطبي
     * 
     * @param observations الملاحظات
     * @param recommendations التوصيات
     * @param riskAssessment تقييم المخاطر
     * @return ملخص التحليل
     */
    private fun generateSummary(
        observations: List<String>,
        recommendations: List<String>,
        riskAssessment: Map<String, Float>
    ): String {
        val summary = StringBuilder()
        
        if (observations.isEmpty()) {
            summary.append("جميع النتائج ضمن المدى الطبيعي. ")
        } else {
            summary.append("تم اكتشاف ${observations.size} قيم غير طبيعية. ")
            
            // إضافة معلومات حول أعلى خطر
            if (riskAssessment.isNotEmpty()) {
                val highestRisk = riskAssessment.maxByOrNull { it.value }
                if (highestRisk != null && highestRisk.value > 0.3f) {
                    summary.append("يوجد احتمال متزايد لمخاطر ${highestRisk.key}. ")
                }
            }
        }
        
        if (recommendations.isNotEmpty()) {
            summary.append("التوصيات الرئيسية: ${recommendations.first()}")
        }
        
        return summary.toString()
    }
    
    /**
     * إغلاق المفسر وتحرير الموارد
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إغلاق المحلل الطبي: ${e.message}", e)
        }
    }
    
    /**
     * قاعدة بيانات طبية مبسطة تحتوي على معلومات عن الفحوصات الطبية
     */
    inner class MedicalDatabase {
        // خريطة تربط بين اسم الفحص ورمزه الرقمي
        private val testCodes = mapOf(
            "هيموجلوبين" to 1f,
            "كرات دم حمراء" to 2f,
            "كرات دم بيضاء" to 3f,
            "صفائح دموية" to 4f,
            "سكر صائم" to 5f,
            "كوليسترول" to 6f,
            "تراي جليسيريد" to 7f,
            "HDL" to 8f,
            "LDL" to 9f,
            "كرياتينين" to 10f,
            "يوريا" to 11f,
            "حمض يوريك" to 12f,
            "ALT" to 13f,
            "AST" to 14f,
            "ALP" to 15f,
            "بيليروبين" to 16f,
            "كالسيوم" to 17f,
            "صوديوم" to 18f,
            "بوتاسيوم" to 19f,
            "مغنيسيوم" to 20f,
            "فيتامين د" to 21f,
            "فيتامين ب12" to 22f,
            "الحديد" to 23f,
            "فيريتين" to 24f,
            "TSH" to 25f,
            "T3" to 26f,
            "T4" to 27f,
            "HbA1c" to 28f,
            "ضغط دم انقباضي" to 29f,
            "ضغط دم انبساطي" to 30f,
            "نبض" to 31f
        )
        
        // خريطة تربط بين اسم الفحص والمدى المرجعي له
        private val referenceRanges = mapOf(
            "هيموجلوبين" to Pair(12.0f, 18.0f),
            "كرات دم حمراء" to Pair(4.5f, 6.5f),
            "كرات دم بيضاء" to Pair(4.0f, 11.0f),
            "صفائح دموية" to Pair(150f, 450f),
            "سكر صائم" to Pair(70f, 100f),
            "كوليسترول" to Pair(130f, 200f),
            "تراي جليسيريد" to Pair(40f, 150f),
            "HDL" to Pair(40f, 60f),
            "LDL" to Pair(70f, 130f),
            "كرياتينين" to Pair(0.5f, 1.2f),
            "يوريا" to Pair(7f, 20f),
            "حمض يوريك" to Pair(3.5f, 7.2f),
            "ALT" to Pair(7f, 55f),
            "AST" to Pair(8f, 48f),
            "ALP" to Pair(40f, 129f),
            "بيليروبين" to Pair(0.1f, 1.2f),
            "كالسيوم" to Pair(8.5f, 10.5f),
            "صوديوم" to Pair(135f, 145f),
            "بوتاسيوم" to Pair(3.5f, 5.0f),
            "مغنيسيوم" to Pair(1.7f, 2.3f),
            "فيتامين د" to Pair(30f, 100f),
            "فيتامين ب12" to Pair(200f, 900f),
            "الحديد" to Pair(60f, 170f),
            "فيريتين" to Pair(30f, 400f),
            "TSH" to Pair(0.4f, 4.0f),
            "T3" to Pair(80f, 200f),
            "T4" to Pair(5.0f, 12.0f),
            "HbA1c" to Pair(4.0f, 5.6f),
            "ضغط دم انقباضي" to Pair(90f, 120f),
            "ضغط دم انبساطي" to Pair(60f, 80f),
            "نبض" to Pair(60f, 100f)
        )
        
        // خريطة تربط بين اسم الفحص ووصفه
        private val testDescriptions = mapOf(
            "هيموجلوبين" to "الهيموجلوبين (صبغة الدم الحمراء)",
            "كرات دم حمراء" to "كريات الدم الحمراء",
            "كرات دم بيضاء" to "كريات الدم البيضاء",
            "صفائح دموية" to "الصفائح الدموية",
            "سكر صائم" to "مستوى السكر في الدم للصائم",
            "كوليسترول" to "الكوليسترول الكلي",
            "تراي جليسيريد" to "الدهون الثلاثية",
            "HDL" to "البروتين الدهني عالي الكثافة (الكوليسترول الجيد)",
            "LDL" to "البروتين الدهني منخفض الكثافة (الكوليسترول الضار)",
            "كرياتينين" to "الكرياتينين (مؤشر وظائف الكلى)",
            "يوريا" to "اليوريا في الدم",
            "حمض يوريك" to "حمض اليوريك",
            "ALT" to "إنزيم الألانين أمينوترانسفيراز (مؤشر وظائف الكبد)",
            "AST" to "إنزيم الأسبارتيت أمينوترانسفيراز (مؤشر وظائف الكبد)",
            "ALP" to "الفوسفاتيز القلوي",
            "بيليروبين" to "البيليروبين (صبغة الصفراء)",
            "كالسيوم" to "الكالسيوم في الدم",
            "صوديوم" to "الصوديوم في الدم",
            "بوتاسيوم" to "البوتاسيوم في الدم",
            "مغنيسيوم" to "المغنيسيوم في الدم",
            "فيتامين د" to "فيتامين د (كالسيديول)",
            "فيتامين ب12" to "فيتامين ب12 (كوبالامين)",
            "الحديد" to "الحديد في الدم",
            "فيريتين" to "الفيريتين (مخزون الحديد)",
            "TSH" to "الهرمون المنبه للغدة الدرقية",
            "T3" to "هرمون الغدة الدرقية (ثلاثي يودوثيرونين)",
            "T4" to "هرمون الغدة الدرقية (ثيروكسين)",
            "HbA1c" to "الهيموجلوبين السكري (مؤشر لمتوسط السكر)",
            "ضغط دم انقباضي" to "ضغط الدم الانقباضي",
            "ضغط دم انبساطي" to "ضغط الدم الانبساطي",
            "نبض" to "معدل ضربات القلب"
        )
        
        /**
         * الحصول على رمز الفحص من اسمه
         * 
         * @param testName اسم الفحص
         * @return رمز الفحص أو null إذا لم يكن موجودًا
         */
        fun getTestCode(testName: String): Float? {
            // محاولة العثور على الفحص بالاسم المطابق
            testCodes[testName]?.let { return it }
            
            // البحث باستخدام مطابقة جزئية
            val matchingKey = testCodes.keys.find { 
                it.contains(testName, ignoreCase = true) || testName.contains(it, ignoreCase = true)
            }
            
            return testCodes[matchingKey]
        }
        
        /**
         * الحصول على المدى المرجعي للفحص
         * 
         * @param testName اسم الفحص
         * @return المدى المرجعي أو null إذا لم يكن موجودًا
         */
        fun getReferenceRange(testName: String): Pair<Float, Float>? {
            // محاولة العثور على الفحص بالاسم المطابق
            referenceRanges[testName]?.let { return it }
            
            // البحث باستخدام مطابقة جزئية
            val matchingKey = referenceRanges.keys.find { 
                it.contains(testName, ignoreCase = true) || testName.contains(it, ignoreCase = true)
            }
            
            return referenceRanges[matchingKey]
        }
        
        /**
         * الحصول على وصف الفحص
         * 
         * @param testName اسم الفحص
         * @return وصف الفحص أو null إذا لم يكن موجودًا
         */
        fun getTestDescription(testName: String): String? {
            // محاولة العثور على الفحص بالاسم المطابق
            testDescriptions[testName]?.let { return it }
            
            // البحث باستخدام مطابقة جزئية
            val matchingKey = testDescriptions.keys.find { 
                it.contains(testName, ignoreCase = true) || testName.contains(it, ignoreCase = true)
            }
            
            return testDescriptions[matchingKey] ?: testName
        }
        
        /**
         * الحصول على توصيات بناءً على نوع الفحص وحالته
         * 
         * @param testName اسم الفحص
         * @param status حالة القيمة (مرتفعة، منخفضة)
         * @return قائمة من التوصيات
         */
        fun getRecommendations(testName: String, status: MedicalValueStatus): List<String> {
            return when {
                testName.contains("هيموجلوبين", ignoreCase = true) && status == MedicalValueStatus.LOW -> 
                    listOf("ينصح بتناول الأطعمة الغنية بالحديد مثل اللحوم الحمراء والسبانخ والبقوليات", "قد يشير انخفاض الهيموجلوبين إلى فقر الدم، راجع الطبيب للتقييم")
                    
                testName.contains("فيتامين د", ignoreCase = true) && status == MedicalValueStatus.LOW -> 
                    listOf("ينصح بالتعرض لأشعة الشمس المباشرة لمدة 10-15 دقيقة يوميًا", "تناول الأطعمة الغنية بفيتامين د مثل الأسماك الدهنية والبيض")
                    
                testName.contains("سكر", ignoreCase = true) && status == MedicalValueStatus.HIGH -> 
                    listOf("ينصح بتقليل تناول السكريات والكربوهيدرات البسيطة", "ممارسة الرياضة بانتظام لتحسين مستويات السكر في الدم")
                    
                testName.contains("كوليسترول", ignoreCase = true) || testName.contains("LDL", ignoreCase = true) && status == MedicalValueStatus.HIGH -> 
                    listOf("ينصح بتقليل تناول الدهون المشبعة والزيوت المهدرجة", "زيادة تناول الألياف الغذائية والأوميغا 3")
                    
                testName.contains("ضغط", ignoreCase = true) && status == MedicalValueStatus.HIGH -> 
                    listOf("ينصح بتقليل تناول الملح والصوديوم", "ممارسة رياضة المشي يوميًا", "تجنب التوتر والإجهاد النفسي")
                    
                testName.contains("ALT", ignoreCase = true) || testName.contains("AST", ignoreCase = true) && status == MedicalValueStatus.HIGH -> 
                    listOf("ينصح بتجنب الكحول والأدوية التي تؤثر على الكبد", "تجنب الأطعمة الدهنية وتناول الخضروات والفواكه")
                    
                testName.contains("كرياتينين", ignoreCase = true) && status == MedicalValueStatus.HIGH -> 
                    listOf("ينصح بزيادة شرب الماء إلى 2-3 لتر يوميًا", "تقليل تناول البروتين الحيواني وتناول المزيد من الأطعمة النباتية")
                    
                else -> emptyList()
            }
        }
    }
}

/**
 * حالة القيمة الطبية
 */
enum class MedicalValueStatus {
    NORMAL,  // طبيعي
    HIGH,    // مرتفع
    LOW      // منخفض
}

/**
 * قيمة غير طبيعية في الفحص الطبي
 */
data class AbnormalValue(
    val value: Float,               // القيمة الفعلية
    val status: MedicalValueStatus, // الحالة (مرتفع، منخفض)
    val referenceRange: String,     // المدى المرجعي الطبيعي
    val deviation: Float            // نسبة الانحراف عن الحد الطبيعي بالنسبة المئوية
)

/**
 * نتيجة التحليل الطبي
 */
data class MedicalAnalysisResult(
    val observations: List<String>,                // ملاحظات حول النتائج
    val abnormalValues: Map<String, AbnormalValue>, // قيم غير طبيعية
    val recommendations: List<String>,              // توصيات بناءً على النتائج
    val riskAssessment: Map<String, Float>,         // تقييم المخاطر لمختلف الأمراض
    val summary: String                             // ملخص التحليل
)