package com.intelliai.assistant

import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE
import android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

/**
 * مدير واجهة المستخدم التكيفية
 * يعدل واجهة المستخدم ديناميكيًا بناءً على:
 * - وقت اليوم (صباح، ظهر، مساء، ليل)
 * - حالة المستخدم العاطفية
 * - سياق الاستخدام (في المنزل، في العمل، في الطريق)
 * - تفضيلات المستخدم المستمدة من استخدامه
 * - إعدادات النظام والجهاز
 *
 * يستخدم واجهات نظام Android الداخلية فقط، وبالتالي مجاني بالكامل
 */
class AdaptiveUIManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AdaptiveUIManager"
        
        // أنواع الثيمات
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_AUTO = 2
        
        // مستويات الحجم
        const val TEXT_SIZE_SMALL = 0
        const val TEXT_SIZE_NORMAL = 1
        const val TEXT_SIZE_LARGE = 2
        const val TEXT_SIZE_EXTRA_LARGE = 3
        
        // حالات اليوم
        const val TIME_MORNING = 0    // 5:00 - 11:59
        const val TIME_AFTERNOON = 1  // 12:00 - 16:59
        const val TIME_EVENING = 2    // 17:00 - 20:59
        const val TIME_NIGHT = 3      // 21:00 - 4:59
        
        // سياق الاستخدام
        const val CONTEXT_HOME = 0
        const val CONTEXT_WORK = 1
        const val CONTEXT_TRAVELING = 2
    }
    
    // إعدادات حالية
    private var currentTheme = THEME_AUTO
    private var currentTextSize = TEXT_SIZE_NORMAL
    private var currentHighContrastMode = false
    private var currentVoiceSpeed = 1.0f
    private var currentDisplayMode = DisplayMode()
    
    // معلومات النظام والجهاز
    private val isPowerSaveMode: Boolean
        get() {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isPowerSaveMode
        }
    
    private val isNightMode: Boolean
        get() {
            return when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> true
                else -> false
            }
        }
    
    private val isAutoBrightnessEnabled: Boolean
        get() {
            try {
                return Settings.System.getInt(
                    context.contentResolver,
                    SCREEN_BRIGHTNESS_MODE
                ) == SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في الحصول على وضع السطوع: ${e.message}")
                return false
            }
        }
    
    /**
     * تهيئة المدير باستخدام إعدادات النظام الافتراضية
     */
    fun initialize() {
        Log.i(TAG, "تهيئة مدير واجهة المستخدم التكيفية")
        
        // ضبط الثيم بناءً على وضع النظام
        currentTheme = if (isNightMode) THEME_DARK else THEME_LIGHT
        
        // ضبط حجم النص بناءً على إعدادات إمكانية الوصول
        val fontScale = context.resources.configuration.fontScale
        currentTextSize = when {
            fontScale <= 0.85f -> TEXT_SIZE_SMALL
            fontScale <= 1.15f -> TEXT_SIZE_NORMAL
            fontScale <= 1.3f -> TEXT_SIZE_LARGE
            else -> TEXT_SIZE_EXTRA_LARGE
        }
        
        // التكيف مع وضع توفير الطاقة
        if (isPowerSaveMode) {
            currentDisplayMode.reducedAnimations = true
            currentDisplayMode.reducedEffects = true
        }
        
        // تحديث وضع العرض بناءً على وقت اليوم
        updateForTimeOfDay()
    }
    
    /**
     * الحصول على توصيات العرض الحالية
     *
     * @return توصيات العرض
     */
    fun getCurrentDisplayRecommendations(): DisplayMode {
        return currentDisplayMode
    }
    
    /**
     * تحديث وضع العرض بناءً على وقت اليوم
     */
    fun updateForTimeOfDay() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        
        val timeOfDay = when {
            hour in 5..11 -> TIME_MORNING
            hour in 12..16 -> TIME_AFTERNOON
            hour in 17..20 -> TIME_EVENING
            else -> TIME_NIGHT
        }
        
        currentDisplayMode.apply {
            when (timeOfDay) {
                TIME_MORNING -> {
                    colorTemperature = 6500 // ألوان طبيعية
                    brightness = 0.75f
                    fontWeight = "normal"
                }
                TIME_AFTERNOON -> {
                    colorTemperature = 6200 // قليلا أكثر دفئًا
                    brightness = 0.85f
                    fontWeight = "normal"
                }
                TIME_EVENING -> {
                    colorTemperature = 4000 // ألوان دافئة
                    brightness = 0.65f
                    fontWeight = "normal"
                }
                TIME_NIGHT -> {
                    colorTemperature = 3000 // ألوان دافئة جدًا
                    brightness = 0.45f
                    fontWeight = "normal"
                    reducedBrightness = true
                    preferDarkMode = true
                }
            }
        }
        
        // ضبط الوضع الليلي تلقائيًا في الليل
        if (timeOfDay == TIME_NIGHT && currentTheme == THEME_AUTO) {
            currentTheme = THEME_DARK
        }
    }
    
    /**
     * تحديث وضع العرض بناءً على الحالة العاطفية للمستخدم
     *
     * @param emotion الحالة العاطفية
     * @param intensity شدة العاطفة
     */
    fun updateForEmotion(emotion: String, intensity: Float) {
        if (intensity < 0.3f) {
            // إذا كانت شدة العاطفة منخفضة، لا داعي للتعديل
            return
        }
        
        currentDisplayMode.apply {
            when (emotion.toLowerCase()) {
                "happy", "سعيد" -> {
                    // ألوان مشرقة ومبهجة
                    mainColor = "#2196F3" // أزرق فاتح
                    accentColor = "#FFC107" // أصفر
                    fontWeight = "normal"
                    reducedAnimations = false
                }
                "sad", "حزين" -> {
                    // ألوان هادئة وناعمة
                    mainColor = "#607D8B" // رمادي مزرق
                    accentColor = "#4FC3F7" // أزرق فاتح
                    fontWeight = "normal"
                    reducedAnimations = true
                    reducedBrightness = true
                }
                "angry", "غاضب" -> {
                    // واجهة بسيطة وهادئة
                    mainColor = "#4CAF50" // أخضر مهدئ
                    accentColor = "#8BC34A" // أخضر ليموني
                    fontWeight = "normal"
                    reducedAnimations = true
                    simplifiedLayout = true
                }
                "fear", "خوف" -> {
                    // واجهة مستقرة وآمنة
                    mainColor = "#3F51B5" // أزرق داكن
                    accentColor = "#7986CB" // أزرق بنفسجي
                    fontWeight = "medium"
                    reducedAnimations = true
                    simplifiedLayout = true
                }
                "surprise", "مفاجأة" -> {
                    // ألوان مشرقة ومتباينة
                    mainColor = "#673AB7" // بنفسجي
                    accentColor = "#E040FB" // وردي
                    fontWeight = "normal"
                    reducedAnimations = false
                }
                "neutral", "محايد" -> {
                    // الوضع الافتراضي
                    resetColorSettings()
                    fontWeight = "normal"
                }
                else -> {
                    // لم يتم التعرف على العاطفة، استخدام الإعدادات الافتراضية
                    resetColorSettings()
                }
            }
        }
    }
    
    /**
     * تحديث وضع العرض بناءً على سياق الاستخدام
     *
     * @param context سياق الاستخدام
     */
    fun updateForContext(context: Int) {
        currentDisplayMode.apply {
            when (context) {
                CONTEXT_HOME -> {
                    // واجهة مريحة ومناسبة للمنزل
                    layoutDensity = "comfortable"
                    contentPreference = "personalized"
                    simplifiedLayout = false
                }
                CONTEXT_WORK -> {
                    // واجهة عملية وفعالة
                    layoutDensity = "compact"
                    contentPreference = "focused"
                    simplifiedLayout = true
                    reducedAnimations = true
                }
                CONTEXT_TRAVELING -> {
                    // واجهة سهلة القراءة والاستخدام أثناء التنقل
                    layoutDensity = "comfortable"
                    fontWeight = "medium"
                    contentPreference = "essential"
                    simplifiedLayout = true
                    brightness = 0.85f // زيادة السطوع للرؤية في الخارج
                }
            }
        }
    }
    
    /**
     * ضبط حجم النص
     *
     * @param textSize حجم النص
     */
    fun setTextSize(textSize: Int) {
        currentTextSize = textSize
        currentDisplayMode.apply {
            this.textSize = when (textSize) {
                TEXT_SIZE_SMALL -> 0.85f
                TEXT_SIZE_NORMAL -> 1.0f
                TEXT_SIZE_LARGE -> 1.2f
                TEXT_SIZE_EXTRA_LARGE -> 1.4f
                else -> 1.0f
            }
        }
    }
    
    /**
     * ضبط ثيم التطبيق
     *
     * @param theme الثيم
     */
    fun setTheme(theme: Int) {
        currentTheme = theme
        currentDisplayMode.apply {
            when (theme) {
                THEME_LIGHT -> {
                    preferDarkMode = false
                    backgroundColor = "#FFFFFF"
                    textColor = "#212121"
                }
                THEME_DARK -> {
                    preferDarkMode = true
                    backgroundColor = "#121212"
                    textColor = "#EEEEEE"
                }
                THEME_AUTO -> {
                    // يعتمد على الوقت والإعدادات
                    updateForTimeOfDay()
                }
            }
        }
    }
    
    /**
     * تفعيل/تعطيل وضع التباين العالي
     *
     * @param enabled حالة التفعيل
     */
    fun setHighContrastMode(enabled: Boolean) {
        currentHighContrastMode = enabled
        if (enabled) {
            currentDisplayMode.apply {
                textColor = "#FFFFFF"
                backgroundColor = "#000000"
                mainColor = "#FFFFFF"
                accentColor = "#FFEB3B" // أصفر فاقع
                fontSize = 1.1f
                fontWeight = "bold"
            }
        } else {
            // استعادة الإعدادات السابقة
            setTheme(currentTheme)
            setTextSize(currentTextSize)
        }
    }
    
    /**
     * ضبط سرعة الصوت للمستخدم
     *
     * @param speed سرعة الصوت
     */
    fun setVoiceSpeed(speed: Float) {
        currentVoiceSpeed = speed
    }
    
    /**
     * الحصول على سرعة الصوت الحالية
     *
     * @return سرعة الصوت
     */
    fun getVoiceSpeed(): Float {
        return currentVoiceSpeed
    }
    
    /**
     * التكيف مع تفضيلات المستخدم
     *
     * @param preferences تفضيلات المستخدم
     */
    fun adaptToUserPreferences(preferences: UserPreferences) {
        // تطبيق تفضيلات المستخدم
        setTheme(preferences.theme)
        setTextSize(preferences.textSize)
        setHighContrastMode(preferences.highContrastMode)
        setVoiceSpeed(preferences.voiceSpeed)
        
        currentDisplayMode.apply {
            if (preferences.customColors) {
                mainColor = preferences.mainColor
                accentColor = preferences.accentColor
            }
            reducedAnimations = preferences.reducedAnimations
            reducedEffects = preferences.reducedEffects
            layoutDensity = preferences.layoutDensity
        }
    }
    
    /**
     * استرجاع إعدادات الألوان الافتراضية
     */
    private fun resetColorSettings() {
        currentDisplayMode.apply {
            // ألوان قياسية
            if (preferDarkMode) {
                backgroundColor = "#121212"
                textColor = "#EEEEEE"
                mainColor = "#BB86FC" // بنفسجي فاتح
                accentColor = "#03DAC5" // فيروزي
            } else {
                backgroundColor = "#FFFFFF"
                textColor = "#212121"
                mainColor = "#6200EE" // بنفسجي غامق
                accentColor = "#03DAC6" // فيروزي
            }
        }
    }
    
    /**
     * استنتاج سياق الاستخدام الحالي
     *
     * @return سياق الاستخدام
     */
    fun inferCurrentContext(): Int {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        
        // استنتاج بسيط بناءً على الوقت واليوم
        return when {
            // ساعات العمل التقليدية في أيام الأسبوع
            dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY && hour in 9..17 -> CONTEXT_WORK
            
            // عطلة نهاية الأسبوع أو المساء
            dayOfWeek in arrayOf(Calendar.SATURDAY, Calendar.SUNDAY) || hour !in 9..17 -> CONTEXT_HOME
            
            // افتراضي
            else -> CONTEXT_HOME
        }
    }
    
    /**
     * الكشف عن تطبيقات القراءة المثبتة
     *
     * @return قائمة بتطبيقات القراءة المثبتة
     */
    fun detectReadingApps(): List<ApplicationInfo> {
        val readingAppKeywords = listOf(
            "reader", "book", "قارئ", "كتاب", "قراءة",
            "kindle", "epub", "pdf", "kobo"
        )
        
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        
        return installedApps.filter { appInfo ->
            val appName = packageManager.getApplicationLabel(appInfo).toString().toLowerCase()
            val packageName = appInfo.packageName.toLowerCase()
            
            readingAppKeywords.any { keyword ->
                appName.contains(keyword) || packageName.contains(keyword)
            }
        }
    }
    
    /**
     * اقتراح تعديلات على الواجهة بناءً على عادات القراءة
     *
     * @return وضع عرض معدل للقراءة
     */
    fun suggestReadingModeAdjustments(): DisplayMode {
        val readingMode = DisplayMode()
        
        // نسخ الإعدادات الحالية
        readingMode.copyFrom(currentDisplayMode)
        
        // تعديلات خاصة بالقراءة
        readingMode.apply {
            colorTemperature = 2700 // ألوان دافئة للقراءة
            brightness = 0.6f // سطوع منخفض للراحة
            textSize = 1.1f // حجم نص أكبر قليلاً
            lineSpacing = 1.5f // تباعد أسطر مريح
            letterSpacing = 0.05f // تباعد حروف طفيف
            layoutDensity = "comfortable" // تخطيط مريح
            reducedAnimations = true // تقليل الحركة
            fontFamily = "serif" // خط مناسب للقراءة
            fontWeight = "normal"
            textAlignment = "justified" // محاذاة النص
        }
        
        return readingMode
    }
}

