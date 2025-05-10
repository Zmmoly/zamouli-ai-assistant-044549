package com.example.aiassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min

/**
 * محرك التوصيات الذكي
 * يعتمد على تحليل سلوك المستخدم وتفضيلاته وحالته لتقديم توصيات مخصصة
 * يعمل بنظام التعلم المستمر حيث يتحسن مع كل تفاعل
 */
class RecommendationEngine(
    private val context: Context,
    private val userProfileManager: UserProfileManager,
    private val behaviorAnalyzer: BehaviorAnalyzer,
    private val healthTracker: HealthTracker
) {
    companion object {
        private const val TAG = "RecommendationEngine"
        
        // المجالات المختلفة للتوصيات
        private val DOMAINS = listOf(
            "health", "productivity", "entertainment", "learning", "social", "news", "finance"
        )
        
        // أوزان القواعد الأولية - ستتعدل مع التعلم
        private val INITIAL_RULE_WEIGHTS = mapOf(
            "time_of_day" to 1.0f,
            "emotional_state" to 1.2f,
            "sleep_quality" to 0.8f,
            "activity_level" to 0.7f,
            "usage_pattern" to 1.0f,
            "direct_interests" to 1.5f,
            "search_history" to 0.9f,
            "app_preferences" to 1.1f
        )
    }
    
    // أوزان القواعد الحالية - سيتم تحديثها بناءً على التفاعل
    private val ruleWeights = HashMap<String, Float>().apply {
        putAll(INITIAL_RULE_WEIGHTS)
    }
    
    // معامل التعلم - كم يؤثر كل تفاعل على الأوزان
    private var learningRate: Float = 0.05f
    
    // سجل التوصيات السابقة وتفاعلات المستخدم معها
    private val recommendationHistory = HashMap<String, MutableList<RecommendationFeedback>>()
    
    /**
     * توليد توصية ذكية بناءً على السياق الحالي وتاريخ المستخدم
     */
    suspend fun generateRecommendation(
        context: RecommendationContext
    ): List<Recommendation> = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Generating recommendations for context: $context")
            
            // 1. جمع البيانات اللازمة للتوصية
            val userData = gatherUserData()
            
            // 2. تحديد نوع التوصية المناسب بناءً على السياق
            val domainScores = calculateDomainScores(context, userData)
            
            // 3. اختيار المجالات الأعلى تصنيفًا
            val topDomains = domainScores.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            Log.d(TAG, "Top recommendation domains: $topDomains")
            
            // 4. توليد توصيات محددة لكل مجال
            val recommendations = mutableListOf<Recommendation>()
            
            for (domain in topDomains) {
                val domainRecommendations = generateDomainRecommendations(domain, userData, context)
                recommendations.addAll(domainRecommendations)
            }
            
            // 5. تصفية وترتيب التوصيات النهائية
            return@withContext recommendations
                .distinctBy { it.title }
                .sortedByDescending { it.relevanceScore }
                .take(5)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating recommendations", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * جمع البيانات الحالية عن المستخدم
     */
    private suspend fun gatherUserData(): UserData = withContext(Dispatchers.Default) {
        val userData = UserData()
        
        try {
            // الحالة العاطفية الحالية
            userData.emotionalState = userProfileManager.getDominantEmotionalState(1)
            
            // تحليل أنماط النوم
            val sleepData = userProfileManager.analyzeHealthTrend("sleep", 7)
            if (sleepData.isNotEmpty()) {
                userData.averageSleepHours = sleepData.sumByDouble { (it.second as? Double) ?: 0.0 } / sleepData.size
            }
            
            // تحليل مستوى النشاط
            val activityData = userProfileManager.analyzeHealthTrend("physical_activity", 7)
            if (activityData.isNotEmpty()) {
                userData.averageActivityMinutes = activityData.sumByDouble { (it.second as? Double) ?: 0.0 } / activityData.size
            }
            
            // تحليل اهتمامات المستخدم
            val interests = behaviorAnalyzer.analyzeInterests()
            userData.interests = interests.map { it.keyword }
            
            // تحليل استخدام التطبيقات
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val appUsage = behaviorAnalyzer.analyzeAppUsage(7)
                userData.topApps = appUsage.entries
                    .sortedByDescending { it.value.totalTimeMs }
                    .take(10)
                    .map { it.key }
            }
            
            // تحليل سجل البحث والتفاعلات
            val recentInteractions = userProfileManager.getInteractionHistory(50)
            userData.searchQueries = recentInteractions
                .filter { it.type == "search" || it.type == "question" }
                .map { it.query }
            
            // تحليل التفضيلات
            userData.preferences = userProfileManager.getAllPreferences()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error gathering user data", e)
        }
        
        return@withContext userData
    }
    
    /**
     * حساب نتائج المجالات المختلفة بناءً على بيانات المستخدم والسياق
     */
    private fun calculateDomainScores(
        context: RecommendationContext,
        userData: UserData
    ): Map<String, Float> {
        val scores = HashMap<String, Float>()
        
        // تهيئة جميع المجالات بقيم ابتدائية
        DOMAINS.forEach { domain ->
            scores[domain] = 0f
        }
        
        try {
            // 1. توصيات صحية - أعلى في الصباح وبناءً على أنماط النوم والنشاط
            scores["health"] = calculateHealthScore(context, userData)
            
            // 2. توصيات الإنتاجية - أعلى أثناء ساعات العمل وعندما يكون المستخدم منتبهًا
            scores["productivity"] = calculateProductivityScore(context, userData)
            
            // 3. توصيات الترفيه - أعلى في المساء ونهاية الأسبوع
            scores["entertainment"] = calculateEntertainmentScore(context, userData)
            
            // 4. توصيات التعلم - بناءً على اهتمامات البحث وتفضيلات المحتوى
            scores["learning"] = calculateLearningScore(context, userData)
            
            // 5. توصيات اجتماعية - أعلى في المساء والعطلات
            scores["social"] = calculateSocialScore(context, userData)
            
            // 6. توصيات الأخبار - أعلى في الصباح وبناءً على الاهتمامات
            scores["news"] = calculateNewsScore(context, userData)
            
            // 7. توصيات مالية - بناءً على الاهتمامات والسلوك
            scores["finance"] = calculateFinanceScore(context, userData)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating domain scores", e)
        }
        
        return scores
    }
    
    /**
     * حساب نتيجة توصيات الصحة
     */
    private fun calculateHealthScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // صباحًا، زيادة أهمية التوصيات الصحية
        val hourOfDay = context.timeOfDay.hour
        if (hourOfDay in 6..10) {
            score += 0.3f * ruleWeights["time_of_day"]!!
        }
        
        // إذا كان النوم سيئًا، زيادة التوصيات الصحية
        if (userData.averageSleepHours < 7.0) {
            score += (0.4f * ruleWeights["sleep_quality"]!!) * ((7.0 - userData.averageSleepHours) / 2.0).toFloat()
        }
        
        // إذا كان مستوى النشاط منخفضًا
        if (userData.averageActivityMinutes < 30.0) {
            score += (0.3f * ruleWeights["activity_level"]!!) * ((30.0 - userData.averageActivityMinutes) / 15.0).toFloat()
        }
        
        // على أساس الحالة العاطفية
        if (userData.emotionalState in listOf("stressed", "angry", "fearful", "sad")) {
            score += 0.2f * ruleWeights["emotional_state"]!!
        }
        
        // اهتمامات متعلقة بالصحة
        val healthKeywords = listOf("صحة", "تمارين", "رياضة", "غذاء", "نوم", "طب", "لياقة")
        val interestScore = userData.interests.count { interest -> 
            healthKeywords.any { it in interest }
        } * 0.1f
        
        score += interestScore * ruleWeights["direct_interests"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة توصيات الإنتاجية
     */
    private fun calculateProductivityScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // خلال ساعات العمل
        val hourOfDay = context.timeOfDay.hour
        val isWeekend = context.timeOfDay.dayOfWeek in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        
        if (hourOfDay in 9..17 && !isWeekend) {
            score += 0.4f * ruleWeights["time_of_day"]!!
        }
        
        // حالة الانتباه والتركيز
        if (userData.emotionalState in listOf("neutral", "calm")) {
            score += 0.3f * ruleWeights["emotional_state"]!!
        }
        
        // إذا كان النوم جيدًا، زيادة توصيات الإنتاجية
        if (userData.averageSleepHours >= 7.0) {
            score += 0.2f * ruleWeights["sleep_quality"]!!
        }
        
        // استخدام تطبيقات الإنتاجية
        val productivityApps = listOf(
            "com.microsoft.office", "com.google.android.gm", "com.google.android.apps.docs",
            "com.google.android.calendar", "com.todoist", "com.any.do", "com.evernote"
        )
        
        val appScore = userData.topApps.count { app -> 
            productivityApps.any { it in app }
        } * 0.15f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة توصيات الترفيه
     */
    private fun calculateEntertainmentScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // في المساء ونهاية الأسبوع
        val hourOfDay = context.timeOfDay.hour
        val isWeekend = context.timeOfDay.dayOfWeek in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        
        if (hourOfDay >= 18 || isWeekend) {
            score += 0.4f * ruleWeights["time_of_day"]!!
        }
        
        // إذا كان مستوى النشاط منخفضًا خلال اليوم
        if (userData.averageActivityMinutes > 30) {
            score += 0.2f * ruleWeights["activity_level"]!!
        }
        
        // أنماط استخدام التطبيقات الترفيهية
        val entertainmentApps = listOf(
            "com.netflix", "com.spotify", "com.google.android.youtube", 
            "com.instagram.android", "com.facebook.katana", "tv.twitch",
            "com.amazon.avod", "com.google.android.play.games"
        )
        
        val appScore = userData.topApps.count { app -> 
            entertainmentApps.any { it in app }
        } * 0.15f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        // الحالة العاطفية
        if (userData.emotionalState in listOf("happy", "calm")) {
            score += 0.2f * ruleWeights["emotional_state"]!!
        } else if (userData.emotionalState in listOf("sad", "stressed")) {
            score += 0.3f * ruleWeights["emotional_state"]!! // ممكن أن يحتاج لرفع المعنويات
        }
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة توصيات التعلم
     */
    private fun calculateLearningScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // وقت مناسب للتعلم - ساعات الهدوء
        val hourOfDay = context.timeOfDay.hour
        if (hourOfDay in 9..12 || hourOfDay in 15..21) {
            score += 0.3f * ruleWeights["time_of_day"]!!
        }
        
        // حالة ذهنية مناسبة للتعلم
        if (userData.emotionalState in listOf("neutral", "calm", "happy", "surprised")) {
            score += 0.2f * ruleWeights["emotional_state"]!!
        }
        
        // استنادًا إلى سجل البحث
        val educationalKeywords = listOf(
            "كيف", "تعلم", "دورة", "شرح", "دليل", "tutori", "course", "learn", "how to"
        )
        
        val searchScore = userData.searchQueries.count { query -> 
            educationalKeywords.any { it in query.toLowerCase() }
        } * 0.1f
        
        score += searchScore * ruleWeights["search_history"]!!
        
        // استخدام تطبيقات التعلم
        val learningApps = listOf(
            "com.duolingo", "org.khanacademy", "com.udemy", "com.coursera",
            "com.sololearn", "com.google.android.apps.youtube.kids"
        )
        
        val appScore = userData.topApps.count { app -> 
            learningApps.any { it in app }
        } * 0.2f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة التوصيات الاجتماعية
     */
    private fun calculateSocialScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // وقت مناسب للتفاعل الاجتماعي
        val hourOfDay = context.timeOfDay.hour
        val isWeekend = context.timeOfDay.dayOfWeek in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        
        if (hourOfDay >= 17 || isWeekend) {
            score += 0.4f * ruleWeights["time_of_day"]!!
        }
        
        // حالة عاطفية مناسبة للتفاعل
        if (userData.emotionalState in listOf("happy", "neutral")) {
            score += 0.3f * ruleWeights["emotional_state"]!!
        } else if (userData.emotionalState in listOf("sad", "lonely")) {
            score += 0.4f * ruleWeights["emotional_state"]!! // قد يحتاج للتفاعل الاجتماعي أكثر
        }
        
        // استخدام تطبيقات اجتماعية
        val socialApps = listOf(
            "com.whatsapp", "com.facebook.katana", "com.instagram.android",
            "com.twitter.android", "com.snapchat.android", "com.linkedin.android"
        )
        
        val appScore = userData.topApps.count { app -> 
            socialApps.any { it in app }
        } * 0.2f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة توصيات الأخبار
     */
    private fun calculateNewsScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // صباحًا ومساءً، زيادة أهمية الأخبار
        val hourOfDay = context.timeOfDay.hour
        if (hourOfDay in 6..9 || hourOfDay in 18..22) {
            score += 0.3f * ruleWeights["time_of_day"]!!
        }
        
        // البحث عن كلمات متعلقة بالأخبار
        val newsKeywords = listOf(
            "أخبار", "عاجل", "حدث", "تقرير", "news", "breaking", "report"
        )
        
        val searchScore = userData.searchQueries.count { query -> 
            newsKeywords.any { it in query.toLowerCase() }
        } * 0.1f
        
        score += searchScore * ruleWeights["search_history"]!!
        
        // استخدام تطبيقات الأخبار
        val newsApps = listOf(
            "com.google.android.apps.magazines", "com.aljazeera.news",
            "bbc.mobile.news", "com.cnn.mobile.android", "com.arabnews"
        )
        
        val appScore = userData.topApps.count { app -> 
            newsApps.any { it in app }
        } * 0.2f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * حساب نتيجة التوصيات المالية
     */
    private fun calculateFinanceScore(context: RecommendationContext, userData: UserData): Float {
        var score = 0f
        
        // في أوقات العمل، زيادة أهمية التوصيات المالية
        val hourOfDay = context.timeOfDay.hour
        val isWeekend = context.timeOfDay.dayOfWeek in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        
        if (hourOfDay in 9..17 && !isWeekend) {
            score += 0.2f * ruleWeights["time_of_day"]!!
        }
        
        // الحالة العاطفية المناسبة للقرارات المالية
        if (userData.emotionalState in listOf("neutral", "calm")) {
            score += 0.3f * ruleWeights["emotional_state"]!!
        }
        
        // البحث عن مواضيع مالية
        val financeKeywords = listOf(
            "مال", "استثمار", "سوق", "بنك", "اقتصاد", "finance", "investment", "stock", "bank"
        )
        
        val searchScore = userData.searchQueries.count { query -> 
            financeKeywords.any { it in query.toLowerCase() }
        } * 0.15f
        
        score += searchScore * ruleWeights["search_history"]!!
        
        // استخدام تطبيقات مالية
        val financeApps = listOf(
            "com.paypal", "org.bankingapp", "com.binance", "com.coinbase", 
            "com.robinhood", "com.stc.stcpay", "sa.alrajhibank", "com.tadawul"
        )
        
        val appScore = userData.topApps.count { app -> 
            financeApps.any { it in app }
        } * 0.2f
        
        score += appScore * ruleWeights["app_preferences"]!!
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * توليد توصيات محددة لمجال معين
     */
    private suspend fun generateDomainRecommendations(
        domain: String,
        userData: UserData,
        context: RecommendationContext
    ): List<Recommendation> = withContext(Dispatchers.Default) {
        val recommendations = mutableListOf<Recommendation>()
        
        try {
            when (domain) {
                "health" -> {
                    // توصيات صحية مخصصة
                    if (userData.averageSleepHours < 7.0) {
                        recommendations.add(
                            Recommendation(
                                title = "تحسين جودة النوم",
                                description = "حاول النوم مبكرًا والحصول على 7-9 ساعات يوميًا لتحسين صحتك العامة",
                                domain = domain,
                                type = "advice",
                                relevanceScore = 0.9f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                    
                    if (userData.averageActivityMinutes < 30.0) {
                        recommendations.add(
                            Recommendation(
                                title = "زيادة النشاط البدني",
                                description = "حاول المشي لمدة 30 دقيقة يوميًا. المشي البسيط يمكن أن يحسن من صحتك العامة بشكل كبير",
                                domain = domain,
                                type = "advice",
                                relevanceScore = 0.85f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                    
                    // توصية بشرب الماء
                    val hourOfDay = context.timeOfDay.hour
                    if (hourOfDay in 9..20 && hourOfDay % 2 == 0) {
                        recommendations.add(
                            Recommendation(
                                title = "تذكير بشرب الماء",
                                description = "حاول شرب كوب من الماء الآن. الترطيب المنتظم مهم لصحتك ونشاطك",
                                domain = domain,
                                type = "reminder",
                                relevanceScore = 0.7f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                    
                    // توصية تمارين التنفس عند الضغط
                    if (userData.emotionalState in listOf("stressed", "angry", "fearful")) {
                        recommendations.add(
                            Recommendation(
                                title = "تمارين تنفس للاسترخاء",
                                description = "خذ 5 دقائق لتمارين التنفس العميق. ستساعدك على الاسترخاء وتخفيف التوتر",
                                domain = domain,
                                type = "activity",
                                relevanceScore = 0.8f,
                                actionUrl = "breathe://exercise",
                                actionLabel = "بدء التمرين"
                            )
                        )
                    }
                }
                
                "productivity" -> {
                    // توصيات الإنتاجية المخصصة
                    val hourOfDay = context.timeOfDay.hour
                    
                    if (hourOfDay in 9..11) {
                        recommendations.add(
                            Recommendation(
                                title = "تخطيط مهام اليوم",
                                description = "هذا وقت مثالي لتخطيط مهامك لليوم. تحديد الأولويات يساعد على زيادة الإنتاجية",
                                domain = domain,
                                type = "activity",
                                relevanceScore = 0.9f,
                                actionUrl = "tasks://new",
                                actionLabel = "إضافة مهمة"
                            )
                        )
                    }
                    
                    if (hourOfDay in 14..16) {
                        recommendations.add(
                            Recommendation(
                                title = "استراحة قصيرة للتركيز",
                                description = "خذ استراحة 5 دقائق لتجديد نشاطك الذهني. الاستراحات القصيرة تحسن الإنتاجية",
                                domain = domain,
                                type = "reminder",
                                relevanceScore = 0.8f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                    
                    // مراجعة المهام
                    if (hourOfDay >= 17) {
                        recommendations.add(
                            Recommendation(
                                title = "مراجعة إنجازات اليوم",
                                description = "خذ لحظة لمراجعة ما أنجزته اليوم، وتحديد الأولويات لغدٍ",
                                domain = domain,
                                type = "activity",
                                relevanceScore = 0.75f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                }
                
                "entertainment" -> {
                    // توصيات الترفيه المخصصة
                    if (userData.emotionalState in listOf("sad", "stressed", "tired")) {
                        recommendations.add(
                            Recommendation(
                                title = "محتوى مرح لتحسين المزاج",
                                description = "شاهد مقاطع فيديو مضحكة على يوتيوب لتحسين مزاجك",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.9f,
                                actionUrl = "youtube://search?q=funny+videos",
                                actionLabel = "مشاهدة الآن"
                            )
                        )
                    }
                    
                    // توصية بناءً على التفضيلات
                    val entertainmentPrefs = userData.preferences
                        .filter { it.key.startsWith("entertainment:") }
                    
                    if (entertainmentPrefs.isNotEmpty() && context.timeOfDay.hour >= 18) {
                        val pref = entertainmentPrefs.entries.random()
                        
                        recommendations.add(
                            Recommendation(
                                title = "محتوى ترفيهي مخصص",
                                description = "استكشف المزيد من المحتوى المشابه لـ ${pref.value}",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.8f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                }
                
                "learning" -> {
                    // توصيات التعلم المخصصة
                    val isGoodTimeForLearning = context.timeOfDay.hour in 9..11 || 
                                                context.timeOfDay.hour in 15..21
                    
                    if (isGoodTimeForLearning && userData.emotionalState in listOf("neutral", "calm", "happy")) {
                        recommendations.add(
                            Recommendation(
                                title = "وقت مثالي للتعلم",
                                description = "هذا وقت مناسب لك لتعلم مهارة جديدة أو متابعة دورة تعليمية",
                                domain = domain,
                                type = "advice",
                                relevanceScore = 0.85f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                    
                    // اقتراح محتوى تعليمي بناءً على الاهتمامات
                    if (userData.interests.isNotEmpty()) {
                        val interest = userData.interests.random()
                        
                        recommendations.add(
                            Recommendation(
                                title = "اكتشف المزيد عن $interest",
                                description = "ابحث عن مقالات أو فيديوهات تعليمية عن هذا الموضوع لتوسيع معرفتك",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.8f,
                                actionUrl = "search://$interest tutorial",
                                actionLabel = "تعلم الآن"
                            )
                        )
                    }
                }
                
                "social" -> {
                    // توصيات اجتماعية مخصصة
                    val isWeekend = context.timeOfDay.dayOfWeek in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
                    
                    if (isWeekend || context.timeOfDay.hour >= 17) {
                        recommendations.add(
                            Recommendation(
                                title = "التواصل مع الأصدقاء والعائلة",
                                description = "هذا وقت جيد للتواصل مع أحد أفراد العائلة أو الأصدقاء الذين لم تتحدث معهم منذ فترة",
                                domain = domain,
                                type = "activity",
                                relevanceScore = 0.85f,
                                actionUrl = "contacts://favorites",
                                actionLabel = "فتح جهات الاتصال"
                            )
                        )
                    }
                    
                    if (userData.emotionalState in listOf("sad", "lonely")) {
                        recommendations.add(
                            Recommendation(
                                title = "المشاركة الاجتماعية",
                                description = "التواصل مع الآخرين يمكن أن يحسن مزاجك. تواصل مع صديق أو فرد من العائلة",
                                domain = domain,
                                type = "advice",
                                relevanceScore = 0.9f,
                                actionUrl = null,
                                actionLabel = null
                            )
                        )
                    }
                }
                
                "news" -> {
                    // توصيات الأخبار المخصصة
                    val morningTime = context.timeOfDay.hour in 6..10
                    val eveningTime = context.timeOfDay.hour in 18..22
                    
                    if (morningTime) {
                        recommendations.add(
                            Recommendation(
                                title = "ملخص أخبار الصباح",
                                description = "اطلع على أهم أخبار اليوم في هذا الصباح",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.9f,
                                actionUrl = "news://morning",
                                actionLabel = "قراءة الأخبار"
                            )
                        )
                    } else if (eveningTime) {
                        recommendations.add(
                            Recommendation(
                                title = "أخبار المساء",
                                description = "اطلع على ملخص لأهم أحداث اليوم",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.85f,
                                actionUrl = "news://daily",
                                actionLabel = "قراءة الأخبار"
                            )
                        )
                    }
                    
                    // أخبار مخصصة بناءً على الاهتمامات
                    if (userData.interests.isNotEmpty()) {
                        val interest = userData.interests.random()
                        
                        recommendations.add(
                            Recommendation(
                                title = "أخبار مخصصة: $interest",
                                description = "اطلع على آخر الأخبار المتعلقة باهتماماتك",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.8f,
                                actionUrl = "news://topic/$interest",
                                actionLabel = "قراءة الأخبار"
                            )
                        )
                    }
                }
                
                "finance" -> {
                    // توصيات مالية مخصصة
                    val weekday = context.timeOfDay.dayOfWeek !in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
                    
                    if (weekday && context.timeOfDay.hour in 9..15) {
                        recommendations.add(
                            Recommendation(
                                title = "متابعة الأسواق المالية",
                                description = "تحقق من آخر تحركات السوق في هذا الوقت المناسب",
                                domain = domain,
                                type = "content",
                                relevanceScore = 0.85f,
                                actionUrl = "finance://markets",
                                actionLabel = "عرض الأسواق"
                            )
                        )
                    }
                    
                    // نصائح مالية
                    recommendations.add(
                        Recommendation(
                            title = "نصائح لتوفير المال",
                            description = "تعرف على طرق بسيطة للتوفير في المصاريف اليومية",
                            domain = domain,
                            type = "advice",
                            relevanceScore = 0.75f,
                            actionUrl = null,
                            actionLabel = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating recommendations for domain: $domain", e)
        }
        
        return@withContext recommendations
    }
    
    /**
     * تسجيل ردود الفعل على التوصيات لتحسين النظام
     */
    fun recordRecommendationFeedback(recommendation: Recommendation, interactionType: InteractionType) {
        try {
            Log.d(TAG, "Recording feedback for recommendation: ${recommendation.title}, interaction: $interactionType")
            
            val feedback = RecommendationFeedback(
                recommendation = recommendation,
                interactionType = interactionType,
                timestamp = Date()
            )
            
            if (!recommendationHistory.containsKey(recommendation.domain)) {
                recommendationHistory[recommendation.domain] = mutableListOf()
            }
            
            recommendationHistory[recommendation.domain]?.add(feedback)
            
            // تعديل الأوزان بناءً على التفاعل
            adjustWeightsBasedOnFeedback(recommendation, interactionType)
            
            // تخزين التفضيل في ملف المستخدم إذا كان التفاعل إيجابيًا
            if (interactionType == InteractionType.CLICKED || interactionType == InteractionType.POSITIVE_FEEDBACK) {
                userProfileManager.setPreference(
                    category = recommendation.domain,
                    name = "recommendation_${recommendation.type}",
                    value = recommendation.title
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording recommendation feedback", e)
        }
    }
    
    /**
     * تعديل أوزان القواعد بناءً على التفاعل مع التوصيات
     */
    private fun adjustWeightsBasedOnFeedback(recommendation: Recommendation, interaction: InteractionType) {
        try {
            // تحديد قيمة التعديل
            val adjustment = when (interaction) {
                InteractionType.CLICKED -> 0.05f
                InteractionType.POSITIVE_FEEDBACK -> 0.1f
                InteractionType.DISMISSED -> -0.05f
                InteractionType.NEGATIVE_FEEDBACK -> -0.1f
                InteractionType.VIEWED_ONLY -> 0.01f // تعديل طفيف للمشاهدة فقط
            }
            
            // تحديد القواعد ذات الصلة بناءً على نوع التوصية
            val relevantRules = when (recommendation.domain) {
                "health" -> listOf("time_of_day", "emotional_state", "sleep_quality", "activity_level")
                "productivity" -> listOf("time_of_day", "emotional_state", "sleep_quality")
                "entertainment" -> listOf("time_of_day", "emotional_state", "app_preferences")
                "learning" -> listOf("time_of_day", "search_history", "direct_interests")
                "social" -> listOf("time_of_day", "emotional_state")
                "news" -> listOf("time_of_day", "search_history")
                "finance" -> listOf("time_of_day", "direct_interests")
                else -> listOf("time_of_day")
            }
            
            // تطبيق التعديل على القواعد ذات الصلة
            for (rule in relevantRules) {
                val currentWeight = ruleWeights[rule] ?: 1.0f
                val newWeight = (currentWeight + (adjustment * learningRate)).coerceIn(0.1f, 2.0f)
                ruleWeights[rule] = newWeight
                
                Log.d(TAG, "Adjusted weight for rule $rule: $currentWeight -> $newWeight")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting weights", e)
        }
    }
    
    /**
     * حفظ حالة محرك التوصيات
     */
    fun saveState() {
        try {
            // يمكن هنا حفظ الحالة في التخزين المستمر إذا لزم الأمر
            // مثل حفظ الأوزان المعدلة ومعدل التعلم وسجل التوصيات
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recommendation engine state", e)
        }
    }
}

/**
 * فئة السياق الخاص بالتوصية
 */
data class RecommendationContext(
    val timeOfDay: TimeOfDay,
    val location: String? = null,
    val activityType: String? = null,
    val currentApp: String? = null
)

/**
 * بيانات الوقت المستخدمة في سياق التوصية
 */
data class TimeOfDay(
    val timestamp: Date = Date(),
    val hour: Int = Calendar.getInstance().apply { time = timestamp }.get(Calendar.HOUR_OF_DAY),
    val dayOfWeek: Int = Calendar.getInstance().apply { time = timestamp }.get(Calendar.DAY_OF_WEEK)
)

/**
 * فئة التوصية
 */
data class Recommendation(
    val title: String,
    val description: String,
    val domain: String,
    val type: String,  // advice, activity, content, reminder
    val relevanceScore: Float,
    val actionUrl: String?,
    val actionLabel: String?
)

/**
 * بيانات المستخدم المجمعة للتوصيات
 */
data class UserData(
    var emotionalState: String = "neutral",
    var averageSleepHours: Double = 0.0,
    var averageActivityMinutes: Double = 0.0,
    var interests: List<String> = emptyList(),
    var topApps: List<String> = emptyList(),
    var searchQueries: List<String> = emptyList(),
    var preferences: Map<String, Any> = emptyMap()
)

/**
 * ردود فعل المستخدم على التوصيات
 */
data class RecommendationFeedback(
    val recommendation: Recommendation,
    val interactionType: InteractionType,
    val timestamp: Date
)

/**
 * أنواع تفاعل المستخدم مع التوصيات
 */
enum class InteractionType {
    VIEWED_ONLY,         // شاهد فقط
    CLICKED,             // نقر على التوصية أو الإجراء
    POSITIVE_FEEDBACK,   // أعطى تقييم إيجابي
    DISMISSED,           // تجاهل أو رفض التوصية
    NEGATIVE_FEEDBACK    // أعطى تقييم سلبي
}