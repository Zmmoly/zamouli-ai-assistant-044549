package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.intelliai.assistant.SpeechRecognizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), HotwordDetectionService.HotwordListener {
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var aiProcessor: AIProcessor
    private lateinit var adaptiveLearningEngine: AdaptiveLearningEngine
    private lateinit var modelManager: ModelManager
    private val messageList = mutableListOf<MessageItem>()
    
    // Speech components
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var isSpeechEnabled = false
    private var isHotwordDetectionEnabled = false
    private var hotwordServiceIntent: Intent? = null

    // Permission request codes
    private val INTERNET_PERMISSION_REQUEST_CODE = 1001
    private val SPEECH_PERMISSION_REQUEST_CODE = 1002
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1003
    private val BLUETOOTH_PERMISSION_REQUEST_CODE = 1004
    private val FOREGROUND_SERVICE_PERMISSION_REQUEST_CODE = 1005
    private val SCREENSHOT_REQUEST_CODE = 2001
    
    // Permission manager instance
    private lateinit var permissionManager: PermissionManager
    
    // UI Automator instance
    private lateinit var uiAutomator: UIAutomator
    
    // تم إعلان هذا المتغير مسبقاً
    
    // إضافة مدير الذاكرة
    private lateinit var memoryManager: MemoryManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // تهيئة مدير الذاكرة وتكوين الذاكرة بناءً على الجهاز
        MemoryManager.initialize(applicationContext) // تهيئة المثيل الوحيد (Singleton)
        memoryManager = MemoryManager.getInstance()
        memoryManager.configureMemory()
        
        // تهيئة مدير النماذج
        modelManager = ModelManager(this)
        
        // التحقق من الأذونات وطلبها
        checkAndRequestPermissions()

        // تهيئة معالج الذكاء الاصطناعي
        aiProcessor = AIProcessor(applicationContext)
        
        // تهيئة وحدة التحكم بواجهة المستخدم
        uiAutomator = UIAutomator(applicationContext)
        
        // تهيئة محرك التعلم التكيفي
        adaptiveLearningEngine = AdaptiveLearningEngine(applicationContext)
        // بدء تهيئة محرك التعلم في الخلفية
        CoroutineScope(Dispatchers.IO).launch {
            adaptiveLearningEngine.initialize()
        }
        
        // تهيئة خدمة الكشف عن الكلمة المفتاحية
        setupHotwordDetection()

        // Set up UI components
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        recyclerView = findViewById(R.id.recyclerView)

        // Set up RecyclerView with adapter
        chatAdapter = ChatAdapter(messageList)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Audio controls for voice analysis
        val micButton = findViewById<ImageButton>(R.id.micButton)
        
        // Set up mic button for voice tone analysis
        micButton.setOnClickListener {
            startSpeechRecognition()
        }
        
        // Handle long press on mic button for voice tone analysis
        micButton.setOnLongClickListener {
            Toast.makeText(
                this,
                "جاري تحليل نبرة صوتك...",
                Toast.LENGTH_SHORT
            ).show()
            
            // Send voice analysis command
            addMessage("حلل نبرة صوتي", true)
            processUserMessage("حلل نبرة صوتي")
            true
        }
        
        // Add welcome message
        addMessage("مرحباً بك في مساعد الذكاء الاصطناعي المتطور! الآن يمكنني التحكم الكامل في هاتفك بما في ذلك فتح التطبيقات، إجراء المكالمات، إرسال الرسائل، وتحليل نبرة صوتك. كيف يمكنني مساعدتك اليوم؟", false)

        // Set up send button click listener
        sendButton.setOnClickListener {
            sendMessage()
        }

        // Set up keyboard send action
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                return@setOnEditorActionListener true
            }
            false
        }
        
        // Check if advanced permissions are needed
        checkAdvancedPermissions()
    }
    
    /**
     * Check for advanced automation permissions
     */
    private fun checkAdvancedPermissions() {
        // Check if accessibility service is enabled
        if (!uiAutomator.isAutomationServiceConnected()) {
            // Show dialog to enable accessibility service
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_accessibility_service))
                .setMessage(getString(R.string.enable_accessibility_prompt))
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open accessibility settings
                    uiAutomator.openAccessibilitySettings()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check if notification listener service is enabled
        if (!NotificationListenerService.isServiceConnected()) {
            // Show dialog to enable notification listener service
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_notification_access))
                .setMessage(getString(R.string.enable_notification_prompt))
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open notification settings
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check for overlay permission
        if (!Settings.canDrawOverlays(this)) {
            // Show dialog to enable overlay permission
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required))
                .setMessage("يحتاج التطبيق إلى إذن العرض فوق التطبيقات الأخرى للتحكم الكامل في الهاتف")
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open overlay settings
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check for screen capture permission
        requestScreenCapturePermission()
    }
    
    /**
     * Request screen capture permission
     */
    private fun requestScreenCapturePermission() {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(intent, SCREENSHOT_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting screen capture: ${e.message}")
        }
    }
    
    /**
     * Handle activity result (for screen capture permission)
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == SCREENSHOT_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Store screen capture intent for later use
                ScreenCaptureService.screenCaptureIntent = data
                Log.d("MainActivity", "Screen capture permission granted")
            } else {
                Log.e("MainActivity", "Screen capture permission denied")
                Toast.makeText(
                    this,
                    "لم تتم الموافقة على إذن التقاط الشاشة. بعض الوظائف قد لا تعمل.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Check and request necessary permissions
     */
    /**
     * بدء التعرف على الكلام
     */
    private fun startSpeechRecognition() {
        try {
            // التحقق من وجود إذن الميكروفون
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // طلب إذن الميكروفون
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
                return
            }
            
            // بدء التعرف على الكلام
            speechRecognizer = SpeechRecognizer(this, object : SpeechRecognizer.SpeechRecognizerCallback {
                override fun onResult(text: String) {
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            messageInput.setText(text)
                        }
                    }
                }
                
                override fun onError(errorMessage: String) {
                    Log.e("MainActivity", "Speech recognition error: $errorMessage")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "حدث خطأ في التعرف على الكلام: $errorMessage",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
            
            speechRecognizer?.startListening()
            
            Toast.makeText(
                this,
                "تحدث الآن...",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting speech recognition", e)
            Toast.makeText(
                this,
                "فشل بدء التعرف على الكلام",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = ArrayList<String>()
        
        // Check for internet permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.INTERNET)
        }
        
        // Check for network state permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_NETWORK_STATE)
        }
        
        // Check for microphone permission (essential for voice recognition)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        
        // Check for foreground service permission (for constant hotword detection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE)
            }
        }
        
        // Check for phone call permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        
        // Check for SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.SEND_SMS)
        }
        
        // Check for contacts permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }
        
        // Check for calendar permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALENDAR)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_CALENDAR)
        }
        
        // Check for storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        // Check for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        
        // Check for location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Request permissions if needed
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                INTERNET_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            INTERNET_PERMISSION_REQUEST_CODE -> {
                // Check if all permissions were granted
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // All permissions granted, continue with app initialization
                    Toast.makeText(
                        this,
                        "تم منح جميع الأذونات المطلوبة",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Permissions denied, show dialog explaining why permissions are needed
                    showPermissionDeniedDialog()
                }
                return
            }
        }
    }
    
    /**
     * Show dialog explaining why permissions are needed
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("أذونات مطلوبة")
            .setMessage("يحتاج التطبيق إلى مجموعة من الأذونات للتحكم الكامل في الهاتف مثل إجراء المكالمات، والرسائل النصية، والوصول إلى جهات الاتصال، والتقويم، والكاميرا، والموقع، وإعدادات النظام. يرجى منح الأذونات المطلوبة من إعدادات التطبيق.")
            .setPositiveButton("الإعدادات") { _, _ ->
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "لن تعمل وظائف التطبيق بشكل كامل بدون الأذونات المطلوبة",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isNotEmpty()) {
            // Add user message to the chat
            addMessage(message, true)
            
            // Clear input field
            messageInput.setText("")
            
            // Process message with AI
            processMessageWithAI(message)
        }
    }

    private fun processMessageWithAI(message: String) {
        // Use coroutine to process message in background
        
        // Initialize automation components if not already done
        initializeAutomationComponents()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val loadingMessage = MessageItem("جاري التفكير...", false, System.currentTimeMillis())
                messageList.add(loadingMessage)
                chatAdapter.notifyItemInserted(messageList.size - 1)
                recyclerView.scrollToPosition(messageList.size - 1)
                
                // التحقق من طلبات التعلم التلقائي
                if (isAutonomousLearningRequest(message)) {
                    // معالجة طلب التعلم التلقائي
                    handleAutonomousLearningRequest(message, loadingMessage)
                    return@launch
                }
                
                // معالجة الرسالة باستخدام المعالج
                val response = withContext(Dispatchers.IO) {
                    aiProcessor.processText(message)
                }
                
                // إزالة رسالة التحميل
                messageList.remove(loadingMessage)
                chatAdapter.notifyDataSetChanged()
                
                // إضافة رد الذكاء الاصطناعي إلى المحادثة
                addMessage(response, false)
                
                // التحقق من أوامر التشغيل التلقائي
                checkAndExecuteAutomationCommands(message)
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "خطأ في معالجة الرسالة: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * التحقق من أوامر التشغيل التلقائي وتنفيذها
     */
    private suspend fun checkAndExecuteAutomationCommands(message: String) {
        // التحقق من أوامر فتح التطبيقات
        if (message.contains("افتح") || message.contains("شغل") || message.contains("تشغيل")) {
            val appName = extractAppName(message)
            if (appName.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    uiAutomator.launchApp(appName)
                }
            }
        }
    }
    
    /**
     * التحقق مما إذا كانت الرسالة تحتوي على طلب تعلم تلقائي
     */
    private fun isAutonomousLearningRequest(message: String): Boolean {
        val lowerMessage = message.toLowerCase()
        
        // البحث عن عبارات طلب التعلم
        return lowerMessage.contains("ابحث عن") && (
            lowerMessage.contains("ساعات") || 
            lowerMessage.contains("ساعة") ||
            lowerMessage.contains("دقائق") ||
            lowerMessage.contains("لمدة") ||
            lowerMessage.contains("طول") ||
            lowerMessage.contains("وتعلم")
        )
    }
    
    /**
     * معالجة طلب التعلم التلقائي
     */
    private suspend fun handleAutonomousLearningRequest(message: String, loadingMessage: MessageItem) {
        try {
            // تحليل الرسالة لاستخراج الموضوع والمدة
            val (topic, durationMinutes) = extractLearningParameters(message)
            
            // إزالة رسالة التحميل
            messageList.remove(loadingMessage)
            chatAdapter.notifyDataSetChanged()
            
            // إضافة رسالة بدء التعلم
            val startMessage = "سأبدأ الآن بالبحث والتعلم حول \"$topic\" لمدة $durationMinutes دقيقة. سأخبرك بالتقدم وأشارك معك المعلومات التي أتعلمها."
            addMessage(startMessage, false)
            
            // بدء جلسة التعلم التلقائي
            withContext(Dispatchers.IO) {
                adaptiveLearningEngine.startAutonomousLearning(
                    topic = topic,
                    durationMinutes = durationMinutes.toInt(),
                    callback = { update -> handleLearningUpdate(update) }
                )
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling learning request", e)
            addMessage("عذراً، حدث خطأ أثناء بدء جلسة التعلم. يرجى المحاولة مرة أخرى.", false)
        }
    }
    
    /**
     * استخراج معلمات التعلم (الموضوع والمدة) من الرسالة
     * 
     * @param message الرسالة
     * @return زوج من الموضوع والمدة بالدقائق
     */
    private fun extractLearningParameters(message: String): Pair<String, Long> {
        // استخراج المدة
        val durationPattern = Regex("(\\d+)\\s*(ساعات|ساعة|دقائق|دقيقة)")
        val durationMatch = durationPattern.find(message)
        
        var durationMinutes: Long = 30 // المدة الافتراضية بالدقائق
        
        if (durationMatch != null) {
            val amount = durationMatch.groupValues[1].toLongOrNull() ?: 1
            val unit = durationMatch.groupValues[2]
            
            durationMinutes = when {
                unit.contains("ساعة") -> amount * 60 // تحويل الساعات إلى دقائق
                else -> amount // دقائق
            }
        }
        
        // استخراج الموضوع
        var topic = message
            .replace(Regex("ابحث عن|تعلم عن|ابحث لي عن|أبحث|بحث"), "")
            .replace(durationPattern, "")
            .replace(Regex("لمدة|خلال|طوال|طول"), "")
            .replace(Regex("وتعلم|وأخبرني|وأعلمني"), "")
            .trim()
        
        // إذا كان الموضوع فارغاً، استخدم موضوعاً افتراضياً
        if (topic.isBlank()) {
            topic = "الذكاء الاصطناعي"
        }
        
        return Pair(topic, durationMinutes)
    }
    
    /**
     * معالجة تحديثات التعلم
     */
    private fun handleLearningUpdate(update: LearningUpdate) {
        runOnUiThread {
            when (update.status) {
                LearningStatus.STARTED -> {
                    // تم بدء جلسة التعلم
                    Log.d("Learning", "Started learning session: ${update.sessionId}")
                }
                
                LearningStatus.PLANNING -> {
                    // تم إنشاء خطة التعلم
                    val message = "خطة التعلم: ${update.message}\n\n" +
                        update.discoveries.joinToString("\n") { "• ${it.title}" }
                    addMessage(message, false)
                }
                
                LearningStatus.RESEARCHING -> {
                    // تحديث حالة البحث
                    if (update.discoveries.isNotEmpty() && update.discoveries.size % 3 == 0) {
                        // إضافة تحديث كل 3 اكتشافات
                        val recentDiscoveries = update.discoveries.takeLast(3)
                        val message = "اكتشفت بعض المعلومات الجديدة عن الموضوع:\n\n" +
                            recentDiscoveries.joinToString("\n\n") { "• ${it.title}:\n${it.content.take(150)}..." }
                        addMessage(message, false)
                    }
                }
                
                LearningStatus.ANALYZING -> {
                    // مرحلة تحليل المعلومات
                    addMessage("أقوم الآن بتحليل وتنظيم المعلومات التي تعلمتها...", false)
                }
                
                LearningStatus.COMPLETED -> {
                    // اكتمال جلسة التعلم
                    val numDiscoveries = update.discoveries.size
                    val numInsights = update.insights.size
                    
                    // إنشاء ملخص للتعلم
                    val summaryMessage = "اكتملت جلسة التعلم! تعلمت $numDiscoveries معلومات جديدة واستخلصت $numInsights أفكار رئيسية.\n\n" +
                        "أهم الأفكار المستفادة:\n" +
                        update.insights.joinToString("\n\n") { "• ${it.title}:\n${it.explanation.take(200)}..." }
                    
                    addMessage(summaryMessage, false)
                    
                    // إظهار رسالة نهائية
                    addMessage("يمكنني الإجابة على أسئلتك حول هذا الموضوع الآن. ما الذي ترغب في معرفته؟", false)
                }
                
                LearningStatus.ERROR -> {
                    // حدث خطأ أثناء التعلم
                    addMessage("عذراً، حدث خطأ أثناء جلسة التعلم: ${update.message}", false)
                }
            }
        }
    }
        }
    }

    private fun addMessage(message: String, isUser: Boolean) {
        messageList.add(MessageItem(message, isUser))
        chatAdapter.notifyItemInserted(messageList.size - 1)
        recyclerView.smoothScrollToPosition(messageList.size - 1)
    }

    /**
     * Initialize the UI Automator and other automation components
     */
    private fun initializeAutomationComponents() {
        // Check if the automation service and notification listener service are running
        if (!AutomationService.isServiceConnected()) {
            Toast.makeText(
                this,
                "خدمة الأتمتة غير مفعلة. سيتم تفعيلها لتوفير تحكم كامل في الهاتف",
                Toast.LENGTH_LONG
            ).show()
        }
        
        if (!NotificationListenerService.isServiceConnected()) {
            Toast.makeText(
                this,
                "خدمة الإشعارات غير مفعلة. سيتم تفعيلها للوصول إلى الإشعارات",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // Check if screen capture permission has been granted
        if (ScreenCaptureService.screenCaptureIntent == null) {
            Toast.makeText(
                this,
                "إذن التقاط الشاشة غير ممنوح. سيتم طلبه للتفاعل مع التطبيقات الأخرى",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // Initialize speech components if they aren't already
        initializeSpeechComponents()
    }
    
    /**
     * Initialize speech recognition components
     */
    private fun initializeSpeechComponents() {
        try {
            // Check for required permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // Request the permission
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
            } else {
                // Permission already granted, enable speech
                isSpeechEnabled = true
            }
            
            Log.d("MainActivity", "Speech components initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing speech components: ${e.message}")
            isSpeechEnabled = false
        }
    }
    
    /**
     * Handle speech recognition result
     */
    private fun handleSpeechResult(text: String) {
        if (text.isNotEmpty()) {
            // Add the recognized text to the message input
            messageInput.setText(text)
            
            // Send the message
            sendMessage()
        }
    }
    
    /**
     * إعداد خدمة اكتشاف الكلمة المفتاحية
     */
    private fun setupHotwordDetection() {
        try {
            // التحقق من وجود إذن الميكروفون
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                return // سيتم طلب الإذن لاحقاً
            }
            
            // إعداد خدمة اكتشاف الكلمة المفتاحية
            hotwordServiceIntent = Intent(this, HotwordDetectionService::class.java)
            
            // بدء الخدمة
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(hotwordServiceIntent)
            } else {
                startService(hotwordServiceIntent)
            }
            
            isHotwordDetectionEnabled = true
            
            // تسجيل هذا النشاط كمستمع للكلمات المفتاحية
            val hotwordService = getSystemService(Context.BIND_SERVICE_AGENCY) as? HotwordDetectionService
            hotwordService?.setHotwordListener(this)
            
            Log.d("MainActivity", "تم بدء خدمة اكتشاف الكلمة المفتاحية")
            
            Toast.makeText(
                this,
                "يمكنك الآن تنشيط زمولي بمجرد قول اسمه",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "خطأ في إعداد خدمة اكتشاف الكلمة المفتاحية: ${e.message}")
            isHotwordDetectionEnabled = false
        }
    }
    
    /**
     * استجابة للكلمة المفتاحية المكتشفة
     * تنفيذ واجهة HotwordListener
     */
    override fun onHotwordDetected() {
        // تشغيل مؤثر صوتي للإشارة إلى أن الكلمة المفتاحية تم اكتشافها
        runOnUiThread {
            Toast.makeText(
                this,
                "زمولي يستمع إليك الآن...",
                Toast.LENGTH_SHORT
            ).show()
            
            // بدء التعرف على الكلام لاستقبال الأمر
            startSpeechRecognition()
        }
    }
    
    /**
     * Start speech recognition
     */
    private fun startSpeechRecognition() {
        try {
            // التحقق من وجود إذن الميكروفون
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // طلب إذن الميكروفون
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
                return
            }
            
            // بدء التعرف على الكلام
            speechRecognizer = SpeechRecognizer(this, object : SpeechRecognizer.SpeechRecognizerCallback {
                override fun onResult(text: String) {
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            messageInput.setText(text)
                        }
                    }
                }
                
                override fun onError(errorMessage: String) {
                    Log.e("MainActivity", "Speech recognition error: $errorMessage")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "حدث خطأ في التعرف على الكلام: $errorMessage",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
            
            speechRecognizer?.startListening()
            
            Toast.makeText(
                this,
                "تحدث الآن...",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting speech recognition", e)
            Toast.makeText(
                this,
                "فشل بدء التعرف على الكلام",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * Speak the given text using text-to-speech
     */
    private fun speakText(text: String) {
        // TODO: يمكن إضافة وظيفة تحويل النص إلى كلام هنا في المستقبل
        Toast.makeText(
            this,
            "تفعيل الكلام: $text",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        aiProcessor.close()
        
        // Clean up speech resources
        speechRecognizer?.release()
        
        // إيقاف خدمة اكتشاف الكلمة المفتاحية
        if (isHotwordDetectionEnabled && hotwordServiceIntent != null) {
            stopService(hotwordServiceIntent)
            isHotwordDetectionEnabled = false
            Log.d("MainActivity", "تم إيقاف خدمة اكتشاف الكلمة المفتاحية")
        }
    }
    
    /**
     * إنشاء قائمة الخيارات
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    /**
     * معالجة اختيارات القائمة
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_advanced_models -> {
                // الانتقال إلى نشاط النماذج المتقدمة
                openAdvancedModelsActivity()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * فتح نشاط النماذج المتقدمة
     */
    private fun openAdvancedModelsActivity() {
        val intent = Intent(this, AdvancedModelsActivity::class.java)
        startActivity(intent)
    }
}
