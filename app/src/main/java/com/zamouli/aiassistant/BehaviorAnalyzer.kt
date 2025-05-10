package com.example.aiassistant

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * محلل سلوك المستخدم
 * يقوم بجمع وتحليل بيانات استخدام الجهاز لفهم عادات وتفضيلات المستخدم
 */
class BehaviorAnalyzer(private val context: Context, private val userProfileManager: UserProfileManager) {
    
    companion object {
        private const val TAG = "BehaviorAnalyzer"
    }
    
    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }
    
    /**
     * تحليل استخدام التطبيقات في فترة زمنية محددة
     * يتطلب أذونات PACKAGE_USAGE_STATS
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    suspend fun analyzeAppUsage(days: Int = 7): Map<String, AppUsageData> = withContext(Dispatchers.IO) {
        try {
            if (usageStatsManager == null) {
                Log.e(TAG, "UsageStatsManager غير متوفر")
                return@withContext emptyMap<String, AppUsageData>()
            }
            
            // تحديد الفترة الزمنية
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.DAYS.toMillis(days.toLong())
            
            // الحصول على أحداث الاستخدام
            val events = usageStatsManager!!.queryEvents(startTime, endTime)
            val usageData = mutableMapOf<String, AppUsageData>()
            
            // متغيرات لتتبع حالة التطبيق الحالي
            var currentPackage = ""
            var lastEventTime = 0L
            
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // تسجيل وقت بدء استخدام التطبيق
                        currentPackage = event.packageName
                        lastEventTime = event.timeStamp
                        
                        if (!usageData.containsKey(currentPackage)) {
                            usageData[currentPackage] = AppUsageData(
                                packageName = currentPackage,
                                totalTimeMs = 0,
                                launchCount = 0,
                                lastUsed = Date(lastEventTime)
                            )
                        }
                        
                        // زيادة عدد مرات الفتح
                        val data = usageData[currentPackage]!!
                        usageData[currentPackage] = data.copy(launchCount = data.launchCount + 1)
                    }
                    
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (currentPackage == event.packageName && lastEventTime > 0) {
                            // حساب مدة الاستخدام وإضافتها إلى الإجمالي
                            val usageDuration = event.timeStamp - lastEventTime
                            
                            val data = usageData[currentPackage]!!
                            usageData[currentPackage] = data.copy(
                                totalTimeMs = data.totalTimeMs + usageDuration,
                                lastUsed = Date(event.timeStamp)
                            )
                            
                            // تسجيل البيانات في مدير الملف الشخصي
                            userProfileManager.recordBehavior(
                                category = "app_usage",
                                action = currentPackage,
                                value = usageDuration
                            )
                        }
                        
                        // إعادة تعيين المتغيرات
                        currentPackage = ""
                        lastEventTime = 0
                    }
                }
            }
            
            // ترتيب التطبيقات حسب وقت الاستخدام
            return@withContext usageData
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل استخدام التطبيقات: ${e.message}")
            return@withContext emptyMap<String, AppUsageData>()
        }
    }
    
    /**
     * تحليل أنماط النوم بناء على استخدام الجهاز
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    suspend fun analyzeSleepPattern(days: Int = 14): SleepPattern = withContext(Dispatchers.IO) {
        try {
            if (usageStatsManager == null) {
                Log.e(TAG, "UsageStatsManager غير متوفر")
                return@withContext SleepPattern()
            }
            
            // لكل يوم في الفترة المحددة
            val dailyPatterns = mutableListOf<DailySleepData>()
            val calendar = Calendar.getInstance()
            val endOfDay = Calendar.getInstance()
            
            for (i in 0 until days) {
                // تعيين بداية ونهاية اليوم
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                
                endOfDay.time = calendar.time
                endOfDay.set(Calendar.HOUR_OF_DAY, 23)
                endOfDay.set(Calendar.MINUTE, 59)
                endOfDay.set(Calendar.SECOND, 59)
                
                val startOfDay = calendar.timeInMillis
                val endOfDayTime = endOfDay.timeInMillis
                
                // تحليل الفترات التي لم يستخدم فيها الهاتف لأكثر من ساعتين متتاليتين
                val events = usageStatsManager!!.queryEvents(startOfDay, endOfDayTime)
                
                // تتبع آخر نشاط
                var lastInteractionTime = 0L
                var longestInactiveStart = 0L
                var longestInactiveDuration = 0L
                
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || 
                        event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        
                        // تسجيل وقت التفاعل
                        val interactionTime = event.timeStamp
                        
                        if (lastInteractionTime > 0) {
                            // حساب فترة السكون
                            val inactiveDuration = interactionTime - lastInteractionTime
                            
                            // إذا كانت فترة السكون أكثر من ساعتين وأطول من الفترة السابقة
                            if (inactiveDuration > TimeUnit.HOURS.toMillis(2) && 
                                inactiveDuration > longestInactiveDuration) {
                                
                                longestInactiveStart = lastInteractionTime
                                longestInactiveDuration = inactiveDuration
                            }
                        }
                        
                        lastInteractionTime = interactionTime
                    }
                }
                
                // إذا وجدت فترة سكون كبيرة، سجلها كفترة نوم محتملة
                if (longestInactiveDuration > 0) {
                    val date = Date(startOfDay)
                    val sleepStart = Date(longestInactiveStart)
                    val sleepDurationHours = longestInactiveDuration / (1000.0 * 60 * 60)
                    
                    dailyPatterns.add(DailySleepData(
                        date = date,
                        probableSleepStart = sleepStart,
                        sleepDurationHours = sleepDurationHours
                    ))
                    
                    // تسجيل البيانات في مدير الملف الشخصي
                    if (sleepDurationHours >= 4 && sleepDurationHours <= 12) {
                        userProfileManager.recordHealthData(
                            type = "sleep",
                            value = sleepDurationHours,
                            notes = "تقدير تلقائي بناءً على عدم نشاط الهاتف"
                        )
                    }
                }
            }
            
            // حساب متوسط مدة النوم ووقت النوم المعتاد
            var totalSleepHours = 0.0
            var totalSleepStartHour = 0
            var count = 0
            
            dailyPatterns.forEach { patternData ->
                totalSleepHours += patternData.sleepDurationHours
                
                // استخراج ساعة بداية النوم
                val sleepCalendar = Calendar.getInstance()
                sleepCalendar.time = patternData.probableSleepStart
                totalSleepStartHour += sleepCalendar.get(Calendar.HOUR_OF_DAY)
                
                count++
            }
            
            val averageSleepHours = if (count > 0) totalSleepHours / count else 0.0
            val averageSleepStartHour = if (count > 0) totalSleepStartHour / count else 0
            
            return@withContext SleepPattern(
                averageSleepDurationHours = averageSleepHours,
                averageSleepStartHour = averageSleepStartHour,
                dailyPatterns = dailyPatterns
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل أنماط النوم: ${e.message}")
            return@withContext SleepPattern()
        }
    }
    
    /**
     * تحليل استخدام الهاتف حسب الوقت من اليوم
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    suspend fun analyzeUsageByTimeOfDay(days: Int = 7): Map<Int, Long> = withContext(Dispatchers.IO) {
        try {
            val hourlyUsage = mutableMapOf<Int, Long>()
            
            // تهيئة القيم
            for (i in 0..23) {
                hourlyUsage[i] = 0
            }
            
            // تحديد الفترة الزمنية
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.DAYS.toMillis(days.toLong())
            
            val events = usageStatsManager?.queryEvents(startTime, endTime)
                ?: return@withContext hourlyUsage
            
            var lastPackage = ""
            var lastTime = 0L
            
            val event = UsageEvents.Event()
            val calendar = Calendar.getInstance()
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPackage = event.packageName
                    lastTime = event.timeStamp
                } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && 
                           lastPackage == event.packageName && lastTime > 0) {
                    
                    val duration = event.timeStamp - lastTime
                    
                    // تقسيم الاستخدام حسب الساعة
                    var currentTime = lastTime
                    while (currentTime < event.timeStamp) {
                        calendar.timeInMillis = currentTime
                        val hour = calendar.get(Calendar.HOUR_OF_DAY)
                        
                        // حساب مقدار الاستخدام في هذه الساعة
                        val nextHourStart = calendar.clone() as Calendar
                        nextHourStart.set(Calendar.MINUTE, 0)
                        nextHourStart.set(Calendar.SECOND, 0)
                        nextHourStart.set(Calendar.MILLISECOND, 0)
                        nextHourStart.add(Calendar.HOUR_OF_DAY, 1)
                        
                        val timeInThisHour = minOf(nextHourStart.timeInMillis, event.timeStamp) - currentTime
                        
                        // إضافة الوقت إلى الإجمالي لهذه الساعة
                        hourlyUsage[hour] = (hourlyUsage[hour] ?: 0) + timeInThisHour
                        
                        // الانتقال إلى الساعة التالية
                        currentTime = nextHourStart.timeInMillis
                    }
                    
                    lastPackage = ""
                    lastTime = 0
                }
            }
            
            // تسجيل نمط الاستخدام اليومي
            val mostActiveHour = hourlyUsage.entries.maxByOrNull { it.value }?.key ?: 12
            userProfileManager.recordBehavior(
                category = "usage_pattern",
                action = "most_active_hour",
                value = mostActiveHour
            )
            
            return@withContext hourlyUsage
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل الاستخدام حسب وقت اليوم: ${e.message}")
            return@withContext emptyMap<Int, Long>()
        }
    }
    
    /**
     * تحليل الاهتمامات بناء على سجل البحث والتفاعلات
     */
    suspend fun analyzeInterests(): List<UserInterest> = withContext(Dispatchers.Default) {
        try {
            val interactionHistory = userProfileManager.getInteractionHistory(100)
            val interestMap = mutableMapOf<String, Int>()
            
            // استخراج الكلمات المفتاحية من استفسارات المستخدم
            val stopWords = setOf("من", "في", "على", "إلى", "عن", "مع", "هل", "ما", "ماذا", "كيف", 
                "لماذا", "متى", "أين", "من", "هو", "هي", "نحن", "هم", "افتح", "ابحث", "اتصل", "أرسل", 
                "ضبط", "أخبرني", "معلومات", "معنى", "تعريف")
            
            interactionHistory.forEach { interaction ->
                // تقسيم الاستفسار إلى كلمات
                val words = interaction.query.split(" ", "،", ".", "؟", "!")
                    .filter { it.length > 3 && !stopWords.contains(it.toLowerCase()) }
                
                words.forEach { word ->
                    interestMap[word] = (interestMap[word] ?: 0) + 1
                }
            }
            
            // تحويل إلى قائمة مرتبة
            return@withContext interestMap.entries
                .sortedByDescending { it.value }
                .take(20)
                .map { UserInterest(it.key, it.value) }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل الاهتمامات: ${e.message}")
            return@withContext emptyList<UserInterest>()
        }
    }
    
    /**
     * تحليل الصحة العامة بناء على أنماط النوم والنشاط
     */
    suspend fun analyzeHealthStatus(): HealthStatusReport = withContext(Dispatchers.Default) {
        try {
            // تحليل بيانات النوم
            val sleepData = userProfileManager.analyzeHealthTrend("sleep", 14)
            val avgSleep = if (sleepData.isNotEmpty()) {
                sleepData.sumByDouble { (it.second as? Double) ?: 0.0 } / sleepData.size
            } else 0.0
            
            // تحليل بيانات النشاط البدني
            val activityData = userProfileManager.analyzeHealthTrend("physical_activity", 14)
            val avgActivity = if (activityData.isNotEmpty()) {
                activityData.sumByDouble { (it.second as? Double) ?: 0.0 } / activityData.size
            } else 0.0
            
            // تقييم حالة النوم
            val sleepQuality = when {
                avgSleep >= 7.0 && avgSleep <= 9.0 -> "جيدة"
                avgSleep > 9.0 -> "زائدة عن الحاجة"
                avgSleep >= 6.0 -> "غير كافية قليلاً"
                avgSleep > 0 -> "غير كافية"
                else -> "غير معروفة"
            }
            
            // تقييم حالة النشاط
            val activityLevel = when {
                avgActivity >= 30.0 -> "جيد"
                avgActivity >= 15.0 -> "متوسط"
                avgActivity > 0 -> "منخفض"
                else -> "غير معروف"
            }
            
            // توصيات بناء على التحليل
            val recommendations = mutableListOf<String>()
            
            if (avgSleep < 7.0 && avgSleep > 0) {
                recommendations.add("يُنصح بزيادة ساعات النوم إلى 7-9 ساعات يومياً للحفاظ على الصحة")
            } else if (avgSleep > 9.0) {
                recommendations.add("قد تشير زيادة النوم عن 9 ساعات إلى مشاكل صحية، يفضل مراجعة الطبيب")
            }
            
            if (avgActivity < 30.0 && avgActivity >= 0.0) {
                recommendations.add("يُنصح بممارسة النشاط البدني لمدة 30 دقيقة على الأقل يومياً")
            }
            
            return@withContext HealthStatusReport(
                sleepQuality = sleepQuality,
                activityLevel = activityLevel,
                averageSleepHours = avgSleep,
                averageActivityMinutes = avgActivity,
                recommendations = recommendations
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل الحالة الصحية: ${e.message}")
            return@withContext HealthStatusReport()
        }
    }
}

