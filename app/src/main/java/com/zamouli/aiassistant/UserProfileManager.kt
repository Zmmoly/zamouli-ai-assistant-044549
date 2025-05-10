package com.example.aiassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * مدير الملف الشخصي للمستخدم
 * يتعقب ويخزن معلومات المستخدم وتفضيلاته وسلوكه
 */
class UserProfileManager(private val context: Context) {
    companion object {
        private const val TAG = "UserProfileManager"
        private const val PREFS_NAME = "user_profile"
        private const val KEY_PROFILE = "profile_data"
        private const val KEY_HEALTH = "health_data"
        private const val KEY_BEHAVIORS = "behavior_data"
        private const val KEY_PREFERENCES = "preferences_data"
        private const val KEY_INTERACTION_HISTORY = "interaction_history"
    }
    
    private val gson = Gson()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // نموذج البيانات الرئيسي للمستخدم
    private var userProfile: UserProfile = loadUserProfile()
    
    /**
     * تحميل ملف المستخدم من التخزين
     */
    private fun loadUserProfile(): UserProfile {
        val profileJson = sharedPreferences.getString(KEY_PROFILE, null)
        val healthJson = sharedPreferences.getString(KEY_HEALTH, null)
        val behaviorsJson = sharedPreferences.getString(KEY_BEHAVIORS, null)
        val preferencesJson = sharedPreferences.getString(KEY_PREFERENCES, null)
        val historyJson = sharedPreferences.getString(KEY_INTERACTION_HISTORY, null)
        
        val profile = if (profileJson != null) {
            gson.fromJson(profileJson, UserProfile::class.java)
        } else {
            UserProfile()
        }
        
        // تحميل البيانات الصحية
        if (healthJson != null) {
            val type = object : TypeToken<MutableList<HealthEntry>>() {}.type
            profile.healthData = gson.fromJson(healthJson, type)
        }
        
        // تحميل بيانات السلوك
        if (behaviorsJson != null) {
            val type = object : TypeToken<MutableMap<String, MutableList<BehaviorEntry>>>() {}.type
            profile.behaviorPatterns = gson.fromJson(behaviorsJson, type)
        }
        
        // تحميل التفضيلات
        if (preferencesJson != null) {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            profile.preferences = gson.fromJson(preferencesJson, type)
        }
        
        // تحميل سجل التفاعلات
        if (historyJson != null) {
            val type = object : TypeToken<MutableList<InteractionEntry>>() {}.type
            profile.interactionHistory = gson.fromJson(historyJson, type)
        }
        
        return profile
    }
    
    /**
     * حفظ ملف المستخدم في التخزين
     */
    private fun saveUserProfile() {
        val profileJson = gson.toJson(userProfile)
        val healthJson = gson.toJson(userProfile.healthData)
        val behaviorsJson = gson.toJson(userProfile.behaviorPatterns)
        val preferencesJson = gson.toJson(userProfile.preferences)
        val historyJson = gson.toJson(userProfile.interactionHistory)
        
        sharedPreferences.edit().apply {
            putString(KEY_PROFILE, profileJson)
            putString(KEY_HEALTH, healthJson)
            putString(KEY_BEHAVIORS, behaviorsJson)
            putString(KEY_PREFERENCES, preferencesJson)
            putString(KEY_INTERACTION_HISTORY, historyJson)
            apply()
        }
        
        Log.d(TAG, "تم حفظ بيانات المستخدم")
    }
    
    /**
     * إعداد الملف الشخصي الأساسي
     */
    fun setupProfile(name: String, age: Int, gender: String) {
        userProfile.name = name
        userProfile.age = age
        userProfile.gender = gender
        userProfile.creationDate = Date()
        
        saveUserProfile()
    }
    
    /**
     * تسجيل بيانات صحية جديدة
     */
    fun recordHealthData(type: String, value: Any, notes: String = "") {
        val entry = HealthEntry(
            type = type,
            value = value,
            timestamp = Date(),
            notes = notes
        )
        
        userProfile.healthData.add(entry)
        saveUserProfile()
    }
    
