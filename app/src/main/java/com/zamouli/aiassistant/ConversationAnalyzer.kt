package com.example.aiassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * محلل المحادثات والتفاعلات المتسلسلة
 * يقوم بتحليل سلسلة من التفاعلات وليس فقط التفاعلات الفردية
 * يتعلم أنماط الحوار والمواضيع المترابطة والسياق المستمر
 */
class ConversationAnalyzer(
    private val context: Context,
    private val userProfileManager: UserProfileManager
) {
    // كاشف اللهجات العربية
    private val dialectDetector = DialectDetector(context)
    companion object {
        private const val TAG = "ConversationAnalyzer"
        
        // طول سلسلة المحادثة التي يتم تحليلها
        private const val CONVERSATION_WINDOW = 10
        
        // أقصى طول للفترة الزمنية بين الرسائل لاعتبارها جزءًا من نفس المحادثة (بالدقائق)
        private const val MAX_CONVERSATION_GAP_MINUTES = 30
        
        // أقصى عدد لموضوعات المحادثة التي يتم تتبعها
        private const val MAX_TRACKED_TOPICS = 20
    }
    
    // قائمة المواضيع التي تم اكتشافها
    private val discoveredTopics = mutableMapOf<String, TopicData>()
    
    // التفضيلات اللغوية للمستخدم
    private val languagePreferences = LanguagePreferences()
    
    /**
     * تحليل سلسلة محادثة جديدة
     */
    suspend fun analyzeConversationChain(): ConversationInsights = withContext(Dispatchers.Default) {
        try {
            // الحصول على آخر تفاعلات من سجل المستخدم
            val recentInteractions = userProfileManager.getInteractionHistory(CONVERSATION_WINDOW)
            
            if (recentInteractions.isEmpty()) {
                return@withContext ConversationInsights()
            }
            
            // تقسيم التفاعلات إلى محادثات متسلسلة بناءً على الوقت
            val conversations = groupInteractionsIntoConversations(recentInteractions)
            
            // تحليل كل محادثة على حدة
            val allInsights = mutableListOf<ConversationInsights>()
            
            for (conversation in conversations) {
                if (conversation.size < 2) continue // نحتاج على الأقل إلى تفاعلين لتكوين محادثة
                
                val insights = analyzeConversation(conversation)
                allInsights.add(insights)
                
                // تحديث التفضيلات اللغوية
                updateLanguagePreferences(conversation)
            }
            
            // دمج الرؤى من جميع المحادثات
            return@withContext mergeInsights(allInsights)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing conversation chain", e)
            return@withContext ConversationInsights()
        }
    }
    
    /**
     * تقسيم التفاعلات إلى محادثات متسلسلة بناءً على الوقت
     */
    private fun groupInteractionsIntoConversations(interactions: List<InteractionEntry>): List<List<InteractionEntry>> {
        if (interactions.isEmpty()) return emptyList()
        
        val sortedInteractions = interactions.sortedBy { it.timestamp }
        val conversations = mutableListOf<MutableList<InteractionEntry>>()
        var currentConversation = mutableListOf<InteractionEntry>()
        
        // ابدأ بأول تفاعل
        currentConversation.add(sortedInteractions.first())
        
        // قسم التفاعلات بناءً على الفاصل الزمني
        for (i in 1 until sortedInteractions.size) {
            val prevTime = sortedInteractions[i-1].timestamp.time
            val currentTime = sortedInteractions[i].timestamp.time
            
            // إذا كان الفارق الزمني أقل من الحد الأقصى، أضف إلى المحادثة الحالية
            if ((currentTime - prevTime) <= (MAX_CONVERSATION_GAP_MINUTES * 60 * 1000)) {
                currentConversation.add(sortedInteractions[i])
            } else {
                // ابدأ محادثة جديدة
                if (currentConversation.isNotEmpty()) {
                    conversations.add(currentConversation)
                }
                currentConversation = mutableListOf(sortedInteractions[i])
            }
        }
        
        // أضف آخر محادثة
        if (currentConversation.isNotEmpty()) {
            conversations.add(currentConversation)
        }
        
        return conversations
    }
    
    /**
     * تحليل محادثة واحدة
     */
    private fun analyzeConversation(conversation: List<InteractionEntry>): ConversationInsights {
        val insights = ConversationInsights()
        
        // تحليل المواضيع والكلمات المفتاحية
        val (topics, keywords) = extractTopicsAndKeywords(conversation)
        insights.topics.addAll(topics)
        insights.keywords.addAll(keywords)
        
        // تحليل أنماط الأسئلة
        insights.questionPatterns.addAll(extractQuestionPatterns(conversation))
        
        // تحليل الحالة العاطفية السائدة
        insights.dominantEmotionalState = extractDominantEmotionalState(conversation)
        
        // تحليل وقت المحادثة
        insights.conversationTimeOfDay = extractTimeOfDay(conversation)
        
        // تحديد سياق المحادثة
        insights.conversationContext = determineConversationContext(conversation)
        
        return insights
    }
    
    /**
     * استخراج المواضيع والكلمات المفتاحية من المحادثة
     */
    private fun extractTopicsAndKeywords(conversation: List<InteractionEntry>): Pair<Set<String>, Set<String>> {
        val allText = conversation.joinToString(" ") { it.query }
        
        // قائمة الكلمات المستبعدة
        val stopWords = setOf(
            "من", "في", "على", "إلى", "عن", "مع", "هل", "ما", "ماذا", "كيف", 
            "لماذا", "متى", "أين", "هو", "هي", "نحن", "هم", "أنا", "أنت", "التي", 
            "الذي", "كان", "كانت", "و", "أو", "ثم", "لكن", "لذلك", "لأن", "إذا"
        )
        
        // استخراج الكلمات المفتاحية (الكلمات المتكررة والطويلة)
        val wordCounts = allText.split(" ", ".", "،", "!", "؟", ":", "-")
            .filter { it.length > 3 && !stopWords.contains(it.toLowerCase()) }
            .groupBy { it.toLowerCase() }
            .mapValues { it.value.size }
        
        // الكلمات المفتاحية هي الكلمات الأكثر تكراراً
        val keywords = wordCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }
            .toSet()
        
        // استخراج المواضيع من خلال تجميع الكلمات المفتاحية المترابطة
        val topics = mutableSetOf<String>()
        
        // باستخدام الكلمات المفتاحية المستخرجة، نحاول تحديد المواضيع
        val detectedTopics = detectTopicsFromKeywords(keywords)
        topics.addAll(detectedTopics)
        
        // تحديث قاعدة بيانات المواضيع
        updateTopicsDatabase(topics, conversation)
        
        return Pair(topics, keywords)
    }
    
    /**
     * اكتشاف المواضيع من الكلمات المفتاحية
     */
    private fun detectTopicsFromKeywords(keywords: Set<String>): Set<String> {
        val topics = mutableSetOf<String>()
        
        // مجموعات الكلمات المتعلقة بمواضيع معينة
        val topicKeywordGroups = mapOf(
            "صحة" to setOf("صحة", "طب", "مرض", "دواء", "علاج", "طبيب", "مستشفى", "صداع", "ألم"),
            "تقنية" to setOf("تقنية", "كمبيوتر", "هاتف", "إنترنت", "تطبيق", "برنامج", "تصميم", "برمجة"),
            "تعليم" to setOf("تعليم", "مدرسة", "جامعة", "دراسة", "كتاب", "قراءة", "علم", "بحث"),
            "رياضة" to setOf("رياضة", "كرة", "لاعب", "مباراة", "تمرين", "جري", "سباحة", "لياقة"),
            "طعام" to setOf("طعام", "أكل", "طبخ", "وصفة", "مطعم", "غداء", "عشاء", "فطور"),
            "أخبار" to setOf("أخبار", "عاجل", "حدث", "تقرير", "سياسة", "اقتصاد", "عالم"),
            "سفر" to setOf("سفر", "سياحة", "فندق", "رحلة", "طيران", "تذكرة", "مطار", "حقيبة"),
            "ترفيه" to setOf("ترفيه", "فيلم", "موسيقى", "أغنية", "مسلسل", "أفلام", "فن", "مسرح")
        )
        
        // البحث عن تطابق الكلمات المفتاحية مع مجموعات المواضيع
        for ((topic, relatedWords) in topicKeywordGroups) {
            if (keywords.any { keyword -> relatedWords.any { it in keyword } }) {
                topics.add(topic)
            }
        }
        
        // إضافة مواضيع من الكلمات المفتاحية الفردية التي قد تكون مواضيع بحد ذاتها
        keywords.filter { it.length > 5 }.forEach { keyword ->
            // يمكن اعتبار الكلمات الطويلة مواضيع محتملة
            topics.add(keyword)
        }
        
        return topics.take(5).toSet()
    }
    
    /**
     * تحديث قاعدة بيانات المواضيع
     */
    private fun updateTopicsDatabase(topics: Set<String>, conversation: List<InteractionEntry>) {
        val timestamp = conversation.last().timestamp
        
        for (topic in topics) {
            if (!discoveredTopics.containsKey(topic)) {
                discoveredTopics[topic] = TopicData(topic, 1, timestamp)
            } else {
                val topicData = discoveredTopics[topic]!!
                discoveredTopics[topic] = topicData.copy(
                    occurrences = topicData.occurrences + 1,
                    lastDiscussed = max(topicData.lastDiscussed.time, timestamp.time).let { Date(it) }
                )
            }
        }
        
        // الاحتفاظ فقط بالمواضيع الأكثر شيوعاً
        if (discoveredTopics.size > MAX_TRACKED_TOPICS) {
            val sortedTopics = discoveredTopics.values
                .sortedByDescending { it.occurrences }
                .take(MAX_TRACKED_TOPICS)
            
            discoveredTopics.clear()
            sortedTopics.forEach { discoveredTopics[it.name] = it }
        }
    }
    
    /**
     * استخراج أنماط الأسئلة من المحادثة
     */
    private fun extractQuestionPatterns(conversation: List<InteractionEntry>): List<String> {
        val questionPatterns = mutableListOf<String>()
        
        // البحث عن جمل الاستفهام
        val questionIntroducers = listOf("هل", "ما", "ماذا", "كيف", "لماذا", "متى", "أين", "من", "كم")
        
        for (interaction in conversation) {
            val query = interaction.query
            
            // تحقق مما إذا كانت الجملة تبدأ بكلمة استفهام أو تنتهي بعلامة استفهام
            if (query.contains("؟") || questionIntroducers.any { query.startsWith(it) }) {
                // استخراج نمط السؤال عن طريق تعميم الكلمات المحددة
                val pattern = generalizeQuestionPattern(query)
                questionPatterns.add(pattern)
            }
        }
        
        return questionPatterns.distinct().take(5)
    }
    
    /**
     * تعميم نمط السؤال باستبدال الكلمات المحددة بمتغيرات
     */
    private fun generalizeQuestionPattern(question: String): String {
        var pattern = question
        
        // قائمة من أنواع الكلمات التي يمكن استبدالها
        val namePattern = "\\b[A-Za-zأ-ي]{3,}\\s[A-Za-zأ-ي]{3,}\\b".toRegex()
        val placePattern = "\\b(في|من|إلى|ب)\\s([A-Za-zأ-ي]+)\\b".toRegex()
        val numberPattern = "\\b\\d+\\b".toRegex()
        
        // استبدال الأسماء والأماكن والأرقام بمتغيرات
        pattern = pattern.replace(namePattern, "[اسم]")
        pattern = pattern.replace(placePattern, "$1 [مكان]")
        pattern = pattern.replace(numberPattern, "[رقم]")
        
        return pattern
    }
    
    /**
     * استخراج الحالة العاطفية السائدة في المحادثة
     */
    private fun extractDominantEmotionalState(conversation: List<InteractionEntry>): String {
        val emotionalStateCount = mutableMapOf<String, Int>()
        
        for (interaction in conversation) {
            val state = interaction.emotionalState
            emotionalStateCount[state] = (emotionalStateCount[state] ?: 0) + 1
        }
        
        return emotionalStateCount.entries
            .maxByOrNull { it.value }
            ?.key ?: "neutral"
    }
    
    /**
     * تحديد وقت المحادثة (صباح، ظهر، مساء، ليل)
     */
    private fun extractTimeOfDay(conversation: List<InteractionEntry>): String {
        val hour = conversation.first().timestamp.let {
            val calendar = java.util.Calendar.getInstance()
            calendar.time = it
            calendar.get(java.util.Calendar.HOUR_OF_DAY)
        }
        
        return when (hour) {
            in 5..11 -> "صباح"
            in 12..15 -> "ظهر"
            in 16..19 -> "مساء"
            else -> "ليل"
        }
    }
    
    /**
     * تحديد سياق المحادثة
     */
    private fun determineConversationContext(conversation: List<InteractionEntry>): String {
        // تحليل بسيط للسياق بناءً على الكلمات والعبارات المتكررة
        val contextKeywords = mapOf(
            "استفسار" to listOf("كيف", "ما هو", "ما هي", "هل", "لماذا", "متى", "أين", "؟"),
            "طلب مساعدة" to listOf("ساعدني", "مساعدة", "احتاج", "كيف يمكنني", "كيفية", "طريقة"),
            "إعطاء أمر" to listOf("افعل", "افتح", "أغلق", "شغل", "أوقف", "ابحث", "اتصل", "ارسل", "ضبط"),
            "حوار عام" to listOf("أخبرني", "حدثني", "ماذا تعرف", "رأيك في", "ما هي")
        )
        
        val contextCounts = contextKeywords.mapValues { (_, keywords) ->
            conversation.count { interaction ->
                keywords.any { keyword -> 
                    interaction.query.contains(keyword, ignoreCase = true) 
                }
            }
        }
        
        return contextCounts.entries
            .maxByOrNull { it.value }
            ?.key ?: "حوار عام"
    }
    
    /**
     * دمج الرؤى من عدة محادثات
     */
    private fun mergeInsights(allInsights: List<ConversationInsights>): ConversationInsights {
        if (allInsights.isEmpty()) return ConversationInsights()
        
        val merged = ConversationInsights()
        
        // دمج المواضيع والكلمات المفتاحية
        allInsights.forEach { insights ->
            merged.topics.addAll(insights.topics)
            merged.keywords.addAll(insights.keywords)
            merged.questionPatterns.addAll(insights.questionPatterns)
        }
        
        // الحفاظ على التميز
        merged.topics = merged.topics.take(10).toMutableSet()
        merged.keywords = merged.keywords.take(20).toMutableSet()
        merged.questionPatterns = merged.questionPatterns.take(10).toMutableList()
        
        // تحديد الحالة العاطفية السائدة
        val emotionalStates = allInsights.map { it.dominantEmotionalState }
        merged.dominantEmotionalState = emotionalStates
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: "neutral"
        
        // تحديد سياق المحادثة الأكثر شيوعًا
        val contexts = allInsights.map { it.conversationContext }
        merged.conversationContext = contexts
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: "حوار عام"
        
        // تحديد وقت المحادثة الأكثر شيوعًا
        val timeOfDay = allInsights.map { it.conversationTimeOfDay }
        merged.conversationTimeOfDay = timeOfDay
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: "غير محدد"
        
        return merged
    }
    
    /**
     * تحديث تفضيلات اللغة بناءً على محادثة
     */
    private fun updateLanguagePreferences(conversation: List<InteractionEntry>) {
        // تحليل الكلمات المستخدمة للعثور على الكلمات المفضلة
        val allText = conversation.joinToString(" ") { it.query }
        val words = allText.split(" ", ".", "،", "!", "؟", ":", "-")
            .filter { it.length > 3 }
        
        // تحديث قائمة الكلمات المفضلة
        for (word in words) {
            languagePreferences.incrementWordUsage(word)
        }
        
        // تحليل الجمل لمعرفة الطول المفضل
        val sentences = allText.split(".", "؟", "!").filter { it.isNotEmpty() }
        val avgSentenceLength = sentences.sumOf { it.length } / max(sentences.size, 1)
        languagePreferences.updatePreferredSentenceLength(avgSentenceLength)
        
        // البحث عن استخدام اللهجات
        val dialectWords = detectDialectWords(words)
        for ((dialect, count) in dialectWords) {
            languagePreferences.recordDialectUsage(dialect, count)
        }
    }
    
    /**
     * اكتشاف الكلمات اللهجية في النص
     */
    private suspend fun detectDialectWords(words: List<String>): Map<String, Int> {
        val dialectWords = mutableMapOf<String, Int>()
        
        // كلمات وعبارات باللهجة السودانية
        val sudaneseWords = setOf(
            "شنو", "ما شنو", "كيفن", "وين", "زاتو", "زول", "زلام", "قاعد", 
            "هسع", "ياخي", "والله", "ماشي", "خلاص", "تعال", "تعالي", "يا ود",
            "كدي", "كدا", "البراكم", "التلقي", "تباً", "شديد", "عديل",
            "الأخاف", "مافي", "ماف", "دوك", "البتجي", "شفت"
        )
        
        // حساب تكرار الكلمات اللهجية
        val sudaneseCount = words.count { word -> 
            sudaneseWords.any { dialectWord -> 
                word.toLowerCase().contains(dialectWord.toLowerCase()) 
            } 
        }
        
        if (sudaneseCount > 0) {
            dialectWords["sudanese"] = sudaneseCount
        }
        
        return dialectWords
    }
    
    /**
     * الحصول على التفضيلات اللغوية الحالية
     */
    fun getLanguagePreferences(): LanguagePreferences {
        return languagePreferences
    }
    
    /**
     * الحصول على المواضيع المكتشفة
     */
    fun getDiscoveredTopics(): List<TopicData> {
        return discoveredTopics.values.sortedByDescending { it.occurrences }
    }
    
    /**
     * تكييف النص حسب تفضيلات المستخدم اللغوية
     */
    fun adaptTextToUserPreferences(originalText: String): String {
        // إذا لم يكن هناك تفضيلات محددة، أعد النص كما هو
        if (!languagePreferences.hasLearnedPreferences()) {
            return originalText
        }
        
        var adaptedText = originalText
        
        // تكييف طول الجمل
        val preferredLength = languagePreferences.getPreferredSentenceLength()
        if (preferredLength > 0) {
            adaptedText = adaptSentenceLength(adaptedText, preferredLength)
        }
        
        // استخدام اللهجة المفضلة إذا كانت محددة
        val preferredDialect = languagePreferences.getPreferredDialect()
        if (preferredDialect.isNotEmpty()) {
            adaptedText = adaptToDialect(adaptedText, preferredDialect)
        }
        
        // استبدال الكلمات بكلمات مفضلة عند الإمكان
        val preferredWords = languagePreferences.getPreferredWords()
        if (preferredWords.isNotEmpty()) {
            adaptedText = replaceWithPreferredWords(adaptedText, preferredWords)
        }
        
        return adaptedText
    }
    
    /**
     * تكييف طول الجمل حسب التفضيل
     */
    private fun adaptSentenceLength(text: String, preferredLength: Int): String {
        // تقسيم النص إلى جمل
        val sentences = text.split(". ", "؟ ", "! ").filter { it.isNotEmpty() }
        if (sentences.size <= 1) return text
        
        val adaptedSentences = mutableListOf<String>()
        
        for (sentence in sentences) {
            if (sentence.length > preferredLength * 1.5) {
                // تقسيم الجمل الطويلة إلى جمل أقصر
                val parts = splitSentence(sentence)
                adaptedSentences.addAll(parts)
            } else if (sentence.length < preferredLength * 0.5 && adaptedSentences.isNotEmpty()) {
                // دمج الجمل القصيرة مع الجملة السابقة
                val lastSentence = adaptedSentences.removeAt(adaptedSentences.size - 1)
                adaptedSentences.add("$lastSentence، $sentence")
            } else {
                adaptedSentences.add(sentence)
            }
        }
        
        return adaptedSentences.joinToString(". ")
    }
    
    /**
     * تقسيم جملة طويلة إلى عدة جمل
     */
    private fun splitSentence(sentence: String): List<String> {
        // البحث عن النقاط المناسبة للتقسيم (الفواصل، و، ثم، إلخ)
        val splitPoints = listOf("،", "و", "ثم", "حيث", "أيضًا", "كما", "لكن")
        
        for (point in splitPoints) {
            if (sentence.contains(point)) {
                return sentence.split(point)
                    .filter { it.isNotEmpty() }
                    .map { it.trim() }
            }
        }
        
        // إذا لم يتم العثور على نقاط تقسيم، أعد الجملة كما هي
        return listOf(sentence)
    }
    
    /**
     * تكييف النص حسب اللهجة المفضلة
     */
    private fun adaptToDialect(text: String, dialect: String): String {
        if (dialect == "sudanese") {
            return adaptToSudaneseDialect(text)
        }
        
        return text
    }
    
    /**
     * تكييف النص إلى اللهجة السودانية
     */
    private fun adaptToSudaneseDialect(text: String): String {
        // قائمة من كلمات فصحى ومقابلاتها باللهجة السودانية
        val dialectMapping = mapOf(
            "ماذا" to "شنو",
            "كيف" to "كيفن",
            "أين" to "وين",
            "هذا" to "دا",
            "هذه" to "دي",
            "الآن" to "هسع",
            "حسناً" to "تمام",
            "جداً" to "عديل",
            "نعم" to "أيوة",
            "لا" to "لأ",
            "انظر" to "شوف",
            "تعال" to "تعال",
            "اذهب" to "امشي",
            "أريد" to "عايز",
            "لا يوجد" to "مافي",
            "هنا" to "هنا",
            "هناك" to "هناك"
        )
        
        var adaptedText = text
        
        // استبدال الكلمات الفصحى بمقابلاتها باللهجة السودانية
        for ((standard, dialect) in dialectMapping) {
            val regex = "\\b$standard\\b".toRegex()
            adaptedText = adaptedText.replace(regex, dialect)
        }
        
        // لا نريد تحويل كل الكلمات، فقط نسبة منها لتعطي طابع اللهجة
        // لذلك نختار عشوائياً بعض الاستبدالات
        
        return adaptedText
    }
    
    /**
     * استبدال الكلمات بكلمات مفضلة
     */
    private fun replaceWithPreferredWords(text: String, preferredWords: Map<String, Int>): String {
        var adaptedText = text
        
        // قائمة من الكلمات المرادفة في اللغة العربية
        val synonyms = mapOf(
            "جميل" to listOf("رائع", "جذاب", "فاتن", "حسن"),
            "كبير" to listOf("ضخم", "عظيم", "هائل", "واسع"),
            "صغير" to listOf("ضئيل", "ضآلة", "بسيط", "محدود"),
            "سريع" to listOf("عاجل", "سريع الحركة", "متعجل", "خفيف"),
            "بطيء" to listOf("متمهل", "متأني", "متباطئ", "ثقيل الحركة"),
            "جيد" to listOf("ممتاز", "فاخر", "مثالي", "فائق"),
            "سيء" to listOf("رديء", "سيئ", "فاسد", "قبيح"),
            "سعيد" to listOf("مبتهج", "فرح", "مسرور", "مرح"),
            "حزين" to listOf("مكتئب", "محزون", "متألم", "مغموم")
        )
        
        // البحث عن المرادفات في النص واستبدالها بالكلمات المفضلة
        for ((word, syns) in synonyms) {
            for (synonym in syns) {
                if (text.contains(synonym) && preferredWords.containsKey(word)) {
                    // استبدال المرادف بالكلمة المفضلة
                    adaptedText = adaptedText.replace("\\b$synonym\\b".toRegex(), word)
                }
            }
        }
        
        return adaptedText
    }
}