/**
 * نموذج توصيات العرض
 */
class DisplayMode {
    var mainColor: String = "#6200EE" // اللون الرئيسي
    var accentColor: String = "#03DAC6" // لون التأكيد
    var backgroundColor: String = "#FFFFFF" // لون الخلفية
    var textColor: String = "#212121" // لون النص
    
    var textSize: Float = 1.0f // حجم النص (1.0 = عادي)
    var fontFamily: String = "sans-serif" // عائلة الخط
    var fontWeight: String = "normal" // وزن الخط
    var lineSpacing: Float = 1.0f // تباعد الأسطر
    var letterSpacing: Float = 0.0f // تباعد الحروف
    var textAlignment: String = "start" // محاذاة النص
    
    var brightness: Float = 0.75f // مستوى السطوع (0.0-1.0)
    var colorTemperature: Int = 6500 // درجة حرارة اللون (كلفن)
    
    var reducedAnimations: Boolean = false // تقليل الرسوم المتحركة
    var reducedEffects: Boolean = false // تقليل المؤثرات
    var reducedBrightness: Boolean = false // تقليل السطوع
    var preferDarkMode: Boolean = false // تفضيل الوضع الداكن
    var layoutDensity: String = "normal" // كثافة التخطيط
    var simplifiedLayout: Boolean = false // تبسيط التخطيط
    var contentPreference: String = "balanced" // تفضيلات المحتوى
    