    /**
     * تسجيل سلوك أو عادة جديدة
     */
    fun recordBehavior(category: String, action: String, context: String = "", value: Any? = null) {
        val entry = BehaviorEntry(
            action = action,
            context = context,
            timestamp = Date(),
            value = value
        )
        
        if (!userProfile.behaviorPatterns.containsKey(category)) {
            userProfile.behaviorPatterns[category] = mutableListOf()
        }
        
        userProfile.behaviorPatterns[category]?.add(entry)
        saveUserProfile()
    }
    
    /**
     * تسجيل تفضيل جديد
     */
    fun setPreference(category: String, name: String, value: Any) {
        val key = "$category:$name"
        userProfile.preferences[key] = value
        saveUserProfile()
    }
    
    /**
     * الحصول على تفضيل
     */
    fun getPreference(category: String, name: String, defaultValue: Any? = null): Any? {
        val key = "$category:$name"
        return userProfile.preferences[key] ?: defaultValue
    }
    
    /**
     * تسجيل تفاعل جديد مع المستخدم
     */
    fun recordInteraction(type: String, query: String, response: String, emotionalState: String = "neutral") {
        val entry = InteractionEntry(
            type = type,
            query = query,
            response = response,
            timestamp = Date(),
            emotionalState = emotionalState
        )
        
        userProfile.interactionHistory.add(entry)
        
        // للحفاظ على الأداء، نحتفظ فقط بآخر 1000 تفاعل
        if (userProfile.interactionHistory.size > 1000) {
            userProfile.interactionHistory = userProfile.interactionHistory.takeLast(1000).toMutableList()
        }
        
        saveUserProfile()
    }
    
    /**
     * تحليل السلوك لفئة معينة
     */
    fun analyzeBehavior(category: String): Map<String, Int> {
        val behaviors = userProfile.behaviorPatterns[category] ?: return emptyMap()
        
        return behaviors
            .groupBy { it.action }
            .mapValues { it.value.size }
    }
    
    /**
     * تحليل النمط اليومي - متى يتفاعل المستخدم مع التطبيق
     */
    fun analyzeUsagePattern(): Map<Int, Int> {
        val hourlyDistribution = mutableMapOf<Int, Int>()
        
        for (i in 0..23) {
            hourlyDistribution[i] = 0
        }
        
        userProfile.interactionHistory.forEach { interaction ->
            val calendar = Calendar.getInstance()
            calendar.time = interaction.timestamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            
            hourlyDistribution[hour] = (hourlyDistribution[hour] ?: 0) + 1
        }
        
        return hourlyDistribution
    }
    
    /**
     * تحليل الحالة الصحية بناء على البيانات المسجلة
     */
    fun analyzeHealthTrend(type: String, days: Int = 30): List<Pair<Date, Any>> {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        val startDate = calendar.time
        
        return userProfile.healthData
            .filter { it.type == type && it.timestamp.after(startDate) }
            .sortedBy { it.timestamp }
            .map { Pair(it.timestamp, it.value) }
    }
    
    /**
     * الحصول على التفضيلات ضمن فئة
     */
    fun getPreferencesInCategory(category: String): Map<String, Any> {
        val prefix = "$category:"
        return userProfile.preferences
            .filter { it.key.startsWith(prefix) }
            .mapKeys { it.key.substring(prefix.length) }
    }
    
    /**
     * الحصول على الحالة العاطفية السائدة في الفترة الأخيرة
     */
    fun getDominantEmotionalState(days: Int = 7): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        val startDate = calendar.time
        
