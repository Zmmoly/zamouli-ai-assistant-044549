package com.example.aiassistant

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * معالج التحاليل الطبية - مسؤول عن استخراج البيانات من صور التحاليل الطبية وتحليلها
 */
class MedicalAnalysisProcessor(
    private val context: Context,
    private val healthTracker: HealthTracker
) {
    companion object {
        private const val TAG = "MedicalAnalysisProcessor"
        
        // قائمة بالتحاليل المخبرية الشائعة وبياناتها
        private val commonLabTests = mapOf(
            // تحاليل الدم العامة
            "hemoglobin" to TestInfo("هيموجلوبين", "g/dL", 13.0..17.0, "تحليل دم"),
            "هيموجلوبين" to TestInfo("هيموجلوبين", "g/dL", 13.0..17.0, "تحليل دم"),
            "hb" to TestInfo("هيموجلوبين", "g/dL", 13.0..17.0, "تحليل دم"),
            
            "wbc" to TestInfo("كريات الدم البيضاء", "10^3/μL", 4.5..11.0, "تحليل دم"),
            "white blood cells" to TestInfo("كريات الدم البيضاء", "10^3/μL", 4.5..11.0, "تحليل دم"),
            "كريات بيضاء" to TestInfo("كريات الدم البيضاء", "10^3/μL", 4.5..11.0, "تحليل دم"),
            
            "rbc" to TestInfo("كريات الدم الحمراء", "10^6/μL", 4.5..5.9, "تحليل دم"),
            "red blood cells" to TestInfo("كريات الدم الحمراء", "10^6/μL", 4.5..5.9, "تحليل دم"),
            "كريات حمراء" to TestInfo("كريات الدم الحمراء", "10^6/μL", 4.5..5.9, "تحليل دم"),
            
            "platelets" to TestInfo("الصفائح الدموية", "10^3/μL", 150.0..450.0, "تحليل دم"),
            "plt" to TestInfo("الصفائح الدموية", "10^3/μL", 150.0..450.0, "تحليل دم"),
            "صفائح دموية" to TestInfo("الصفائح الدموية", "10^3/μL", 150.0..450.0, "تحليل دم"),
            
            // تحاليل وظائف الكبد
            "alt" to TestInfo("ALT (إنزيم الكبد)", "U/L", 7.0..56.0, "وظائف الكبد"),
            "ast" to TestInfo("AST (إنزيم الكبد)", "U/L", 10.0..40.0, "وظائف الكبد"),
            "إنزيمات الكبد" to TestInfo("إنزيمات الكبد", "U/L", 7.0..56.0, "وظائف الكبد"),
            "alkaline phosphatase" to TestInfo("الفوسفاتيز القلوي", "U/L", 44.0..147.0, "وظائف الكبد"),
            
            // تحاليل وظائف الكلى
            "creatinine" to TestInfo("كرياتينين", "mg/dL", 0.6..1.2, "وظائف الكلى"),
            "كرياتينين" to TestInfo("كرياتينين", "mg/dL", 0.6..1.2, "وظائف الكلى"),
            "blood urea nitrogen" to TestInfo("نيتروجين اليوريا", "mg/dL", 7.0..20.0, "وظائف الكلى"),
            "urea" to TestInfo("يوريا", "mg/dL", 15.0..43.0, "وظائف الكلى"),
            "يوريا" to TestInfo("يوريا", "mg/dL", 15.0..43.0, "وظائف الكلى"),
            
            // تحاليل السكر
            "glucose" to TestInfo("جلوكوز (سكر الدم)", "mg/dL", 70.0..100.0, "تحليل سكر"),
            "fasting glucose" to TestInfo("جلوكوز صيامي", "mg/dL", 70.0..100.0, "تحليل سكر"),
            "سكر صائم" to TestInfo("جلوكوز صيامي", "mg/dL", 70.0..100.0, "تحليل سكر"),
            "سكر الدم" to TestInfo("جلوكوز (سكر الدم)", "mg/dL", 70.0..100.0, "تحليل سكر"),
            "hba1c" to TestInfo("الهيموجلوبين السكري", "%", 4.0..5.6, "تحليل سكر"),
            "a1c" to TestInfo("الهيموجلوبين السكري", "%", 4.0..5.6, "تحليل سكر"),
            "السكر التراكمي" to TestInfo("الهيموجلوبين السكري", "%", 4.0..5.6, "تحليل سكر"),
            
            // تحاليل الدهون
            "cholesterol" to TestInfo("الكولسترول الكلي", "mg/dL", 125.0..200.0, "تحليل دهون"),
            "كولسترول" to TestInfo("الكولسترول الكلي", "mg/dL", 125.0..200.0, "تحليل دهون"),
            "hdl" to TestInfo("الكولسترول النافع", "mg/dL", 40.0..60.0, "تحليل دهون"),
            "الكولسترول النافع" to TestInfo("الكولسترول النافع", "mg/dL", 40.0..60.0, "تحليل دهون"),
            "ldl" to TestInfo("الكولسترول الضار", "mg/dL", 0.0..100.0, "تحليل دهون"),
            "الكولسترول الضار" to TestInfo("الكولسترول الضار", "mg/dL", 0.0..100.0, "تحليل دهون"),
            "triglycerides" to TestInfo("الدهون الثلاثية", "mg/dL", 0.0..150.0, "تحليل دهون"),
            "الدهون الثلاثية" to TestInfo("الدهون الثلاثية", "mg/dL", 0.0..150.0, "تحليل دهون"),
            
            // تحاليل هرمونات الغدة الدرقية
            "tsh" to TestInfo("هرمون TSH (الغدة الدرقية)", "μIU/mL", 0.4..4.0, "هرمونات الغدة الدرقية"),
            "free t4" to TestInfo("هرمون T4 الحر", "ng/dL", 0.8..1.8, "هرمونات الغدة الدرقية"),
            "free t3" to TestInfo("هرمون T3 الحر", "pg/mL", 2.3..4.2, "هرمونات الغدة الدرقية"),
            "الغدة الدرقية" to TestInfo("هرمون TSH (الغدة الدرقية)", "μIU/mL", 0.4..4.0, "هرمونات الغدة الدرقية")
        )
    }
    
    /**
     * تحليل نص واستخراج نتائج التحاليل الطبية منه
     * @param text النص المستخرج من صورة التحليل الطبي أو المُدخل من قبل المستخدم
     * @return قائمة بنتائج التحاليل المستخرجة
     */
    suspend fun analyzeLabResultsText(text: String): List<ExtractedLabResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<ExtractedLabResult>()
        
        try {
            // البحث عن أنماط محتملة لنتائج التحاليل المخبرية
            
            // النمط الأول: اسم التحليل: القيمة وحدة القياس
            val pattern1 = Pattern.compile("([\\p{L}\\s\\d]+)\\s*[\\:=]\\s*(\\d+\\.?\\d*)\\s*([a-zA-Z%\\/%\\^\\d\\u0621-\\u064A\\s]+)")
            val matcher1 = pattern1.matcher(text)
            
            while (matcher1.find()) {
                val testName = matcher1.group(1).trim().toLowerCase(Locale.ROOT)
                val valueStr = matcher1.group(2)
                val unitAndMore = matcher1.group(3).trim()
                
                // استخراج وحدة القياس من النص
                val unit = extractUnit(unitAndMore)
                
                try {
                    val value = valueStr.toDouble()
                    
                    // البحث عن التحليل في قائمة التحاليل المعروفة
                    val testInfo = findTestInfo(testName)
                    if (testInfo != null) {
                        results.add(
                            ExtractedLabResult(
                                name = testInfo.standardName,
                                value = value,
                                unit = if (unit.isNotEmpty()) unit else testInfo.unit,
                                normalRange = testInfo.normalRange,
                                category = testInfo.category,
                                confidence = 0.85
                            )
                        )
                    } else {
                        // إذا لم نجد معلومات عن التحليل، نضيفه بمعلومات افتراضية
                        results.add(
                            ExtractedLabResult(
                                name = testName,
                                value = value,
                                unit = unit,
                                normalRange = 0.0..0.0, // نطاق غير معروف
                                category = "تحليل غير مصنف",
                                confidence = 0.6
                            )
                        )
                    }
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "خطأ في تحويل قيمة التحليل: $valueStr", e)
                }
            }
            
            // النمط الثاني: البحث عن أسماء التحاليل المعروفة والقيم العددية القريبة منها
            for ((key, testInfo) in commonLabTests) {
                val testPattern = Pattern.compile(
                    "\\b${Pattern.quote(key)}\\b[\\s\\p{Punct}]+(\\d+\\.?\\d*)",
                    Pattern.CASE_INSENSITIVE
                )
                val matcher = testPattern.matcher(text.toLowerCase(Locale.ROOT))
                
                while (matcher.find()) {
                    val valueStr = matcher.group(1)
                    try {
                        val value = valueStr.toDouble()
                        
                        // تحقق ما إذا كانت هذه النتيجة موجودة بالفعل
                        val exists = results.any { it.name.equals(testInfo.standardName, ignoreCase = true) }
                        
                        if (!exists) {
                            results.add(
                                ExtractedLabResult(
                                    name = testInfo.standardName,
                                    value = value,
                                    unit = testInfo.unit,
                                    normalRange = testInfo.normalRange,
                                    category = testInfo.category,
                                    confidence = 0.75
                                )
                            )
                        }
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "خطأ في تحويل قيمة التحليل: $valueStr", e)
                    }
                }
            }
            
            Log.d(TAG, "تم استخراج ${results.size} نتيجة تحليل من النص")
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل نص التحاليل الطبية", e)
        }
        
        return@withContext results
    }
    
    /**
     * استخراج وحدة القياس من نص
     */
    private fun extractUnit(text: String): String {
        // قائمة بوحدات القياس الشائعة
        val commonUnits = listOf(
            "mg/dL", "g/dL", "mmol/L", "U/L", "μIU/mL", "ng/dL", "pg/mL", "%",
            "10^3/μL", "10^6/μL", "mm/hr", "mEq/L", "مجم/ديسيلتر", "وحدة/لتر", "ملي جرام/ديسيلتر"
        )
        
        for (unit in commonUnits) {
            if (text.contains(unit, ignoreCase = true)) {
                return unit
            }
        }
        
        // استخراج وحدة قياس محتملة من النص
        val unitPattern = Pattern.compile("([a-zA-Z%\\/%\\^\\d]+)")
        val matcher = unitPattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        
        return ""
    }
    
    /**
     * البحث عن معلومات التحليل في قائمة التحاليل المعروفة
     */
    private fun findTestInfo(testName: String): TestInfo? {
        val lowerName = testName.toLowerCase(Locale.ROOT)
        
        // البحث عن تطابق مباشر
        commonLabTests[lowerName]?.let { return it }
        
        // البحث عن تطابق جزئي
        for ((key, testInfo) in commonLabTests) {
            if (lowerName.contains(key.toLowerCase(Locale.ROOT)) || 
                key.toLowerCase(Locale.ROOT).contains(lowerName)) {
                return testInfo
            }
        }
        
        return null
    }
    
    /**
     * معالجة صورة تحليل طبي واستخراج النص منها وتحليله
     * @param imageUri مسار صورة التحليل الطبي
     * @return قائمة بنتائج التحاليل المستخرجة
     */
    suspend fun processLabResultImage(imageUri: Uri): List<ExtractedLabResult> = withContext(Dispatchers.Default) {
        try {
            // استخراج النص من الصورة باستخدام OCR
            val extractedText = extractTextFromImage(imageUri)
            if (extractedText.isBlank()) {
                return@withContext emptyList<ExtractedLabResult>()
            }
            
            // تحليل النص واستخراج نتائج التحاليل
            return@withContext analyzeLabResultsText(extractedText)
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة صورة التحليل الطبي", e)
            return@withContext emptyList<ExtractedLabResult>()
        }
    }
    
    /**
     * استخراج النص من صورة باستخدام OCR
     * في تطبيق حقيقي، سيتم استخدام مكتبة OCR مثل Google ML Kit أو Tesseract OCR
     */
    private suspend fun extractTextFromImage(imageUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            // في التطبيق الحقيقي، يتم استخدام مكتبة OCR هنا
            // للتبسيط، نستخدم محاكاة لاستخراج النص
            
            val inputStream = context.contentResolver.openInputStream(imageUri)
            inputStream?.use {
                // قراءة معلومات الصورة للتحقق من أنها صورة تحليل طبي
                val exif = ExifInterface(it)
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME)
                
                // هذا محاكاة فقط - في الواقع سنستخدم OCR لاستخراج النص
                // ترجع نتيجة فارغة هنا لأنها محاكاة
                return@withContext ""
            }
            
            return@withContext ""
            
        } catch (e: IOException) {
            Log.e(TAG, "خطأ في استخراج النص من الصورة", e)
            return@withContext ""
        }
    }
    
    /**
     * تسجيل نتائج التحاليل المستخرجة في HealthTracker
     * @param extractedResults قائمة بنتائج التحاليل المستخرجة
     * @return عدد النتائج التي تم تسجيلها بنجاح
     */
    suspend fun saveExtractedResults(extractedResults: List<ExtractedLabResult>): Int = withContext(Dispatchers.Default) {
        var successCount = 0
        
        for (result in extractedResults) {
            try {
                healthTracker.recordLabTest(
                    labTestName = result.name,
                    value = result.value,
                    unit = result.unit,
                    minRange = result.normalRange.start,
                    maxRange = result.normalRange.endInclusive,
                    category = result.category,
                    date = Date(), // يمكن استخراج التاريخ من صورة التحليل في تطبيق حقيقي
                    notes = "تم استخراجه بثقة ${result.confidence * 100}%"
                )
                
                successCount++
                
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تسجيل نتيجة التحليل ${result.name}", e)
            }
        }
        
        return@withContext successCount
    }
    
    /**
     * تحليل وتفسير نتائج التحاليل المستخرجة بشكل شامل
     * @param extractedResults قائمة بنتائج التحاليل المستخرجة
     * @return تقرير تفصيلي عن نتائج التحاليل
     */
    suspend fun generateComprehensiveReport(extractedResults: List<ExtractedLabResult>): String = withContext(Dispatchers.Default) {
        try {
            if (extractedResults.isEmpty()) {
                return@withContext "لم يتم العثور على أي نتائج تحاليل للتحليل."
            }
            
            val reportBuilder = StringBuilder()
            reportBuilder.append("# تقرير نتائج التحاليل\n\n")
            
            // تقسيم النتائج حسب الفئة
            val resultsByCategory = extractedResults.groupBy { it.category }
            
            for ((category, results) in resultsByCategory) {
                reportBuilder.append("## $category\n\n")
                
                for (result in results) {
                    val status = when {
                        result.value < result.normalRange.start -> "منخفض"
                        result.value > result.normalRange.endInclusive -> "مرتفع"
                        else -> "طبيعي"
                    }
                    
                    reportBuilder.append("### ${result.name}\n")
                    reportBuilder.append("- القيمة: ${result.value} ${result.unit}\n")
                    reportBuilder.append("- المعدل الطبيعي: ${result.normalRange.start} - ${result.normalRange.endInclusive} ${result.unit}\n")
                    reportBuilder.append("- الحالة: $status\n\n")
                }
            }
            
            // تحليل وتفسير النتائج
            reportBuilder.append("## التحليل والتفسير\n\n")
            
            val analysisResults = healthTracker.analyzeLabResults(
                extractedResults.associate { it.name to LabTest(
                    name = it.name,
                    value = it.value,
                    unit = it.unit,
                    normalRange = it.normalRange,
                    date = Date(),
                    category = it.category
                ) }
            )
            
            for ((name, interpretation) in analysisResults) {
                reportBuilder.append("- **$name**: $interpretation\n")
            }
            
            // توصيات عامة
            reportBuilder.append("\n## التوصيات العامة\n\n")
            
            val abnormalResults = extractedResults.filter { 
                it.value < it.normalRange.start || it.value > it.normalRange.endInclusive 
            }
            
            if (abnormalResults.isEmpty()) {
                reportBuilder.append("- نتائج التحاليل ضمن المعدلات الطبيعية. استمر في نمط الحياة الصحي واتبع توصيات الطبيب للفحوصات الدورية.\n")
            } else {
                reportBuilder.append("- يُنصح بمراجعة الطبيب لمناقشة النتائج الغير طبيعية في التحاليل.\n")
                
                // توصيات خاصة بناءً على نوع التحاليل غير الطبيعية
                val hasCholesterolIssue = abnormalResults.any { 
                    it.name.contains("كولسترول", ignoreCase = true) || 
                    it.name.contains("cholesterol", ignoreCase = true) ||
                    it.name.contains("دهون", ignoreCase = true)
                }
                
                val hasGlucoseIssue = abnormalResults.any { 
                    it.name.contains("glucose", ignoreCase = true) || 
                    it.name.contains("سكر", ignoreCase = true)
                }
                
                val hasLiverIssue = abnormalResults.any { 
                    it.name.contains("ALT", ignoreCase = true) || 
                    it.name.contains("AST", ignoreCase = true) ||
                    it.name.contains("كبد", ignoreCase = true) ||
                    it.name.contains("liver", ignoreCase = true)
                }
                
                val hasKidneyIssue = abnormalResults.any { 
                    it.name.contains("creatinine", ignoreCase = true) || 
                    it.name.contains("كرياتينين", ignoreCase = true) ||
                    it.name.contains("كلى", ignoreCase = true) ||
                    it.name.contains("kidney", ignoreCase = true)
                }
                
                if (hasCholesterolIssue) {
                    reportBuilder.append("- للمساعدة في تحسين مستويات الدهون في الدم، يُنصح باتباع نظام غذائي غني بالألياف والأوميغا 3 وقليل الدهون المشبعة، بالإضافة إلى ممارسة النشاط البدني بانتظام.\n")
                }
                
                if (hasGlucoseIssue) {
                    reportBuilder.append("- للمساعدة في تنظيم مستوى السكر في الدم، يُنصح بالحد من تناول السكريات والنشويات المكررة، وزيادة الألياف في النظام الغذائي، والمحافظة على وزن صحي، وممارسة النشاط البدني بانتظام.\n")
                }
                
                if (hasLiverIssue) {
                    reportBuilder.append("- للمساعدة في تحسين وظائف الكبد، يُنصح بتقليل تناول الكحول (إن وجد)، والتقليل من الأطعمة الدهنية والمقلية، وشرب كميات كافية من الماء، واستشارة الطبيب حول الأدوية التي تؤثر على الكبد.\n")
                }
                
                if (hasKidneyIssue) {
                    reportBuilder.append("- للمساعدة في تحسين وظائف الكلى، يُنصح بالالتزام بشرب كميات كافية من الماء، وتقليل تناول الملح، ومراقبة ضغط الدم، وتناول نظام غذائي متوازن يراعي احتياجات الكلى.\n")
                }
            }
            
            // تنبيه بالتشاور مع الطبيب
            reportBuilder.append("\n**ملاحظة هامة**: هذا التقرير لا يغني عن استشارة الطبيب المختص. يرجى مراجعة طبيبك لتفسير شامل للنتائج وتقديم التوصيات المناسبة لحالتك الصحية.\n")
            
            return@withContext reportBuilder.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إنشاء تقرير شامل للتحاليل", e)
            return@withContext "حدث خطأ أثناء إنشاء التقرير. يرجى المحاولة مرة أخرى."
        }
    }
}

/**
 * معلومات التحليل المخبري
 */
data class TestInfo(
    val standardName: String,  // الاسم المعياري للتحليل
    val unit: String,          // وحدة القياس
    val normalRange: ClosedRange<Double>,  // النطاق الطبيعي
    val category: String       // فئة التحليل (مثل: تحليل دم، وظائف كبد، إلخ)
)

/**
 * نتيجة تحليل مستخرجة من نص أو صورة
 */
data class ExtractedLabResult(
    val name: String,          // اسم التحليل
    val value: Double,         // القيمة
    val unit: String,          // وحدة القياس
    val normalRange: ClosedRange<Double>,  // النطاق الطبيعي
    val category: String,      // فئة التحليل
    val confidence: Double     // مستوى الثقة في دقة الاستخراج (0-1)
)