/**
 * بيانات استخدام التطبيق
 */
data class AppUsageData(
    val packageName: String,
    val totalTimeMs: Long,
    val launchCount: Int,
    val lastUsed: Date
) {
    fun getTotalHours(): Double = totalTimeMs / (1000.0 * 60 * 60)
    
    fun getFormattedTime(): String {
        val hours = totalTimeMs / (1000 * 60 * 60)
        val minutes = (totalTimeMs % (1000 * 60 * 60)) / (1000 * 60)
        
        return if (hours > 0) {
            "$hours ساعة و $minutes دقيقة"
        } else {
            "$minutes دقيقة"
        }
    }
}

/**
 * نمط النوم
 */
data class SleepPattern(
    val averageSleepDurationHours: Double = 0.0,
    val averageSleepStartHour: Int = 0,
    val dailyPatterns: List<DailySleepData> = emptyList()
)

/**
 * بيانات النوم اليومية
 */
data class DailySleepData(
    val date: Date,
    val probableSleepStart: Date,
    val sleepDurationHours: Double
)

/**
 * اهتمام المستخدم
 */
data class UserInterest(
    val keyword: String,
    val frequency: Int
)

/**
 * تقرير الحالة الصحية
 */
data class HealthStatusReport(
    val sleepQuality: String = "غير معروفة",
    val activityLevel: String = "غير معروف",
    val averageSleepHours: Double = 0.0,
    val averageActivityMinutes: Double = 0.0,
    val recommendations: List<String> = emptyList()
)