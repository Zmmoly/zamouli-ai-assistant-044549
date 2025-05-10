package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Class responsible for AI text processing using TensorFlow Lite
 */
class AIProcessor(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val labelList: List<String>
    private val MAX_RESULT_COUNT = 3
    private val WORD_THRESHOLD = 0.3f
    private val MAX_SENTENCE_LENGTH = 256
    
    // Voice tone analysis properties
    var emotionalState: VoiceToneAnalyzer.EmotionalState = VoiceToneAnalyzer.EmotionalState.NEUTRAL
    var emotionalStateConfidence: Float = 0.0f
    
    // لإدارة الملف الشخصي للمستخدم
    private val userProfileManager = UserProfileManager(context)
    
    // لتحليل سلوك المستخدم
    private val behaviorAnalyzer = BehaviorAnalyzer(context, userProfileManager)
    
    // لتتبع الصحة ونمط الحياة
    private val healthTracker = HealthTracker(context, userProfileManager)
    
    // محرك التوصيات الذكي
    private val recommendationEngine = RecommendationEngine(context, userProfileManager, behaviorAnalyzer, healthTracker)
    
    // محلل المحادثات للتعلم من سلسلة التفاعلات
    private val conversationAnalyzer = ConversationAnalyzer(context, userProfileManager)
    
    // محرك الاستدلال المنطقي للتخطيط وتنفيذ المهام المعقدة
    private val logicalReasoningEngine = LogicalReasoningEngine(context, deviceController, webProcessorBridge, userProfileManager)
    
    // A simple pre-defined response map for common phrases
    private val predefinedResponses = mapOf(
        "مرحبا" to "مرحباً! كيف يمكنني مساعدتك اليوم؟",
        "كيف حالك" to "أنا بخير، شكراً للسؤال! كيف يمكنني مساعدتك؟",
        "من أنت" to "أنا مساعد ذكاء اصطناعي مبني على تقنية التعلم الآلي. تم تصميمي لمساعدتك في الإجابة على أسئلتك.",
        "ماذا يمكنك أن تفعل" to "يمكنني الإجابة على أسئلتك البسيطة، وتقديم معلومات عامة، ومساعدتك في تنظيم أفكارك.",
        "شكرا" to "أهلاً بك! سعيد بمساعدتك.",
        "وداعا" to "إلى اللقاء! أتمنى لك يوماً سعيداً."
    )
    
    // A simple list of fallback responses when no good match is found
    private val fallbackResponses = listOf(
        "هذا سؤال مثير للاهتمام. هل يمكنك إخباري المزيد عن ما تبحث عنه؟",
        "لم أفهم تماماً ما تسأل عنه. هل يمكنك توضيح سؤالك؟",
        "أنا آسف، لكن معلوماتي محدودة في هذا المجال. هل هناك شيء آخر يمكنني مساعدتك به؟",
        "دعني أفكر في ذلك... يبدو أن هذا موضوع معقد. هل يمكنك طرح سؤال أكثر تحديداً؟",
        "أنا مساعد ذكاء اصطناعي بسيط وأعمل بشكل محلي على جهازك، لذا قدراتي محدودة. هل يمكنني مساعدتك في موضوع آخر؟"
    )

    init {
        try {
            // Load the TFLite model
            val model = loadModelFile(context, "model.tflite")
            interpreter = Interpreter(model)
            
            // Load labels
            labelList = loadLabelList(context)
            
            Log.d(TAG, "TFLite model and labels loaded successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Error loading model or labels", e)
            labelList = ArrayList()
            // Use simple pattern matching without model if model loading fails
        }
    }

    // Device controller for advanced device functions
    private val deviceController = DeviceController(context)
    
    // Automation controller for advanced UI automation
    private val automationController = AutomationController(context)
    
    // Web processor bridge for advanced web content extraction
    private val webProcessorBridge = WebProcessorBridge(context)
    
    // Voice tone analyzer for emotional state detection
    private val voiceToneAnalyzer = VoiceToneAnalyzer(context)
    
    // Medical analysis processor for lab results understanding
    private val medicalAnalysisProcessor = MedicalAnalysisProcessor(context, healthTracker)
    
    // تم تحديد الحالة العاطفية للمستخدم في المتغيرات العامة
    // emotionalState, emotionalStateConfidence
    
    /**
     * Process the input text and generate a response
     * Now uses coroutines for async operations
     */
    suspend fun processText(text: String): String {
        val lowerText = text.toLowerCase(Locale.getDefault())
        
        // لتحليل المحادثات المستمرة وتحديث تفضيلات اللغة
        // نقوم بذلك في خلفية العملية لتجنب إبطاء المعالجة الرئيسية
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                conversationAnalyzer.analyzeConversationChain()
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing conversation chain", e)
            }
        }

        // التحقق من طلب تحليل نبرة الصوت
        if (lowerText.contains("حلل نبرة صوتي") || 
            lowerText.contains("تحليل نبرة الصوت") ||
            lowerText.contains("كيف يبدو صوتي") ||
            lowerText.contains("ما هي حالتي العاطفية")) {
            return analyzeVoiceTone()
        }

        // Check for device control related commands
        
        // Phone call commands
        if (lowerText.contains("اتصل") || lowerText.contains("مكالمة") || lowerText.contains("اجري اتصال")) {
            val phonePattern = Regex("\\d{7,15}")
            val phoneMatch = phonePattern.find(text)
            
            if (phoneMatch != null) {
                val phoneNumber = phoneMatch.value
                if (deviceController.makePhoneCall(phoneNumber)) {
                    return adaptResponseToEmotionalState("جاري الاتصال بالرقم: $phoneNumber")
                } else {
                    return "حدث خطأ أثناء محاولة الاتصال. تأكد من منح الأذونات المطلوبة."
                }
            } else if (lowerText.contains("اتصل ب") || lowerText.contains("مكالمة مع")) {
                // Extract contact name
                val nameStart = if (lowerText.contains("اتصل ب")) {
                    lowerText.indexOf("اتصل ب") + "اتصل ب".length
                } else {
                    lowerText.indexOf("مكالمة مع") + "مكالمة مع".length
                }
                
                val contactName = text.substring(nameStart).trim()
                if (contactName.isNotEmpty()) {
                    // Get contacts list
                    val contacts = deviceController.getContacts()
                    val matchedContact = contacts.firstOrNull { 
                        it["name"]?.toLowerCase(Locale.getDefault())?.contains(contactName.toLowerCase(Locale.getDefault())) == true 
                    }
                    
                    if (matchedContact != null) {
                        val phoneNumber = matchedContact["number"] ?: ""
                        if (deviceController.makePhoneCall(phoneNumber)) {
                            return "جاري الاتصال بـ ${matchedContact["name"]}"
                        } else {
                            return "حدث خطأ أثناء محاولة الاتصال. تأكد من منح الأذونات المطلوبة."
                        }
                    } else {
                        return "لم أجد جهة الاتصال \"$contactName\" في قائمة جهات الاتصال."
                    }
                }
            }
        }
        
        // SMS commands
        if (lowerText.contains("ارسل رسالة") || lowerText.contains("بعث رسالة")) {
            // Format: "ارسل رسالة إلى [اسم/رقم] [نص الرسالة]"
            val extractToPattern = "(?:إلى|الى|ل|لـ)\\s+([^\\s]+)"
            val toMatch = Regex(extractToPattern).find(lowerText)
            
            if (toMatch != null) {
                val recipient = toMatch.groupValues[1]
                
                // Extract message text - everything after the recipient
                val messageStart = lowerText.indexOf(recipient) + recipient.length
                val message = text.substring(messageStart).trim()
                
                if (message.isNotEmpty()) {
                    // Check if recipient is a phone number or name
                    val phonePattern = Regex("\\d{7,15}")
                    if (phonePattern.matches(recipient)) {
                        if (deviceController.sendSMS(recipient, message)) {
                            return "تم إرسال الرسالة إلى الرقم $recipient"
                        } else {
                            return "حدث خطأ أثناء إرسال الرسالة. تأكد من منح الأذونات المطلوبة."
                        }
                    } else {
                        // Try to find contact by name
                        val contacts = deviceController.getContacts()
                        val matchedContact = contacts.firstOrNull { 
                            it["name"]?.toLowerCase(Locale.getDefault())?.contains(recipient.toLowerCase(Locale.getDefault())) == true 
                        }
                        
                        if (matchedContact != null) {
                            val phoneNumber = matchedContact["number"] ?: ""
                            if (deviceController.sendSMS(phoneNumber, message)) {
                                return "تم إرسال الرسالة إلى ${matchedContact["name"]}"
                            } else {
                                return "حدث خطأ أثناء إرسال الرسالة. تأكد من منح الأذونات المطلوبة."
                            }
                        } else {
                            return "لم أجد جهة الاتصال \"$recipient\" في قائمة جهات الاتصال."
                        }
                    }
                } else {
                    return "لم تذكر نص الرسالة. يرجى تحديد نص الرسالة بعد اسم المستلم."
                }
            }
        }
        
        // Calendar and event commands
        if (lowerText.contains("أضف حدث") || lowerText.contains("أنشئ حدث") || lowerText.contains("حدد موعد")) {
            // Extract event details
            val titlePattern = "(?:بعنوان|اسمه)\\s+\"([^\"]+)\""
            val titleMatch = Regex(titlePattern).find(lowerText)
            
            val title = if (titleMatch != null) {
                titleMatch.groupValues[1]
            } else {
                "حدث جديد"
            }
            
            // Try to extract date and time
            val dateTimePatterns = listOf(
                "(?:في|على|بتاريخ)\\s+(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})",  // dd/mm/yyyy
                "(?:في|على|بتاريخ)\\s+(\\d{1,2})\\s+([^\\s]+)\\s+(\\d{2,4})"     // dd month yyyy
            )
            
            var year = Calendar.getInstance().get(Calendar.YEAR)
            var month = Calendar.getInstance().get(Calendar.MONTH)
            var day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            var hour = 12
            var minute = 0
            
            // Try to extract date
            for (pattern in dateTimePatterns) {
                val dateMatch = Regex(pattern).find(lowerText)
                if (dateMatch != null) {
                    val dateGroups = dateMatch.groupValues
                    if (dateGroups.size >= 4) {
                        day = dateGroups[1].toInt()
                        
                        // Handle numeric month or text month
                        if (dateGroups[2].toIntOrNull() != null) {
                            month = dateGroups[2].toInt() - 1 // Calendar months are 0-based
                        } else {
                            // Convert Arabic month name to number
                            val monthName = dateGroups[2].toLowerCase()
                            month = when {
                                monthName.contains("يناير") -> 0
                                monthName.contains("فبراير") -> 1
                                monthName.contains("مارس") -> 2
                                monthName.contains("أبريل") -> 3
                                monthName.contains("مايو") -> 4
                                monthName.contains("يونيو") -> 5
                                monthName.contains("يوليو") -> 6
                                monthName.contains("أغسطس") -> 7
                                monthName.contains("سبتمبر") -> 8
                                monthName.contains("أكتوبر") -> 9
                                monthName.contains("نوفمبر") -> 10
                                monthName.contains("ديسمبر") -> 11
                                else -> Calendar.getInstance().get(Calendar.MONTH)
                            }
                        }
                        
                        // Parse year
                        val yearStr = dateGroups[3]
                        year = if (yearStr.length <= 2) {
                            2000 + yearStr.toInt()  // Assuming 2-digit years are in the 2000s
                        } else {
                            yearStr.toInt()
                        }
                        
                        break
                    }
                }
            }
            
            // Try to extract time
            val timePattern = "(?:في|الساعة)\\s+(\\d{1,2})(?:[:.]?(\\d{2}))?\\s*(?:صباحا|مساء)?"
            val timeMatch = Regex(timePattern).find(lowerText)
            if (timeMatch != null) {
                val timeGroups = timeMatch.groupValues
                hour = timeGroups[1].toInt()
                minute = if (timeGroups.size > 2 && timeGroups[2].isNotEmpty()) timeGroups[2].toInt() else 0
                
                // Check for AM/PM
                if (lowerText.contains("مساء") && hour < 12) {
                    hour += 12
                }
            }
            
            // Create calendar objects for start and end times
            val startTime = Calendar.getInstance()
            startTime.set(year, month, day, hour, minute)
            
            val endTime = Calendar.getInstance()
            endTime.set(year, month, day, hour + 1, minute)
            
            // Extract description
            val descriptionPattern = "(?:وصف|ملاحظة|تفاصيل)\\s+\"([^\"]+)\""
            val descriptionMatch = Regex(descriptionPattern).find(lowerText)
            val description = if (descriptionMatch != null) {
                descriptionMatch.groupValues[1]
            } else {
                ""
            }
            
            // Add the event
            if (deviceController.addCalendarEvent(title, description, startTime.timeInMillis, endTime.timeInMillis)) {
                return "تم إضافة الحدث \"$title\" في تاريخ ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(startTime.time)} الساعة ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(startTime.time)}"
            } else {
                return "لم أتمكن من إضافة الحدث. تأكد من منح التطبيق الأذونات المطلوبة للوصول إلى التقويم."
            }
        }
        
        // Alarm and timer commands
        if (lowerText.contains("ضبط منبه") || lowerText.contains("اضبط منبه") || lowerText.contains("ضع منبه")) {
            // Extract time
            val timePattern = "(?:على|في|الساعة)\\s+(\\d{1,2})(?:[:.]?(\\d{2}))?\\s*(?:صباحا|مساء)?"
            val timeMatch = Regex(timePattern).find(lowerText)
            
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = if (timeMatch.groupValues.size > 2 && timeMatch.groupValues[2].isNotEmpty()) 
                    timeMatch.groupValues[2].toInt() else 0
                
                // Check for AM/PM
                if (lowerText.contains("مساء") && hour < 12) {
                    hour += 12
                } else if (lowerText.contains("صباحا") && hour == 12) {
                    hour = 0
                }
                
                // Extract alarm name/label
                val labelPattern = "(?:بعنوان|باسم|اسمه)\\s+\"([^\"]+)\""
                val labelMatch = Regex(labelPattern).find(lowerText)
                
                val alarmLabel = if (labelMatch != null) {
                    labelMatch.groupValues[1]
                } else {
                    "منبه"
                }
                
                // Set the alarm
                if (deviceController.setAlarm(hour, minute, alarmLabel)) {
                    return "تم ضبط المنبه على الساعة ${String.format("%02d:%02d", hour, minute)}"
                } else {
                    return "حدث خطأ أثناء ضبط المنبه."
                }
            } else {
                return "لم أفهم وقت المنبه. يرجى تحديد الوقت بوضوح، مثل: \"ضبط منبه على الساعة 7:30 صباحا\""
            }
        }
        
        // Timer commands
        if (lowerText.contains("ضبط مؤقت") || lowerText.contains("اضبط مؤقت") || lowerText.contains("ضع مؤقت")) {
            // Extract time
            val minutesPattern = "(\\d+)\\s+(?:دقيقة|دقائق)"
            val secondsPattern = "(\\d+)\\s+(?:ثانية|ثواني)"
            
            val minutesMatch = Regex(minutesPattern).find(lowerText)
            val secondsMatch = Regex(secondsPattern).find(lowerText)
            
            var totalSeconds = 0
            
            if (minutesMatch != null) {
                totalSeconds += minutesMatch.groupValues[1].toInt() * 60
            }
            
            if (secondsMatch != null) {
                totalSeconds += secondsMatch.groupValues[1].toInt()
            }
            
            if (totalSeconds > 0) {
                // Extract timer label
                val labelPattern = "(?:بعنوان|باسم|اسمه)\\s+\"([^\"]+)\""
                val labelMatch = Regex(labelPattern).find(lowerText)
                
                val timerLabel = if (labelMatch != null) {
                    labelMatch.groupValues[1]
                } else {
                    "مؤقت"
                }
                
                // Set the timer
                if (deviceController.setTimer(totalSeconds, timerLabel)) {
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    return "تم ضبط المؤقت لـ ${if (minutes > 0) "$minutes دقيقة " else ""}${if (seconds > 0) "$seconds ثانية" else ""}"
                } else {
                    return "حدث خطأ أثناء ضبط المؤقت."
                }
            } else {
                return "لم أفهم مدة المؤقت. يرجى تحديد المدة بوضوح، مثل: \"ضبط مؤقت 5 دقائق\""
            }
        }
        
        // System control commands
        
        // Toggle Wi-Fi
        if (lowerText.contains("شغل الواي فاي") || lowerText.contains("افتح الواي فاي") || lowerText.contains("فعل الواي فاي")) {
            if (deviceController.toggleWifi(true)) {
                return "تم تشغيل الواي فاي"
            } else {
                return "لم أتمكن من تشغيل الواي فاي. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        } else if (lowerText.contains("اغلق الواي فاي") || lowerText.contains("اطفئ الواي فاي") || lowerText.contains("عطل الواي فاي")) {
            if (deviceController.toggleWifi(false)) {
                return "تم إغلاق الواي فاي"
            } else {
                return "لم أتمكن من إغلاق الواي فاي. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        }
        
        // Toggle Bluetooth
        if (lowerText.contains("شغل البلوتوث") || lowerText.contains("افتح البلوتوث") || lowerText.contains("فعل البلوتوث")) {
            if (deviceController.toggleBluetooth(true)) {
                return "تم تشغيل البلوتوث"
            } else {
                return "لم أتمكن من تشغيل البلوتوث. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        } else if (lowerText.contains("اغلق البلوتوث") || lowerText.contains("اطفئ البلوتوث") || lowerText.contains("عطل البلوتوث")) {
            if (deviceController.toggleBluetooth(false)) {
                return "تم إغلاق البلوتوث"
            } else {
                return "لم أتمكن من إغلاق البلوتوث. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        }
        
        // Volume control
        if (lowerText.contains("ارفع الصوت") || lowerText.contains("زد الصوت")) {
            // Increase media volume
            deviceController.setVolume(AudioManager.STREAM_MUSIC, 15) // Set to max or higher
            return "تم رفع صوت الوسائط"
        } else if (lowerText.contains("اخفض الصوت") || lowerText.contains("قلل الصوت")) {
            // Decrease media volume
            deviceController.setVolume(AudioManager.STREAM_MUSIC, 5) // Set to mid-low
            return "تم خفض صوت الوسائط"
        } else if (lowerText.contains("كتم الصوت") || lowerText.contains("اكتم الصوت")) {
            // Mute sound
            deviceController.setVolume(AudioManager.STREAM_MUSIC, 0)
            return "تم كتم صوت الوسائط"
        }
        
        // Flashlight control
        if (lowerText.contains("شغل الفلاش") || lowerText.contains("شغل المصباح") || lowerText.contains("افتح الفلاش")) {
            if (deviceController.toggleFlashlight(true)) {
                return "تم تشغيل الفلاش"
            } else {
                return "لم أتمكن من تشغيل الفلاش. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        } else if (lowerText.contains("اغلق الفلاش") || lowerText.contains("اطفئ المصباح") || lowerText.contains("اطفئ الفلاش")) {
            if (deviceController.toggleFlashlight(false)) {
                return "تم إغلاق الفلاش"
            } else {
                return "لم أتمكن من إغلاق الفلاش. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        }
        
        // Take photo command
        if (lowerText.contains("التقط صورة") || lowerText.contains("خذ صورة") || 
            (lowerText.contains("صور") && !lowerText.contains("صورة للشاشة") && !lowerText.contains("سكرين شوت"))) {
            val photoUri = deviceController.capturePhoto()
            if (photoUri != null) {
                return "جاري فتح الكاميرا لالتقاط صورة..."
            } else {
                return "لم أتمكن من فتح الكاميرا. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        }
        
        // Take screenshot command
        if (lowerText.contains("التقط صورة للشاشة") || lowerText.contains("سكرين شوت") || 
            lowerText.contains("لقطة شاشة") || lowerText.contains("صورة الشاشة")) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    deviceController.takeScreenshot()
                }
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Error taking screenshot: ${e.message}")
                return "حدث خطأ أثناء محاولة التقاط صورة للشاشة. تأكد من منح التطبيق الأذونات المطلوبة."
            }
        }
        
        // Check for advanced UI automation commands
        val automationCommand = AutomationController.parseCommand(text)
        if (automationCommand != null) {
            // We identified an automation command, execute it
            try {
                val result = kotlinx.coroutines.runBlocking {
                    automationController.executeCommand(automationCommand)
                }
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Error executing automation command: ${e.message}")
                return "عذرًا، حدث خطأ أثناء تنفيذ الأمر: ${e.message}"
            }
        }
        
        // Check for internet related commands
        if (lowerText.contains("افتح") || lowerText.contains("تصفح") || 
            lowerText.contains("موقع") || lowerText.contains("ابحث عن")) {
            
            // Extract website or search query
            val query = extractSearchQuery(text)
            if (query.isNotEmpty()) {
                if (lowerText.contains("موقع") || lowerText.contains("تصفح") || 
                    query.startsWith("www.") || query.contains(".com") || query.contains(".net") || query.contains(".org")) {
                    // It's likely a website URL
                    var url = query
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    openWebsite(url)
                    return adaptResponseToEmotionalState("جاري فتح الموقع: $url")
                } else {
                    // It's likely a search query
                    searchOnGoogle(query)
                    return adaptResponseToEmotionalState("جاري البحث عن: $query")
                }
            }
        }
        
        // Check for app open requests
        if (lowerText.contains("افتح تطبيق") || lowerText.contains("شغل تطبيق")) {
            val appName = extractAppName(text)
            if (appName.isNotEmpty()) {
                // Try the automation controller first for better app launching
                val packageName = AutomationController.getPackageNameForApp(appName) ?: appName
                val automationCommand = AutomationCommand(CommandType.OPEN_APP, packageName)
                
                try {
                    val result = kotlinx.coroutines.runBlocking {
                        automationController.executeCommand(automationCommand)
                    }
                    return result
                } catch (e: Exception) {
                    // Fall back to the old method
                    val opened = openApp(appName)
                    return if (opened) {
                        adaptResponseToEmotionalState("تم فتح تطبيق $appName")
                    } else {
                        adaptResponseToEmotionalState("لم أتمكن من العثور على تطبيق باسم $appName")
                    }
                }
            }
        }
        
        // Check if the text contains a query for web content
        if (lowerText.contains("ما هو") || lowerText.contains("من هو") || 
            lowerText.contains("اخبرني عن") || lowerText.contains("معلومات عن")) {
            
            val query = extractSearchQuery(text)
            if (query.isNotEmpty()) {
                try {
                    val content = fetchWebContent(query)
                    if (content.isNotEmpty()) {
                        // تعديل الرد بناءً على الحالة العاطفية
                        return adaptResponseToEmotionalState(content)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching web content", e)
                    // Fall through to other handlers
                }
            }
        }
        
        // First check for simple patterns in our predefined responses
        for ((key, response) in predefinedResponses) {
            if (lowerText.contains(key.toLowerCase(Locale.getDefault()))) {
                return adaptResponseToEmotionalState(response)
            }
        }
        
        // If we have a working model, try using it
        interpreter?.let {
            try {
                // Simple word/intent classification
                val result = classifyText(text)
                if (result.first > WORD_THRESHOLD) {
                    return generateResponseBasedOnIntent(result.second, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing with TFLite", e)
                // Fall through to pattern matching if model inference fails
            }
        }
        
        // Handle dates and time patterns
        if (lowerText.contains("الوقت") || lowerText.contains("الساعة")) {
            return adaptResponseToEmotionalState("الوقت الحالي هو ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}")
        }
        
        if (lowerText.contains("التاريخ") || lowerText.contains("اليوم")) {
            val arabicDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale("ar"))
            val weekdayFormat = SimpleDateFormat("EEEE", Locale("ar"))
            val date = Date()
            return adaptResponseToEmotionalState("اليوم هو ${weekdayFormat.format(date)} والتاريخ هو ${arabicDateFormat.format(date)}")
        }
        
        // تحقق من طلب استعراض التفضيلات
        if (lowerText.contains("تفضيلاتي") || 
            lowerText.contains("ملفي الشخصي") ||
            lowerText.contains("ماذا تعرف عني") ||
            lowerText.contains("بياناتي") ||
            lowerText.contains("نمط حياتي") ||
            lowerText.contains("عاداتي")) {
            return getUserPreferencesReport()
        }
        
        // تحقق من طلب تحليل المحادثات
        if (lowerText.contains("حلل محادثاتنا") || 
            lowerText.contains("تحليل المحادثات") ||
            lowerText.contains("ماذا تعلمت من حواراتنا") ||
            lowerText.contains("مواضيع محادثاتنا")) {
            return getConversationAnalysisReport()
        }
        
        // تحقق من طلبات تنفيذ مهام معقدة تحتاج لتفكير منطقي
        if ((lowerText.contains("قم") || lowerText.contains("نفذ") || lowerText.contains("أنجز") ||
             lowerText.contains("اعمل") || lowerText.contains("اصنع") || lowerText.contains("أنشئ")) &&
            (lowerText.contains("خطة") || lowerText.contains("مهمة") || lowerText.contains("مشروع") ||
             lowerText.contains("عملية") || text.split(" ").size > 5)) {
            
            // استخدام محرك الاستدلال المنطقي لتنفيذ المهمة
            return executeLogicalTask(text)
        }
        
        // تحقق من طلبات تحليل نتائج فحوصات طبية
        if ((lowerText.contains("حلل") || lowerText.contains("فسر") || lowerText.contains("اشرح")) && 
            (lowerText.contains("تحليل") || lowerText.contains("فحص") || lowerText.contains("نتيجة") || 
             lowerText.contains("تحاليل") || lowerText.contains("فحوصات") || 
             lowerText.contains("مختبر") || lowerText.contains("دم"))) {
            
            return analyzeMedicalTest(text)
        }
        
        // تحقق من طلبات خاصة بتفضيلات اللغة
        if (lowerText.contains("اللهجة السودانية") && 
            (lowerText.contains("استخدم") || lowerText.contains("تحدث") || lowerText.contains("كلمني"))) {
            // تسجيل تفضيل استخدام اللهجة السودانية
            conversationAnalyzer.getLanguagePreferences().recordDialectUsage("sudanese", 10)
            return adaptResponseToEmotionalState("تم ضبط اللهجة السودانية كلهجة مفضلة. سأحاول استخدامها في ردودي معاك من هسع.")
        }
        
        // تحقق من طلبات التوصيات الذكية
        if (lowerText.contains("توصيات") || 
            lowerText.contains("اقترح") ||
            lowerText.contains("ماذا تقترح") ||
            lowerText.contains("نصائح") ||
            lowerText.contains("أفكار") ||
            (lowerText.contains("ماذا") && lowerText.contains("يجب") && lowerText.contains("أفعل"))) {
            return getSmartRecommendations()
        }
        
        // تفاعل مع توصية محددة
        val recommendationInteractionPattern = "(?:اختر|أختار|أريد|تفاعل مع|أعجبني|لا يعجبني) (?:التوصية|الاقتراح) (?:رقم |#)?(\\d+)".toRegex()
        val matchInteraction = recommendationInteractionPattern.find(lowerText)
        
        if (matchInteraction != null) {
            val recommendationIndex = matchInteraction.groupValues[1].toInt() - 1 // تحويل من 1-based إلى 0-based
            
            val interactionType = when {
                lowerText.contains("أعجبني") -> InteractionType.POSITIVE_FEEDBACK
                lowerText.contains("لا يعجبني") || lowerText.contains("سيء") -> InteractionType.NEGATIVE_FEEDBACK
                lowerText.contains("تجاهل") || lowerText.contains("تخطى") -> InteractionType.DISMISSED
                else -> InteractionType.CLICKED
            }
            
            return interactWithRecommendation(recommendationIndex, interactionType)
        }
        
        // If no matches, return a random fallback response adapted to emotional state
        return adaptResponseToEmotionalState(fallbackResponses.random())
    }
    
    /**
     * Extract search query from user text
     */
    private fun extractSearchQuery(text: String): String {
        val keywords = arrayOf("عن", "حول", "هو", "افتح", "تصفح", "موقع", "ابحث")
        var cleanText = text.toLowerCase()
        
        // Remove question words
        val questionWords = arrayOf("ما", "من", "متى", "اين", "كيف", "لماذا")
        for (word in questionWords) {
            cleanText = cleanText.replace("$word ", " ")
        }
        
        // Find the position after keywords
        var startPos = 0
        for (keyword in keywords) {
            val pos = cleanText.indexOf(keyword)
            if (pos >= 0 && pos + keyword.length + 1 < cleanText.length) {
                startPos = pos + keyword.length + 1
                break
            }
        }
        
        // Get the remaining text after the keyword
        var query = if (startPos > 0) cleanText.substring(startPos).trim() else cleanText.trim()
        
        // Remove common stop words
        val stopWords = arrayOf("من", "في", "على", "إلى", "عن", "مع", "هل", "ماذا", "كيف", "لماذا", "متى", "أين")
        for (word in stopWords) {
            query = query.replace(" $word ", " ")
        }
        
        return query.trim()
    }
    
    /**
     * Extract app name from user text
     */
    private fun extractAppName(text: String): String {
        val prefix = if (text.contains("افتح تطبيق")) "افتح تطبيق"
                    else if (text.contains("شغل تطبيق")) "شغل تطبيق"
                    else ""
                    
        return if (prefix.isNotEmpty()) {
            val startIndex = text.indexOf(prefix) + prefix.length
            text.substring(startIndex).trim()
        } else {
            ""
        }
    }
    
    /**
     * Open a website in browser
     */
    private fun openWebsite(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening website", e)
        }
    }
    
    /**
     * Search on Google
     */
    private fun searchOnGoogle(query: String) {
        try {
            val encodedQuery = Uri.encode(query)
            val url = "https://www.google.com/search?q=$encodedQuery"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching on Google", e)
        }
    }
    
    /**
     * Open an app by name
     */
    private fun openApp(appName: String): Boolean {
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        
        for (app in installedApps) {
            val label = packageManager.getApplicationLabel(app).toString()
            if (label.toLowerCase().contains(appName.toLowerCase())) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching app", e)
                }
            }
        }
        return false
    }
    
    /**
     * Fetch content from the web based on query
     */
    private suspend fun fetchWebContent(query: String): String {
        try {
            // Use the WebProcessorBridge for all web content processing
            return webProcessorBridge.getProcessedWebContent(query)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching web content", e)
            return adaptResponseToEmotionalState("لم أتمكن من العثور على معلومات عن \"$query\". يرجى تحديد استفسارك بشكل أكثر وضوحًا.")
        }
    }
    
    /**
     * Get weather information for a location
     */
    private suspend fun getWeatherInfo(location: String): String {
        return try {
            webProcessorBridge.getWeatherInfo(location)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting weather information", e)
            adaptResponseToEmotionalState("لم أتمكن من الحصول على معلومات الطقس لـ \"$location\".")
        }
    }
    
    /**
     * Get news information
     */
    private suspend fun getNewsInfo(category: String = ""): String {
        return try {
            webProcessorBridge.getNewsHeadlines(category)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting news information", e)
            adaptResponseToEmotionalState("لم أتمكن من الحصول على الأخبار الحالية.")
        }
    }
    


    /**
     * Classify the input text using the TFLite model
     * @return Pair of confidence score and predicted label
     */
    private fun classifyText(text: String): Pair<Float, String> {
        // Preprocess text - very simple preprocessing
        val inputBuffer = ByteBuffer.allocateDirect(MAX_SENTENCE_LENGTH * 4) // 4 bytes per float
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // Simple character-level encoding (very basic)
        val cleanText = text.take(MAX_SENTENCE_LENGTH)
        for (i in cleanText.indices) {
            inputBuffer.putFloat(cleanText[i].toInt().toFloat() / 255.0f)  // Simple char normalization
        }
        
        // Pad with zeros
        for (i in cleanText.length until MAX_SENTENCE_LENGTH) {
            inputBuffer.putFloat(0.0f)
        }
        
        inputBuffer.rewind()
        
        // Output buffer
        val outputBuffer = Array(1) { FloatArray(labelList.size) }
        
        // Run inference
        interpreter?.run(inputBuffer, outputBuffer)
        
        // Process results
        val results = PriorityQueue<Pair<Float, Int>>(MAX_RESULT_COUNT) { a, b ->
            b.first.compareTo(a.first)
        }
        
        for (i in outputBuffer[0].indices) {
            results.add(Pair(outputBuffer[0][i], i))
        }
        
        val topResult = results.poll()
        return if (topResult != null) {
            Pair(topResult.first, labelList[topResult.second])
        } else {
            Pair(0.0f, "unknown")
        }
    }

    /**
     * Generate response based on classified intent
     */
    private fun generateResponseBasedOnIntent(intent: String, originalText: String): String {
        // تجهيز الرد المبدئي بناءًا على القصد
        val initialResponse = when (intent) {
            "greeting" -> "مرحباً! كيف يمكنني مساعدتك اليوم؟"
            "question" -> "هذا سؤال مثير للاهتمام. بناءً على معلوماتي المحدودة، يمكنني القول أن هناك عدة جوانب لهذا الموضوع."
            "request" -> "سأحاول مساعدتك في هذا الطلب قدر المستطاع."
            "farewell" -> "مع السلامة! أتمنى لك يوماً سعيداً."
            "thank" -> "لا شكر على واجب! سعيد بمساعدتك."
            else -> {
                // Try to extract keywords and generate a response
                val keywords = extractKeywords(originalText)
                if (keywords.isNotEmpty()) {
                    "أفهم أنك تتحدث عن ${keywords.joinToString(", ")}. هل يمكنك إعطائي مزيداً من التفاصيل؟"
                } else {
                    fallbackResponses.random()
                }
            }
        }
        
        // تكييف الرد بناءً على الحالة العاطفية للمستخدم
        return adaptResponseToEmotionalState(initialResponse)
    }

    /**
     * Simple keyword extraction (very basic implementation)
     */
    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf("من", "في", "على", "إلى", "عن", "مع", "هل", "ما", "ماذا", "كيف", "لماذا", "متى", "أين", "من", "هو", "هي", "نحن", "هم")
        
        return text.split(" ")
            .filter { it.length > 3 && !stopWords.contains(it) }
            .distinct()
            .take(3)
    }

    /**
     * Load TFLite model from assets
     */
    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Load label list from assets
     */
    @Throws(IOException::class)
    private fun loadLabelList(context: Context): List<String> {
        val labels = ArrayList<String>()
        try {
            context.assets.open("labels.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    labels.add(line.trim())
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading label file", e)
            // Add some default labels in case loading fails
            labels.addAll(listOf("greeting", "question", "request", "farewell", "thank", "unknown"))
        }
        return labels
    }

    /**
     * Release resources when no longer needed
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        voiceToneAnalyzer.release()
    }
    
    /**
     * تحليل نبرة الصوت للمستخدم
     * يبدأ عملية تسجيل وتحليل للصوت لاكتشاف الحالة العاطفية
     */
    suspend fun analyzeVoiceTone(): String {
        return try {
            // تسجيل الصوت لمدة 5 ثوان وتحليله
            emotionalState = voiceToneAnalyzer.analyzeVoice(5000)
            emotionalStateConfidence = voiceToneAnalyzer.confidence.value
            
            Log.d(TAG, "Voice tone analysis completed: ${emotionalState.name} (${emotionalStateConfidence})")
            
            // إعداد الرد بناءً على نتيجة التحليل
            val confidence = when {
                emotionalStateConfidence > 0.8f -> "عالية جدًا"
                emotionalStateConfidence > 0.6f -> "عالية"
                emotionalStateConfidence > 0.4f -> "متوسطة"
                else -> "منخفضة"
            }
            
            "بناءً على تحليل نبرة صوتك، يبدو أنك ${getEmotionDescription(emotionalState)} بدرجة ثقة $confidence.\n\n" +
            "يمكنني تكييف ردودي لتتناسب مع حالتك العاطفية لتقديم تجربة أكثر تخصيصًا. لاحظ أن هذا التحليل يتم محليًا على جهازك ولا يتم إرسال أي بيانات صوتية إلى الإنترنت."
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during voice tone analysis", e)
            "عذرًا، لم أتمكن من إجراء تحليل لنبرة صوتك. يرجى التأكد من منح إذن استخدام الميكروفون والمحاولة مرة أخرى."
        }
    }
    
    /**
     * الحصول على وصف للحالة العاطفية باللغة العربية
     */
    private fun getEmotionDescription(emotionalState: VoiceToneAnalyzer.EmotionalState): String {
        return when (emotionalState) {
            VoiceToneAnalyzer.EmotionalState.NEUTRAL -> "في حالة محايدة"
            VoiceToneAnalyzer.EmotionalState.HAPPY -> "سعيد ومبتهج"
            VoiceToneAnalyzer.EmotionalState.SAD -> "حزين أو مكتئب"
            VoiceToneAnalyzer.EmotionalState.ANGRY -> "غاضب أو منفعل"
            VoiceToneAnalyzer.EmotionalState.FEARFUL -> "خائف أو قلق"
            VoiceToneAnalyzer.EmotionalState.SURPRISED -> "متفاجئ أو مندهش"
            VoiceToneAnalyzer.EmotionalState.CALM -> "هادئ ومسترخي"
        }
    }
    
    /**
     * الحصول على تفضيلات المستخدم وبيانات نمط حياته
     */
    /**
     * تحليل المحادثات وإنشاء تقرير عن التفاعلات والمواضيع المكتشفة
     */
    private suspend fun getConversationAnalysisReport(): String {
        try {
            // تحليل المحادثات بشكل متزامن
            val conversationInsights = conversationAnalyzer.analyzeConversationChain()
            
            // الحصول على المواضيع المكتشفة
            val topics = conversationAnalyzer.getDiscoveredTopics()
            
            // الحصول على تفضيلات اللغة
            val languagePreferences = conversationAnalyzer.getLanguagePreferences()
            
            // بناء التقرير
            val report = StringBuilder()
            
            // إضافة مقدمة
            report.append("بناءً على تحليل محادثاتنا السابقة، وجدت المعلومات التالية:\n\n")
            
            // إضافة المواضيع التي تمت مناقشتها
            if (conversationInsights.topics.isNotEmpty()) {
                report.append("🔷 المواضيع التي تهتم بها:\n")
                
                conversationInsights.topics.take(5).forEach { topic ->
                    report.append("• $topic\n")
                }
                report.append("\n")
            }
            
            // إضافة أنماط الأسئلة
            if (conversationInsights.questionPatterns.isNotEmpty()) {
                report.append("🔷 أنماط الأسئلة التي تستخدمها غالباً:\n")
                
                conversationInsights.questionPatterns.take(3).forEach { pattern ->
                    report.append("• $pattern\n")
                }
                report.append("\n")
            }
            
            // إضافة معلومات عن السياق الأكثر شيوعاً
            report.append("🔷 تفاعلاتك غالباً ما تكون في سياق: ${conversationInsights.conversationContext}\n\n")
            
            // إضافة معلومات عن الحالة العاطفية السائدة
            val emotionalStateArabic = when(conversationInsights.dominantEmotionalState) {
                "neutral" -> "محايدة"
                "happy" -> "سعيدة"
                "sad" -> "حزينة"
                "angry" -> "غاضبة"
                "fearful" -> "قلقة"
                "surprised" -> "متفاجئة"
                "calm" -> "هادئة"
                else -> conversationInsights.dominantEmotionalState
            }
            report.append("🔷 حالتك العاطفية الغالبة أثناء محادثاتنا: $emotionalStateArabic\n\n")
            
            // إضافة معلومات عن وقت المحادثة المفضل
            report.append("🔷 أوقات المحادثة المفضلة لديك: ${conversationInsights.conversationTimeOfDay}\n\n")
            
            // إضافة معلومات عن تفضيلات اللغة إذا وجدت
            if (languagePreferences.hasLearnedPreferences()) {
                report.append("🔷 تفضيلات اللغة التي تعلمتها منك:\n")
                
                // طول الجملة المفضل
                val sentenceLength = languagePreferences.getPreferredSentenceLength()
                if (sentenceLength > 0) {
                    val lengthDescription = when {
                        sentenceLength < 40 -> "قصيرة"
                        sentenceLength < 80 -> "متوسطة"
                        else -> "طويلة"
                    }
                    report.append("• تفضل الجمل ال$lengthDescription (حوالي $sentenceLength حرف)\n")
                }
                
                // اللهجة المفضلة
                val preferredDialect = languagePreferences.getPreferredDialect()
                if (preferredDialect.isNotEmpty()) {
                    val dialectName = when(preferredDialect) {
                        "sudanese" -> "اللهجة السودانية"
                        else -> preferredDialect
                    }
                    report.append("• تفضل استخدام $dialectName\n")
                }
            }
            
            return adaptResponseToEmotionalState(report.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating conversation analysis report", e)
            return adaptResponseToEmotionalState("عذراً، واجهت مشكلة في تحليل محادثاتنا السابقة. يرجى المحاولة مرة أخرى لاحقاً.")
        }
    }
    
    suspend fun getUserPreferencesReport(): String {
        try {
            // جمع البيانات عن المستخدم
            val userData = StringBuilder()
            
            // المعلومات الأساسية
            userData.append("ملخص تفضيلاتك ونمط حياتك:\n\n")
            
            // تحليل التطبيقات الأكثر استخداماً
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val appUsage = behaviorAnalyzer.analyzeAppUsage(7)
                if (appUsage.isNotEmpty()) {
                    userData.append("التطبيقات المفضلة (بناءً على الاستخدام):\n")
                    appUsage.entries
                        .sortedByDescending { it.value.totalTimeMs }
                        .take(5)
                        .forEachIndexed { index, entry ->
                            val appName = entry.key.split(".").last()
                            userData.append("${index + 1}. $appName (${entry.value.getFormattedTime()})\n")
                        }
                    userData.append("\n")
                }
            }
            
            // أوقات النشاط
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val hourlyUsage = behaviorAnalyzer.analyzeUsageByTimeOfDay(7)
                if (hourlyUsage.isNotEmpty()) {
                    val peakHours = hourlyUsage.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { it.key }
                    
                    userData.append("أوقات النشاط المفضلة: ")
                    userData.append(peakHours.joinToString(", ") { "${formatHour(it)}" })
                    userData.append("\n\n")
                }
            }
            
            // أنماط النوم
            val sleepData = userProfileManager.analyzeHealthTrend("sleep", 7)
            if (sleepData.isNotEmpty()) {
                val avgSleep = sleepData.sumByDouble { (it.second as? Double) ?: 0.0 } / sleepData.size
                userData.append("متوسط النوم: ${String.format("%.1f", avgSleep)} ساعة يومياً\n")
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val sleepPattern = behaviorAnalyzer.analyzeSleepPattern(7)
                    if (sleepPattern.averageSleepStartHour > 0) {
                        val sleepTime = formatHour(sleepPattern.averageSleepStartHour)
                        userData.append("وقت النوم المعتاد: حوالي $sleepTime\n\n")
                    }
                }
            }
            
            // الاهتمامات
            val interests = behaviorAnalyzer.analyzeInterests()
            if (interests.isNotEmpty()) {
                userData.append("الاهتمامات الرئيسية:\n")
                interests.take(7).forEachIndexed { index, interest ->
                    userData.append("${index + 1}. ${interest.keyword}\n")
                }
                userData.append("\n")
            }
            
            // التفضيلات المسجلة
            val allPreferences = userProfileManager.getAllPreferences()
            if (allPreferences.isNotEmpty()) {
                userData.append("تفضيلات أخرى:\n")
                
                // تصفية وتجميع التفضيلات
                val filteredPreferences = allPreferences
                    .filter { !it.key.startsWith("system:") }
                    .entries
                    .groupBy { it.key.split(":").first() }
                
                filteredPreferences.forEach { (category, prefs) ->
                    userData.append("${translateCategory(category)}: ")
                    userData.append(prefs.take(3).joinToString(", ") { it.value.toString() })
                    userData.append("\n")
                }
            }
            
            return adaptResponseToEmotionalState(userData.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error generating user preferences report", e)
            return adaptResponseToEmotionalState("عذرًا، حدث خطأ أثناء استرجاع تفضيلاتك. سأعمل على تحسين هذه الميزة.")
        }
    }
    
    /**
     * تنسيق عرض الساعة
     */
    private fun formatHour(hour: Int): String {
        val formattedHour = when {
            hour == 0 -> "12 منتصف الليل"
            hour < 12 -> "$hour صباحًا"
            hour == 12 -> "12 ظهرًا"
            else -> "${hour - 12} مساءً"
        }
        return formattedHour
    }
    
    /**
     * ترجمة فئات التفضيلات
     */
    private fun translateCategory(category: String): String {
        return when (category) {
            "food" -> "الطعام"
            "entertainment" -> "الترفيه"
            "health" -> "الصحة"
            "productivity" -> "الإنتاجية"
            "learning" -> "التعلم"
            "social" -> "التواصل الاجتماعي"
            "news" -> "الأخبار"
            "finance" -> "المالية"
            "apps" -> "التطبيقات"
            "music" -> "الموسيقى"
            "travel" -> "السفر"
            "exercise" -> "الرياضة"
            else -> category
        }
    }
    
    /**
     * الحصول على توصيات ذكية للمستخدم بناءً على وقت اليوم والحالة العاطفية والسياق
     */
    suspend fun getSmartRecommendations(): String {
        try {
            // إنشاء سياق التوصية
            val timeOfDay = TimeOfDay() // الوقت الحالي
            
            // استخراج الحالة العاطفية
            val currentEmotionalState = emotionalState.name.toLowerCase()
            
            // إنشاء سياق التوصية
            val context = RecommendationContext(
                timeOfDay = timeOfDay,
                location = null, // يمكن إضافة الموقع الحالي لاحقاً
                activityType = null, // يمكن استنتاج النشاط من الاستشعار لاحقًا
                currentApp = null // يمكن تحديد التطبيق الحالي لاحقًا
            )
            
            // الحصول على التوصيات
            val recommendations = recommendationEngine.generateRecommendation(context)
            
            if (recommendations.isEmpty()) {
                return adaptResponseToEmotionalState("لم أتمكن من إنشاء توصيات مخصصة لك في الوقت الحالي. يرجى المحاولة لاحقًا.")
            }
            
            // بناء الرد
            val responseBuilder = StringBuilder()
            responseBuilder.append("إليك بعض التوصيات المخصصة بناءً على ملفك الشخصي ووقت اليوم:\n\n")
            
            // إضافة التوصيات
            recommendations.forEachIndexed { index, recommendation ->
                responseBuilder.append("${index + 1}. ${recommendation.title}\n")
                responseBuilder.append("   ${recommendation.description}\n")
                
                if (recommendation.actionLabel != null && recommendation.actionUrl != null) {
                    responseBuilder.append("   [${recommendation.actionLabel}]\n")
                }
                
                responseBuilder.append("\n")
                
                // تسجيل أن المستخدم شاهد التوصية
                recommendationEngine.recordRecommendationFeedback(
                    recommendation,
                    InteractionType.VIEWED_ONLY
                )
            }
            
            return adaptResponseToEmotionalState(responseBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error generating recommendations", e)
            return adaptResponseToEmotionalState("عذرًا، حدث خطأ أثناء إنشاء التوصيات. سأعمل على تحسين هذه الميزة.")
        }
    }
    
    /**
     * تحليل وتفسير نتائج التحاليل الطبية
     * @param text نص الطلب من المستخدم
     * @return تفسير للتحاليل الطبية
     */
    private suspend fun analyzeMedicalTest(text: String): String {
        try {
            val lowerText = text.toLowerCase(Locale.getDefault())
            
            // البحث عن قيم رقمية محتملة للتحليل
            val labTestPattern = "(?:قيمة|نتيجة|مستوى)\\s+([\\p{L}\\s\\d]+)\\s*(?:هي|هو|:)\\s*(\\d+\\.?\\d*)\\s*([a-zA-Z%\\/%\\^\\d\\u0621-\\u064A]+)?".toRegex()
            val matchResult = labTestPattern.find(lowerText)
            
            if (matchResult != null) {
                // استخلاص المعلومات من النص
                val testName = matchResult.groupValues[1].trim()
                val value = matchResult.groupValues[2].toDoubleOrNull() ?: 0.0
                val unit = matchResult.groupValues[3].trim()
                
                // البحث عن النطاق الطبيعي في النص
                val rangePattern = "(?:النطاق الطبيعي|المعدل الطبيعي)\\s*(?:هو|:)?\\s*(\\d+\\.?\\d*)\\s*(?:إلى|-|~|الى)\\s*(\\d+\\.?\\d*)".toRegex()
                val rangeMatch = rangePattern.find(lowerText)
                
                val normalRangeStart = if (rangeMatch != null) {
                    rangeMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                } else 0.0
                
                val normalRangeEnd = if (rangeMatch != null) {
                    rangeMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                } else 0.0
                
                val normalRange = normalRangeStart..normalRangeEnd
                
                // تحليل وتفسير النتيجة
                val interpretation = healthTracker.interpretLabTest(testName, value, unit, normalRange)
                
                // تسجيل النتيجة في التاريخ الصحي للمستخدم
                if (normalRangeStart > 0 && normalRangeEnd > 0) {
                    healthTracker.recordLabTest(
                        labTestName = testName,
                        value = value,
                        unit = unit,
                        minRange = normalRangeStart,
                        maxRange = normalRangeEnd,
                        category = "تحليل مدخل يدويا",
                        notes = "تم إدخال النتيجة من قبل المستخدم"
                    )
                }
                
                return adaptResponseToEmotionalState(interpretation)
            } else {
                // إذا لم يتم العثور على معلومات محددة، نرجع تقرير عام
                val healthReport = healthTracker.generateHealthReport()
                
                if (healthReport.labResults.isEmpty()) {
                    return adaptResponseToEmotionalState(
                        "لم أتمكن من العثور على نتائج تحاليل مسجلة في النظام. " +
                        "لتحليل نتيجة تحليل طبي، يمكنك إخباري بنوع التحليل وقيمته والنطاق الطبيعي له مثل:\n\n" +
                        "\"نتيجة تحليل السكر هي 120 mg/dL والنطاق الطبيعي هو 70 إلى 100\""
                    )
                }
                
                // بناء رد بناءً على التحاليل المخزنة
                val responseBuilder = StringBuilder()
                
                responseBuilder.append("بناءً على نتائج تحاليلك المسجلة، إليك تقييمي:\n\n")
                
                var hasAbnormalResults = false
                
                // تجميع التحاليل حسب الفئة
                val groupedResults = healthReport.labResults.values.groupBy { it.category }
                
                for ((category, tests) in groupedResults) {
                    responseBuilder.append("🔍 $category:\n")
                    
                    for (test in tests) {
                        val status = when {
                            test.value < test.normalRange.start -> "⚠️ منخفض"
                            test.value > test.normalRange.endInclusive -> "⚠️ مرتفع"
                            else -> "✅ طبيعي"
                        }
                        
                        responseBuilder.append("- ${test.name}: ${test.value} ${test.unit} [$status]\n")
                        
                        if (status.contains("⚠️")) {
                            hasAbnormalResults = true
                        }
                    }
                    
                    responseBuilder.append("\n")
                }
                
                // إضافة التوصيات
                if (healthReport.recommendations.isNotEmpty()) {
                    responseBuilder.append("📋 التوصيات:\n")
                    for (recommendation in healthReport.recommendations) {
                        responseBuilder.append("- $recommendation\n")
                    }
                }
                
                // إضافة نصيحة عامة
                if (hasAbnormalResults) {
                    responseBuilder.append("\nملاحظة هامة: يبدو أن بعض نتائج التحاليل خارج النطاق الطبيعي. أنصحك بمراجعة الطبيب لتقييم هذه النتائج بشكل أفضل.")
                } else {
                    responseBuilder.append("\nبشكل عام، تبدو نتائج تحاليلك في المعدل الطبيعي. استمر بالمحافظة على نمط حياة صحي.")
                }
                
                return adaptResponseToEmotionalState(responseBuilder.toString())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل نتائج التحاليل الطبية", e)
            return adaptResponseToEmotionalState(
                "عذراً، واجهت مشكلة في تحليل نتائج التحاليل الطبية. " +
                "يرجى توضيح نوع التحليل وقيمته والنطاق الطبيعي له بشكل أكثر تفصيلاً."
            )
        }
    }
    
    /**
     * تنفيذ مهمة باستخدام محرك الاستدلال المنطقي
     * @param taskDescription وصف المهمة المطلوبة
     * @return الرد على المستخدم
     */
    private suspend fun executeLogicalTask(taskDescription: String): String {
        try {
            val emotionalState = if (::emotionalState.isInitialized) emotionalState else VoiceToneAnalyzer.EmotionalState.NEUTRAL
            
            // إنشاء رسالة ترحيبية تناسب المهمة
            val welcomeMessage = when (emotionalState) {
                VoiceToneAnalyzer.EmotionalState.HAPPY -> "سأقوم بتنفيذ هذه المهمة بكل سرور! أعطني لحظة للتفكير..."
                VoiceToneAnalyzer.EmotionalState.NEUTRAL -> "سأقوم بمساعدتك في هذه المهمة. دعني أفكر في الأمر..."
                VoiceToneAnalyzer.EmotionalState.SAD -> "سأحاول مساعدتك في تنفيذ هذه المهمة بالرغم من التحديات. دعني أفكر..."
                VoiceToneAnalyzer.EmotionalState.ANGRY -> "سأقوم بمساعدتك في هذه المهمة. دعنا نركز معاً على إيجاد الحل..."
                VoiceToneAnalyzer.EmotionalState.FEARFUL -> "سأساعدك خطوة بخطوة في تنفيذ هذه المهمة. لا تقلق..."
                VoiceToneAnalyzer.EmotionalState.SURPRISED -> "مهمة مثيرة للاهتمام! دعني أفكر في أفضل طريقة للتنفيذ..."
                else -> "سأقوم بمساعدتك في تنفيذ هذه المهمة. دعني أفكر في الأمر..."
            }
            
            // إنشاء الخطة
            val plan = logicalReasoningEngine.createPlan(taskDescription)
            
            // إنشاء توضيح للخطة
            val planExplanation = StringBuilder()
            planExplanation.append("\nلتنفيذ مهمة \"${plan.title}\"، سأتبع الخطة التالية:\n\n")
            
            plan.steps.sortedBy { it.orderId }.forEachIndexed { index, step ->
                planExplanation.append("${index + 1}. ${step.title}: ${step.description}\n")
            }
            
            // تنفيذ الخطة
            val executionResult = logicalReasoningEngine.executePlan(plan)
            
            // إنشاء الرد النهائي
            return if (executionResult.success) {
                "$welcomeMessage\n$planExplanation\n✅ تم تنفيذ المهمة بنجاح!\n\n${executionResult.summary}"
            } else {
                "$welcomeMessage\n$planExplanation\n❌ واجهت بعض الصعوبات في تنفيذ المهمة:\n\n${executionResult.summary}"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing logical task", e)
            return "حدث خطأ أثناء محاولة تنفيذ هذه المهمة: ${e.message}"
        }
    }
    
    /**
     * التفاعل مع توصية محددة
     */
    suspend fun interactWithRecommendation(recommendationIndex: Int, interactionType: InteractionType): String {
        try {
            // نحتاج إلى تخزين التوصيات السابقة لإمكانية التفاعل معها
            // يمكن تنفيذ ذلك بطريقة أكثر تطوراً باستخدام قاعدة بيانات
            // للآن، يمكننا إعادة توليد التوصيات أو تخزينها مؤقتًا
            
            val context = RecommendationContext(timeOfDay = TimeOfDay())
            val recommendations = recommendationEngine.generateRecommendation(context)
            
            if (recommendations.isEmpty() || recommendationIndex >= recommendations.size) {
                return adaptResponseToEmotionalState("لم أتمكن من العثور على التوصية المحددة. يرجى طلب توصيات جديدة.")
            }
            
            val recommendation = recommendations[recommendationIndex]
            
            // تسجيل التفاعل
            recommendationEngine.recordRecommendationFeedback(recommendation, interactionType)
            
            // الرد بناءً على نوع التفاعل
            val response = when (interactionType) {
                InteractionType.CLICKED -> {
                    if (recommendation.actionUrl != null) {
                        // يمكن هنا فتح التطبيق أو الرابط المطلوب
                        "تم تنفيذ الإجراء: ${recommendation.title}. سأتعلم من تفضيلاتك لتحسين التوصيات المستقبلية."
                    } else {
                        "تم تسجيل اهتمامك بـ: ${recommendation.title}. سأقدم المزيد من التوصيات المشابهة في المستقبل."
                    }
                }
                InteractionType.POSITIVE_FEEDBACK -> 
                    "شكراً على رأيك الإيجابي! سأقترح المزيد من التوصيات المشابهة لـ \"${recommendation.title}\" في المستقبل."
                InteractionType.NEGATIVE_FEEDBACK -> 
                    "شكراً على ملاحظاتك. سأتجنب تقديم توصيات مشابهة لـ \"${recommendation.title}\" في المستقبل."
                InteractionType.DISMISSED -> 
                    "تم تجاهل التوصية. سأحاول تقديم توصيات أكثر ملاءمة في المرة القادمة."
                else -> 
                    "تم تسجيل تفاعلك مع التوصية."
            }
            
            return adaptResponseToEmotionalState(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error interacting with recommendation", e)
            return adaptResponseToEmotionalState("عذرًا، حدث خطأ أثناء التفاعل مع التوصية.")
        }
    }
    
    /**
     * تعديل أسلوب الرد بناءً على الحالة العاطفية وتفضيلات المستخدم اللغوية
     * يضيف تعبيرات ومشاعر تناسب حالة المستخدم، ويكيف اللغة حسب تفضيلاته
     */
    private fun adaptResponseToEmotionalState(response: String): String {
        var adaptedResponse = response
        
        // إضافة تعبيرات عاطفية إذا كانت ثقة تحليل الحالة العاطفية كافية
        if (emotionalStateConfidence >= 0.5f) {
            val prefix = when (emotionalState) {
                VoiceToneAnalyzer.EmotionalState.HAPPY -> listOf(
                    "يسعدني أن أرى حماسك! ",
                    "أشعر بإيجابيتك. ",
                    "يبدو أنك في مزاج رائع! "
                ).random()
                
                VoiceToneAnalyzer.EmotionalState.SAD -> listOf(
                    "أتفهم شعورك بالحزن. ",
                    "أنا هنا للمساعدة خلال هذا الوقت الصعب. ",
                    "آسف لسماع ذلك. "
                ).random()
                
                VoiceToneAnalyzer.EmotionalState.ANGRY -> listOf(
                    "أتفهم شعورك بالإحباط. ",
                    "أرى أنك منزعج، دعني أحاول مساعدتك. ",
                    "أنا آسف إذا كنت قد أزعجتك. "
                ).random()
                
                VoiceToneAnalyzer.EmotionalState.FEARFUL -> listOf(
                    "لا داعي للقلق. ",
                    "أنا هنا للمساعدة، لا تقلق. ",
                    "دعني أساعدك في التعامل مع هذا الأمر. "
                ).random()
                
                VoiceToneAnalyzer.EmotionalState.SURPRISED -> listOf(
                    "أرى أنك متفاجئ! ",
                    "هذا مدهش، أليس كذلك؟ ",
                    "أفهم اندهاشك. "
                ).random()
                
                VoiceToneAnalyzer.EmotionalState.CALM -> listOf(
                    "أقدر هدوءك. ",
                    "يبدو أنك مسترخٍ ومتزن. ",
                    "أنت تتعامل مع الأمور بتركيز جيد. "
                ).random()
                
                else -> ""
            }
            
            if (prefix.isNotEmpty()) {
                adaptedResponse = prefix + adaptedResponse
            }
        }
        
        // تكييف اللغة حسب تفضيلات المستخدم اللغوية
        try {
            val languagePreferences = conversationAnalyzer.getLanguagePreferences()
            if (languagePreferences.hasLearnedPreferences()) {
                adaptedResponse = conversationAnalyzer.adaptTextToUserPreferences(adaptedResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adapting text to user language preferences", e)
        }
        
        return adaptedResponse
    }

    /**
     * تعديل جميع الردود النهائية لتتضمن الحالة العاطفية للمستخدم
     * هذه الدالة تستخدم في دالة processText لمعالجة جميع الردود
     */
    override fun toString(): String {
        // هذه الدالة تستبدل بالدالة toString الافتراضية لتسهيل استخدامها في معالجة النصوص
        return "AIProcessor"
    }

    companion object {
        private const val TAG = "AIProcessor"
    }
}