        return userProfile.interactionHistory
            .filter { it.timestamp.after(startDate) }
            .groupBy { it.emotionalState }
            .maxByOrNull { it.value.size }
            ?.key ?: "neutral"
    }
    
    /**
     * البحث في سجل التفاعلات
     */
    fun searchInteractionHistory(query: String): List<InteractionEntry> {
        return userProfile.interactionHistory
            .filter { 
                it.query.contains(query, ignoreCase = true) || 
                it.response.contains(query, ignoreCase = true) 
            }
    }
    
    /**
     * الحصول على سجل التفاعلات الأخيرة
     * @param limit عدد التفاعلات المطلوبة
     */
    fun getInteractionHistory(limit: Int = 10): List<InteractionEntry> {
        return userProfile.interactionHistory
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * الحصول على جميع التفضيلات
     */
    fun getAllPreferences(): Map<String, Any> {
        return userProfile.preferences.toMap()
    }
    
    /**
     * توليد تقرير موجز عن المستخدم
     */
    suspend fun generateUserSummary(): String = withContext(Dispatchers.Default) {
        val builder = StringBuilder()
        
        builder.append("ملخص الملف الشخصي لـ ${userProfile.name}:\n\n")
        
        // معلومات أساسية
        builder.append("- العمر: ${userProfile.age} سنة\n")
        builder.append("- الجنس: ${userProfile.gender}\n")
        
        // تفضيلات رئيسية
        val favoriteCategories = listOf("food", "entertainment", "apps", "topics")
        for (category in favoriteCategories) {
            val preferences = getPreferencesInCategory(category)
            if (preferences.isNotEmpty()) {
                builder.append("- تفضيلات ${translateCategory(category)}: ")
                builder.append(preferences.values.joinToString(", "))
                builder.append("\n")
            }
        }
        
        // أنماط النوم إذا كانت متوفرة
        val sleepData = userProfile.healthData.filter { it.type == "sleep" }.takeLast(7)
        if (sleepData.isNotEmpty()) {
            val avgSleep = sleepData.map { (it.value as? Double) ?: 0.0 }.average()
            builder.append("- متوسط النوم: ${String.format("%.1f", avgSleep)} ساعة يومياً\n")
        }
        
        // الحالة العاطفية السائدة
        val dominantEmotion = getDominantEmotionalState()
        builder.append("- الحالة العاطفية السائدة: ${translateEmotion(dominantEmotion)}\n")
        
        // التطبيقات الأكثر استخداماً
        val appUsage = analyzeBehavior("app_usage")
        if (appUsage.isNotEmpty()) {
            val topApps = appUsage.entries.sortedByDescending { it.value }.take(3)
            builder.append("- التطبيقات الأكثر استخداماً: ")
            builder.append(topApps.joinToString(", ") { it.key })
            builder.append("\n")
        }
        
        builder.toString()
    }
    
    /**
     * ترجمة فئات التفضيلات إلى العربية
     */
    private fun translateCategory(category: String): String {
        return when (category) {
            "food" -> "الطعام"
            "entertainment" -> "الترفيه"
            "apps" -> "التطبيقات"
            "topics" -> "المواضيع"
            "music" -> "الموسيقى"
            "travel" -> "السفر"
            "exercise" -> "الرياضة"
            else -> category
        }
    }
    
    /**
     * ترجمة الحالات العاطفية إلى العربية
     */
    private fun translateEmotion(emotion: String): String {
        return when (emotion) {
            "happy" -> "سعيد"
            "sad" -> "حزين"
            "angry" -> "غاضب"
            "fearful" -> "قلق"
            "surprised" -> "متفاجئ"
            "calm" -> "هادئ"
            "neutral" -> "محايد"
            else -> emotion
        }
    }
}

/**
 * فئة الملف الشخصي الرئيسية
 */
data class UserProfile(
    var name: String = "",
    var age: Int = 0,
    var gender: String = "",
    var creationDate: Date = Date(),
    var healthData: MutableList<HealthEntry> = mutableListOf(),
    var behaviorPatterns: MutableMap<String, MutableList<BehaviorEntry>> = mutableMapOf(),
    var preferences: MutableMap<String, Any> = mutableMapOf(),
    var interactionHistory: MutableList<InteractionEntry> = mutableListOf()
)

/**
 * بيانات صحية مسجلة
 */
data class HealthEntry(
    val type: String,  // مثلاً: sleep, weight, steps, mood, etc.
    val value: Any,    // قيمة القياس (عدد، نص، إلخ)
    val timestamp: Date,
    val notes: String = ""
)

/**
 * سجل سلوك المستخدم
 */
data class BehaviorEntry(
    val action: String,     // العمل الذي قام به المستخدم
    val context: String,    // سياق العمل 
    val timestamp: Date,
    val value: Any? = null  // بيانات إضافية
)

/**
 * سجل التفاعلات مع المستخدم
 */
data class InteractionEntry(
    val type: String,          // نوع التفاعل (محادثة، أمر صوتي، إلخ)
    val query: String,         // ما طلبه المستخدم
    val response: String,      // الرد الذي تم تقديمه
    val timestamp: Date,
    val emotionalState: String // الحالة العاطفية أثناء التفاعل
)