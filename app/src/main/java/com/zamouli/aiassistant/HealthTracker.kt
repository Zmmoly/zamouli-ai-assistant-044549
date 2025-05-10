package com.example.aiassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * متتبع الصحة ونمط الحياة
 * يقوم بجمع وتحليل البيانات المتعلقة بالصحة والنشاط البدني للمستخدم
 */
class HealthTracker(
    private val context: Context,
    private val userProfileManager: UserProfileManager
) : SensorEventListener {
    
    companion object {
        private const val TAG = "HealthTracker"
        private const val STEPS_THRESHOLD = 20 // الحد الأدنى للخطوات لاعتبارها نشاطًا بدنيًا
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    
    private var lastStepCount: Float = 0f
    private var initialStepCount: Float = -1f
    private var lastHeartRate: Float = 0f
    
    /**
     * بدء تتبع البيانات الصحية
     */
    fun startTracking() {
        // التحقق من أذونات الوصول
        val hasBodySensorsPermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        
        // تسجيل مستمع لحسابات الخطوات
        if (stepCounter != null) {
            sensorManager.registerListener(
                this,
                stepCounter,
                SensorManager.SENSOR_DELAY_UI
            )
            Log.d(TAG, "بدء تتبع الخطوات")
        } else {
            Log.w(TAG, "مستشعر عداد الخطوات غير متوفر على هذا الجهاز")
        }
        
        // تسجيل مستمع لقياس معدل ضربات القلب إذا كان متاحًا وتم منح الإذن
        if (heartRateSensor != null && hasBodySensorsPermission) {
            sensorManager.registerListener(
                this,
                heartRateSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d(TAG, "بدء تتبع معدل ضربات القلب")
        } else if (heartRateSensor != null) {
            Log.w(TAG, "إذن الوصول إلى مستشعرات الجسم غير ممنوح")
        } else {
            Log.w(TAG, "مستشعر معدل ضربات القلب غير متوفر على هذا الجهاز")
        }
    }
    
    /**
     * إيقاف تتبع البيانات الصحية
     */
    fun stopTracking() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "تم إيقاف تتبع البيانات الصحية")
    }
    
    /**
     * تقدير مدة النوم بناء على استخدام الهاتف
     */
    suspend fun estimateSleepDuration(days: Int = 1): SleepEstimation = withContext(Dispatchers.Default) {
        try {
            // الحصول على آخر تسجيلات للنوم من مدير الملف الشخصي
            val sleepEntries = userProfileManager.analyzeHealthTrend("sleep", days)
            
            if (sleepEntries.isEmpty()) {
                return@withContext SleepEstimation(
                    estimatedHours = 0.0,
                    quality = "غير معروفة",
                    isEstimated = true
                )
            }
            
            // حساب متوسط ساعات النوم
            val totalHours = sleepEntries.sumByDouble { (it.second as? Double) ?: 0.0 }
            val avgHours = totalHours / sleepEntries.size
            
            // تقييم جودة النوم
            val quality = when {
                avgHours >= 7.0 && avgHours <= 9.0 -> "جيدة"
                avgHours > 9.0 -> "زائدة"
                avgHours >= 6.0 -> "مقبولة"
                avgHours > 0.0 -> "غير كافية"
                else -> "غير معروفة"
            }
            
            return@withContext SleepEstimation(
                estimatedHours = avgHours,
                quality = quality,
                isEstimated = true
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تقدير مدة النوم: ${e.message}")
            return@withContext SleepEstimation(
                estimatedHours = 0.0,
                quality = "غير معروفة",
                isEstimated = true
            )
        }
    }
    
    /**
     * تسجيل نشاط بدني يدويًا من قبل المستخدم
     */
    fun recordManualActivity(activityType: String, durationMinutes: Double, intensityLevel: String, notes: String = "") {
        try {
            val activityData = mapOf(
                "type" to activityType,
                "duration" to durationMinutes,
                "intensity" to intensityLevel
            )
            
            userProfileManager.recordHealthData(
                type = "physical_activity",
                value = durationMinutes,
                notes = "$activityType - $intensityLevel\n$notes"
            )
            
            Log.d(TAG, "تم تسجيل نشاط بدني: $activityType لمدة $durationMinutes دقيقة")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل النشاط البدني: ${e.message}")
        }
    }
    
    /**
     * تسجيل مزاج المستخدم
     */
    fun recordMood(mood: String, intensity: Int, notes: String = "") {
        try {
            userProfileManager.recordHealthData(
                type = "mood",
                value = mapOf("mood" to mood, "intensity" to intensity),
                notes = notes
            )
            
            Log.d(TAG, "تم تسجيل الحالة المزاجية: $mood بشدة $intensity")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل الحالة المزاجية: ${e.message}")
        }
    }
    
    /**
     * تسجيل مؤشرات حيوية يدويًا
     */
    fun recordVitalSign(type: String, value: Any, units: String, notes: String = "") {
        try {
            val vitalData = mapOf(
                "value" to value,
                "units" to units
            )
            
            userProfileManager.recordHealthData(
                type = type,
                value = vitalData,
                notes = notes
            )
            
            Log.d(TAG, "تم تسجيل مؤشر حيوي: $type = $value $units")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل المؤشر الحيوي: ${e.message}")
        }
    }
    
    /**
     * استدعاة عند تغير قيمة المستشعر
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val steps = event.values[0]
                
                // تهيئة القيمة الأولية
                if (initialStepCount < 0) {
                    initialStepCount = steps
                    lastStepCount = steps
                    return
                }
                
                // حساب التغير في الخطوات
                val stepsDelta = steps - lastStepCount
                
                // تسجيل النشاط البدني إذا تجاوز الحد المعين
                if (stepsDelta > STEPS_THRESHOLD) {
                    val activityMinutes = stepsDelta / 100.0 // تقدير تقريبي: 100 خطوة ~ دقيقة من النشاط
                    
                    userProfileManager.recordHealthData(
                        type = "physical_activity",
                        value = activityMinutes,
                        notes = "نشاط مقدر من الخطوات: $stepsDelta خطوة"
                    )
                    
                    Log.d(TAG, "تم تسجيل نشاط بدني: $stepsDelta خطوة، حوالي $activityMinutes دقيقة")
                }
                
                lastStepCount = steps
            }
            
            Sensor.TYPE_HEART_RATE -> {
                val heartRate = event.values[0]
                
                // تجنب تسجيل القراءات المتكررة أو غير المنطقية
                if (heartRate != lastHeartRate && heartRate > 40 && heartRate < 200) {
                    userProfileManager.recordHealthData(
                        type = "heart_rate",
                        value = heartRate,
                        notes = "قياس تلقائي من المستشعر"
                    )
                    
                    Log.d(TAG, "تم تسجيل معدل ضربات القلب: $heartRate نبضة في الدقيقة")
                    lastHeartRate = heartRate
                }
            }
        }
    }
    
    /**
     * استدعاة عند تغير دقة المستشعر
     */
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "تغيرت دقة المستشعر ${sensor.name} إلى $accuracy")
    }
    
    /**
     * تسجيل نتيجة تحليل مخبري
     * @param labTestName اسم التحليل
     * @param value القيمة
     * @param unit وحدة القياس
     * @param minRange الحد الأدنى للنطاق الطبيعي
     * @param maxRange الحد الأعلى للنطاق الطبيعي
     * @param category فئة التحليل
     * @param notes ملاحظات إضافية
     */
    fun recordLabTest(
        labTestName: String,
        value: Double,
        unit: String,
        minRange: Double,
        maxRange: Double,
        category: String,
        date: Date = Date(),
        notes: String = ""
    ) {
        try {
            val labTest = LabTest(
                name = labTestName,
                value = value,
                unit = unit,
                normalRange = minRange..maxRange,
                date = date,
                category = category,
                notes = notes
            )
            
            val labData = mapOf(
                "name" to labTestName,
                "value" to value,
                "unit" to unit,
                "minRange" to minRange,
                "maxRange" to maxRange,
                "category" to category,
                "date" to date.time,
                "notes" to notes
            )
            
            userProfileManager.recordHealthData(
                type = "lab_test",
                value = labData,
                notes = "تم تسجيل نتيجة تحليل $labTestName = $value $unit"
            )
            
            // تحديث التاريخ الطبي للمستخدم
            updateMedicalHistory(labTest)
            
            Log.d(TAG, "تم تسجيل تحليل $labTestName = $value $unit (النطاق الطبيعي: $minRange - $maxRange $unit)")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل نتيجة التحليل: ${e.message}")
        }
    }
    
    /**
     * تحديث التاريخ الطبي بناءً على نتيجة تحليل جديدة
     */
    private fun updateMedicalHistory(labTest: LabTest) {
        try {
            // فحص ما إذا كانت النتيجة خارج النطاق الطبيعي
            if (labTest.value < labTest.normalRange.start || labTest.value > labTest.normalRange.endInclusive) {
                // تسجيل حالة صحية محتملة استناداً إلى نوع التحليل
                val condition = when {
                    labTest.name.contains("glucose", ignoreCase = true) || 
                    labTest.name.contains("سكر", ignoreCase = true) -> {
                        if (labTest.value > labTest.normalRange.endInclusive) "ارتفاع سكر الدم" else "انخفاض سكر الدم"
                    }
                    labTest.name.contains("cholesterol", ignoreCase = true) || 
                    labTest.name.contains("كولسترول", ignoreCase = true) -> {
                        "ارتفاع الكولسترول"
                    }
                    labTest.name.contains("hemoglobin", ignoreCase = true) || 
                    labTest.name.contains("هيموجلوبين", ignoreCase = true) -> {
                        if (labTest.value < labTest.normalRange.start) "فقر دم" else "ارتفاع الهيموجلوبين"
                    }
                    labTest.name.contains("ALT", ignoreCase = true) || 
                    labTest.name.contains("AST", ignoreCase = true) || 
                    labTest.name.contains("انزيمات الكبد", ignoreCase = true) -> {
                        "ارتفاع انزيمات الكبد"
                    }
                    labTest.name.contains("creatinine", ignoreCase = true) || 
                    labTest.name.contains("كرياتينين", ignoreCase = true) -> {
                        "مشكلة في وظائف الكلى"
                    }
                    labTest.name.contains("TSH", ignoreCase = true) || 
                    labTest.name.contains("الغدة الدرقية", ignoreCase = true) -> {
                        if (labTest.value > labTest.normalRange.endInclusive) "قصور الغدة الدرقية" else "فرط نشاط الغدة الدرقية"
                    }
                    labTest.name.contains("white blood", ignoreCase = true) || 
                    labTest.name.contains("WBC", ignoreCase = true) || 
                    labTest.name.contains("كريات بيضاء", ignoreCase = true) -> {
                        if (labTest.value > labTest.normalRange.endInclusive) "التهاب محتمل" else "ضعف المناعة المحتمل"
                    }
                    else -> "قيم غير طبيعية في التحاليل"
                }
                
                // تسجيل الحالة الصحية
                userProfileManager.setPreference("medical_conditions", labTest.name, condition)
                
                // تسجيل معلومات إضافية عن التحليل
                val testInfo = mapOf(
                    "name" to labTest.name,
                    "abnormal_value" to labTest.value,
                    "unit" to labTest.unit,
                    "date" to labTest.date.time,
                    "condition" to condition
                )
                userProfileManager.setPreference("abnormal_tests", labTest.name, testInfo)
                
                Log.d(TAG, "تم تحديث التاريخ الطبي: إضافة حالة $condition بناءً على نتيجة تحليل ${labTest.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحديث التاريخ الطبي: ${e.message}")
        }
    }
    
    /**
     * الحصول على نتائج التحاليل المخبرية المخزنة
     */
    suspend fun getStoredLabTests(days: Int = 365): Map<String, LabTest> = withContext(Dispatchers.Default) {
        try {
            val labData = userProfileManager.analyzeHealthTrend("lab_test", days)
            val results = mutableMapOf<String, LabTest>()
            
            for (entry in labData) {
                val data = entry.second as? Map<*, *> ?: continue
                
                val name = data["name"] as? String ?: continue
                val value = (data["value"] as? Number)?.toDouble() ?: continue
                val unit = data["unit"] as? String ?: ""
                val minRange = (data["minRange"] as? Number)?.toDouble() ?: 0.0
                val maxRange = (data["maxRange"] as? Number)?.toDouble() ?: 0.0
                val category = data["category"] as? String ?: "عام"
                val dateTime = (data["date"] as? Number)?.toLong() ?: Date().time
                val notes = data["notes"] as? String ?: ""
                
                val labTest = LabTest(
                    name = name,
                    value = value,
                    unit = unit,
                    normalRange = minRange..maxRange,
                    date = Date(dateTime),
                    category = category,
                    notes = notes
                )
                
                // نخزن أحدث نتيجة لكل تحليل
                if (!results.containsKey(name) || results[name]!!.date.before(labTest.date)) {
                    results[name] = labTest
                }
            }
            
            return@withContext results
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في استرجاع نتائج التحاليل: ${e.message}")
            return@withContext emptyMap()
        }
    }
    
    /**
     * تحليل نتائج التحاليل المخبرية وتقديم تفسير لها
     */
    suspend fun analyzeLabResults(labTests: Map<String, LabTest>): Map<String, String> = withContext(Dispatchers.Default) {
        val analysis = mutableMapOf<String, String>()
        
        try {
            for ((name, test) in labTests) {
                val status = when {
                    test.value < test.normalRange.start -> "منخفض"
                    test.value > test.normalRange.endInclusive -> "مرتفع"
                    else -> "طبيعي"
                }
                
                val interpretation = when {
                    name.contains("glucose", ignoreCase = true) || name.contains("سكر", ignoreCase = true) -> {
                        when (status) {
                            "منخفض" -> "مستوى سكر الدم منخفض. قد يدل على نقص السكر في الدم (Hypoglycemia) ويمكن أن يسبب الدوخة والتعب."
                            "مرتفع" -> "مستوى سكر الدم مرتفع. قد يدل على مقدمات السكري أو مرض السكري، خاصة إذا كان الارتفاع مستمراً."
                            else -> "مستوى سكر الدم ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("cholesterol", ignoreCase = true) || name.contains("كولسترول", ignoreCase = true) -> {
                        when (status) {
                            "مرتفع" -> "مستوى الكولسترول مرتفع. قد يزيد من خطر الإصابة بأمراض القلب والأوعية الدموية."
                            else -> "مستوى الكولسترول ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("hemoglobin", ignoreCase = true) || name.contains("هيموجلوبين", ignoreCase = true) -> {
                        when (status) {
                            "منخفض" -> "مستوى الهيموجلوبين منخفض. قد يدل على فقر الدم ويمكن أن يسبب التعب وضيق التنفس."
                            "مرتفع" -> "مستوى الهيموجلوبين مرتفع. قد يدل على مشاكل في الرئة أو الكلى أو حالات أخرى."
                            else -> "مستوى الهيموجلوبين ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("ALT", ignoreCase = true) || name.contains("AST", ignoreCase = true) || name.contains("انزيمات الكبد", ignoreCase = true) -> {
                        when (status) {
                            "مرتفع" -> "مستوى إنزيمات الكبد مرتفع. قد يدل على مشكلة في وظائف الكبد أو التهاب الكبد."
                            else -> "مستوى إنزيمات الكبد ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("creatinine", ignoreCase = true) || name.contains("كرياتينين", ignoreCase = true) -> {
                        when (status) {
                            "مرتفع" -> "مستوى الكرياتينين مرتفع. قد يدل على مشكلة في وظائف الكلى."
                            else -> "مستوى الكرياتينين ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("TSH", ignoreCase = true) || name.contains("الغدة الدرقية", ignoreCase = true) -> {
                        when (status) {
                            "منخفض" -> "مستوى TSH منخفض. قد يدل على فرط نشاط الغدة الدرقية."
                            "مرتفع" -> "مستوى TSH مرتفع. قد يدل على قصور الغدة الدرقية."
                            else -> "مستوى TSH ضمن المعدل الطبيعي."
                        }
                    }
                    name.contains("WBC", ignoreCase = true) || name.contains("كريات بيضاء", ignoreCase = true) -> {
                        when (status) {
                            "منخفض" -> "عدد كريات الدم البيضاء منخفض. قد يدل على ضعف في جهاز المناعة."
                            "مرتفع" -> "عدد كريات الدم البيضاء مرتفع. قد يدل على التهاب أو عدوى."
                            else -> "عدد كريات الدم البيضاء ضمن المعدل الطبيعي."
                        }
                    }
                    else -> {
                        when (status) {
                            "منخفض" -> "القيمة أقل من المعدل الطبيعي."
                            "مرتفع" -> "القيمة أعلى من المعدل الطبيعي."
                            else -> "القيمة ضمن المعدل الطبيعي."
                        }
                    }
                }
                
                analysis[name] = interpretation
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل نتائج التحاليل: ${e.message}")
        }
        
        return@withContext analysis
    }
    
    /**
     * فهم نتائج التحاليل المخبرية وتقديم تقييم لها بناءً على تاريخ المستخدم الصحي
     */
    suspend fun interpretLabTest(labTestName: String, value: Double, unit: String, normalRange: ClosedRange<Double>): String = withContext(Dispatchers.Default) {
        try {
            // إنشاء كائن تحليل مؤقت
            val labTest = LabTest(
                name = labTestName,
                value = value,
                unit = unit,
                normalRange = normalRange,
                date = Date(),
                category = "تحليل دم"
            )
            
            // تحليل النتيجة
            val singleTest = mapOf(labTestName to labTest)
            val analysis = analyzeLabResults(singleTest)
            val interpretation = analysis[labTestName] ?: "لم أتمكن من تحليل هذه النتيجة بشكل دقيق."
            
            // الحصول على التاريخ الطبي للمستخدم
            val medicalHistory = userProfileManager.getAllPreferences("medical_conditions")
            val previousTests = getStoredLabTests()
            
            // إنشاء تقييم شامل
            val result = StringBuilder()
            
            // إضافة التفسير الأساسي
            result.append("نتيجة تحليل $labTestName = $value $unit\n")
            result.append("النطاق الطبيعي: ${normalRange.start} - ${normalRange.endInclusive} $unit\n\n")
            result.append("التفسير: $interpretation\n\n")
            
            // مقارنة بنتائج سابقة
            val previousTest = previousTests[labTestName]
            if (previousTest != null) {
                val difference = value - previousTest.value
                val trend = when {
                    difference > 0 -> "ارتفاع"
                    difference < 0 -> "انخفاض"
                    else -> "ثبات"
                }
                
                result.append("مقارنة بالنتائج السابقة: نلاحظ $trend بمقدار ${String.format("%.2f", Math.abs(difference))} $unit منذ ${getDateDifferenceText(previousTest.date)}\n\n")
            }
            
            // تقييم بناءً على الحالات الصحية المسجلة
            if (medicalHistory.isNotEmpty()) {
                result.append("ملاحظات خاصة بناءً على تاريخك الصحي:\n")
                val relevantConditions = mutableListOf<String>()
                
                for ((condition, _) in medicalHistory) {
                    when {
                        // تقييم نتائج تحليل السكر
                        (labTestName.contains("glucose", ignoreCase = true) || labTestName.contains("سكر", ignoreCase = true)) &&
                        (condition.contains("سكري", ignoreCase = true) || condition.contains("diabetes", ignoreCase = true)) -> {
                            relevantConditions.add("• بالنسبة لمرضى السكري، يجب الحفاظ على مستوى سكر الدم ضمن النطاق المستهدف حسب توجيهات الطبيب.")
                        }
                        
                        // تقييم نتائج تحليل الكولسترول
                        (labTestName.contains("cholesterol", ignoreCase = true) || labTestName.contains("كولسترول", ignoreCase = true)) &&
                        (condition.contains("قلب", ignoreCase = true) || condition.contains("ضغط", ignoreCase = true)) -> {
                            relevantConditions.add("• مع وجود تاريخ لمشاكل القلب أو ضغط الدم، يُنصح بمراقبة مستويات الكولسترول بانتظام.")
                        }
                        
                        // تقييم نتائج تحليل الكلى
                        (labTestName.contains("creatinine", ignoreCase = true) || labTestName.contains("كرياتينين", ignoreCase = true)) &&
                        (condition.contains("كلى", ignoreCase = true) || condition.contains("kidney", ignoreCase = true)) -> {
                            relevantConditions.add("• مع وجود تاريخ لمشاكل الكلى، يجب متابعة مستويات الكرياتينين بدقة أكبر.")
                        }
                        
                        // تقييم نتائج تحليل الكبد
                        (labTestName.contains("ALT", ignoreCase = true) || labTestName.contains("AST", ignoreCase = true)) &&
                        (condition.contains("كبد", ignoreCase = true) || condition.contains("liver", ignoreCase = true)) -> {
                            relevantConditions.add("• مع وجود تاريخ لمشاكل الكبد، يجب مراقبة إنزيمات الكبد بانتظام.")
                        }
                    }
                }
                
                if (relevantConditions.isEmpty()) {
                    result.append("لا توجد ملاحظات خاصة ذات صلة بتاريخك الصحي المسجل.\n")
                } else {
                    for (note in relevantConditions) {
                        result.append("$note\n")
                    }
                }
                result.append("\n")
            }
            
            // إضافة التوصيات
            result.append("التوصيات:\n")
            
            val recommendations = when {
                labTest.value < labTest.normalRange.start -> listOf(
                    "• مراجعة الطبيب لمناقشة سبب انخفاض النتيجة.",
                    "• قد تحتاج إلى إجراء المزيد من الفحوصات التكميلية.",
                    "• تأكد من تناول نظام غذائي متوازن."
                )
                labTest.value > labTest.normalRange.endInclusive -> listOf(
                    "• مراجعة الطبيب لمناقشة سبب ارتفاع النتيجة.",
                    "• قد تحتاج إلى إجراء المزيد من الفحوصات التكميلية.",
                    "• تأكد من تناول نظام غذائي متوازن ومناسب."
                )
                else -> listOf(
                    "• النتيجة ضمن المعدل الطبيعي، استمر في المحافظة على نمط حياة صحي.",
                    "• يُنصح بإجراء الفحوصات الدورية الموصى بها من قبل طبيبك."
                )
            }
            
            for (rec in recommendations) {
                result.append("$rec\n")
            }
            
            return@withContext result.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تفسير نتيجة التحليل: ${e.message}")
            return@withContext "لم أتمكن من تحليل نتيجة هذا الفحص. يُرجى استشارة الطبيب لتفسير النتائج."
        }
    }
    
    /**
     * تفسير الفترة الزمنية بين تاريخين بطريقة مفهومة
     */
    private fun getDateDifferenceText(oldDate: Date): String {
        val diffInMillis = Date().time - oldDate.time
        val diffInDays = diffInMillis / (24 * 60 * 60 * 1000)
        
        return when {
            diffInDays < 1 -> "اليوم"
            diffInDays == 1L -> "الأمس"
            diffInDays < 30 -> "منذ $diffInDays يوم"
            diffInDays < 365 -> "منذ ${diffInDays / 30} شهر"
            else -> "منذ ${diffInDays / 365} سنة"
        }
    }
    
    /**
     * تحليل بيانات الصحة وإنشاء تقرير
     */
    suspend fun generateHealthReport(): HealthReport = withContext(Dispatchers.Default) {
        try {
            // تحليل بيانات النوم
            val sleepEstimation = estimateSleepDuration(14)
            
            // تحليل بيانات النشاط البدني
            val activityData = userProfileManager.analyzeHealthTrend("physical_activity", 14)
            val avgActivity = if (activityData.isNotEmpty()) {
                activityData.sumByDouble { (it.second as? Double) ?: 0.0 } / activityData.size
            } else 0.0
            
            // تحليل بيانات المزاج
            val moodData = userProfileManager.analyzeHealthTrend("mood", 14)
            val mostCommonMood = if (moodData.isNotEmpty()) {
                moodData.groupBy { 
                    ((it.second as? Map<*, *>)?.get("mood") as? String) ?: "محايد" 
                }
                .maxByOrNull { it.value.size }
                ?.key ?: "محايد"
            } else "محايد"
            
            // الحصول على نتائج التحاليل المخبرية
            val labResults = getStoredLabTests()
            
            // توصيات بناء على التحليل
            val recommendations = mutableListOf<String>()
            
            if (sleepEstimation.estimatedHours < 7.0 && sleepEstimation.estimatedHours > 0) {
                recommendations.add("يُنصح بزيادة ساعات النوم إلى 7-9 ساعات يومياً للحفاظ على الصحة")
            }
            
            if (avgActivity < 30.0 && avgActivity >= 0) {
                recommendations.add("يُنصح بممارسة النشاط البدني لمدة 30 دقيقة على الأقل يومياً")
            }
            
            if (mostCommonMood in listOf("حزين", "غاضب", "قلق")) {
                recommendations.add("يبدو أن مزاجك متأثر في الفترة الأخيرة. حاول ممارسة أنشطة تجلب لك السعادة")
            }
            
            // توصيات بناء على نتائج التحاليل
            for ((name, test) in labResults) {
                if (test.value < test.normalRange.start || test.value > test.normalRange.endInclusive) {
                    recommendations.add("نتيجة تحليل $name خارج النطاق الطبيعي. يُنصح بمراجعة الطبيب")
                }
            }
            
            // تجميع التقرير
            return@withContext HealthReport(
                averageSleepHours = sleepEstimation.estimatedHours,
                sleepQuality = sleepEstimation.quality,
                averageActivityMinutes = avgActivity,
                dominantMood = mostCommonMood,
                labResults = labResults,
                recommendations = recommendations,
                lastUpdated = Date()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إنشاء تقرير الصحة: ${e.message}")
            return@withContext HealthReport()
        }
    }
    
    /**
     * تسجيل مدة نوم يدويًا من قبل المستخدم
     */
    fun recordManualSleep(hoursSlept: Double, quality: String, startTime: Date, notes: String = "") {
        try {
            val sleepData = mapOf(
                "duration" to hoursSlept,
                "quality" to quality,
                "startTime" to startTime
            )
            
            userProfileManager.recordHealthData(
                type = "sleep",
                value = hoursSlept,
                notes = "جودة النوم: $quality\n$notes"
            )
            
            Log.d(TAG, "تم تسجيل نوم: $hoursSlept ساعة، الجودة: $quality")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تسجيل النوم: ${e.message}")
        }
    }
}

/**
 * تقدير مدة النوم
 */
data class SleepEstimation(
    val estimatedHours: Double,
    val quality: String,
    val isEstimated: Boolean
)

/**
 * تقرير الصحة
 */
data class HealthReport(
    val averageSleepHours: Double = 0.0,
    val sleepQuality: String = "غير معروفة",
    val averageActivityMinutes: Double = 0.0,
    val dominantMood: String = "محايد",
    val labResults: Map<String, LabTest> = emptyMap(),
    val recommendations: List<String> = emptyList(),
    val lastUpdated: Date = Date()
)

/**
 * نتيجة تحليل مخبري
 */
data class LabTest(
    val name: String,
    val value: Double,
    val unit: String,
    val normalRange: ClosedRange<Double>,
    val date: Date,
    val category: String, // مثل "تحليل دم"، "كبد"، "كلى" إلخ
    val notes: String = ""
)