    /**
     * نسخ إعدادات العرض من كائن آخر
     *
     * @param other كائن DisplayMode آخر
     */
    fun copyFrom(other: DisplayMode) {
        mainColor = other.mainColor
        accentColor = other.accentColor
        backgroundColor = other.backgroundColor
        textColor = other.textColor
        
        textSize = other.textSize
        fontFamily = other.fontFamily
        fontWeight = other.fontWeight
        lineSpacing = other.lineSpacing
        letterSpacing = other.letterSpacing
        textAlignment = other.textAlignment
        
        brightness = other.brightness
        colorTemperature = other.colorTemperature
        
        reducedAnimations = other.reducedAnimations
        reducedEffects = other.reducedEffects
        reducedBrightness = other.reducedBrightness
        preferDarkMode = other.preferDarkMode
        layoutDensity = other.layoutDensity
        simplifiedLayout = other.simplifiedLayout
        contentPreference = other.contentPreference
    }
}

/**
 * نموذج تفضيلات المستخدم
 */
data class UserPreferences(
    val theme: Int = AdaptiveUIManager.THEME_AUTO,
    val textSize: Int = AdaptiveUIManager.TEXT_SIZE_NORMAL,
    val highContrastMode: Boolean = false,
    val voiceSpeed: Float = 1.0f,
    val customColors: Boolean = false,
    val mainColor: String = "#6200EE",
    val accentColor: String = "#03DAC6",
    val reducedAnimations: Boolean = false,
    val reducedEffects: Boolean = false,
    val layoutDensity: String = "normal"
)