/**
 * بيانات التفضيلات اللغوية للمستخدم
 */
class LanguagePreferences {
    // الكلمات المستخدمة وعدد مرات استخدامها
    private val wordUsage = mutableMapOf<String, Int>()
    
    // متوسط طول الجملة المفضل
    private var preferredSentenceLength = 0
    private var sentenceLengthSamples = 0
    
    // اللهجات المستخدمة وعدد مرات استخدامها
    private val dialectUsage = mutableMapOf<String, Int>()
    
    /**
     * زيادة عدد مرات استخدام كلمة
     */
    fun incrementWordUsage(word: String) {
        val normalizedWord = word.toLowerCase()
        wordUsage[normalizedWord] = (wordUsage[normalizedWord] ?: 0) + 1
    }
    
    /**
     * تحديث طول الجملة المفضل
     */
    fun updatePreferredSentenceLength(length: Int) {
        if (preferredSentenceLength == 0) {
            preferredSentenceLength = length
            sentenceLengthSamples = 1
        } else {
            // المتوسط المتحرك
            val newTotal = preferredSentenceLength * sentenceLengthSamples + length
            sentenceLengthSamples++
            preferredSentenceLength = newTotal / sentenceLengthSamples
        }
    }
    
    /**
     * تسجيل استخدام لهجة
     */
    fun recordDialectUsage(dialect: String, count: Int) {
        dialectUsage[dialect] = (dialectUsage[dialect] ?: 0) + count
    }
    
    /**
     * الحصول على الكلمات المفضلة
     */
    fun getPreferredWords(): Map<String, Int> {
        // نختار الكلمات الأكثر استخداماً التي تم استخدامها عدة مرات
        return wordUsage.filter { it.value > 3 }
            .toList()
            .sortedByDescending { it.second }
            .take(50)
            .toMap()
    }
    
    /**
     * الحصول على طول الجملة المفضل
     */
    fun getPreferredSentenceLength(): Int {
        return preferredSentenceLength
    }
    
    /**
     * الحصول على اللهجة المفضلة
     */
    fun getPreferredDialect(): String {
        return dialectUsage.entries
            .maxByOrNull { it.value }
            ?.key ?: ""
    }
    
    /**
     * التحقق مما إذا كان هناك تفضيلات تم تعلمها
     */
    fun hasLearnedPreferences(): Boolean {
        return preferredSentenceLength > 0 || wordUsage.isNotEmpty() || dialectUsage.isNotEmpty()
    }
}

/**
 * فئة تمثل موضوعاً تمت مناقشته
 */
data class TopicData(
    val name: String,
    val occurrences: Int,
    val lastDiscussed: Date
)

/**
 * فئة لتخزين الرؤى المستخلصة من تحليل المحادثات
 */
data class ConversationInsights(
    var topics: MutableSet<String> = mutableSetOf(),
    var keywords: MutableSet<String> = mutableSetOf(),
    var questionPatterns: MutableList<String> = mutableListOf(),
    var dominantEmotionalState: String = "neutral",
    var conversationContext: String = "حوار عام",
    var conversationTimeOfDay: String = "غير محدد"
)