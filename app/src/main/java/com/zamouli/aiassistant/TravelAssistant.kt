package com.intelliai.assistant

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * مساعد السفر والتنقل
 * يستخدم OpenStreetMap وNominatim وOSRM APIs (مجانية بالكامل)
 * يوفر خدمات البحث عن الأماكن، والحصول على اتجاهات السفر، وإنشاء خطط سفر مخصصة
 */
class TravelAssistant(private val context: Context) {
    
    companion object {
        private const val TAG = "TravelAssistant"
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
        private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1"
        private const val OPEN_WEATHER_BASE_URL = "https://api.openweathermap.org/data/2.5"
        private const val WEATHER_API_KEY = "7956aadd5a325cb3774527f4246cb3a4" // مفتاح API من المستخدم
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // إضافة User-Agent لاحترام سياسة Nominatim
            val request = chain.request().newBuilder()
                .header("User-Agent", "IntelliAIAssistant/1.0")
                .build()
            chain.proceed(request)
        }
        .build()
    
    /**
     * البحث عن موقع بالاسم باستخدام Nominatim (مجاني)
     * 
     * @param query استعلام البحث (مثلاً: اسم المدينة أو المعلم)
     * @param limit عدد النتائج الأقصى
     * @return قائمة بالأماكن المطابقة
     */
    suspend fun searchLocation(query: String, limit: Int = 5): List<Place> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$NOMINATIM_BASE_URL/search?q=$encodedQuery&format=json&limit=$limit&addressdetails=1"
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "فشل في البحث عن الموقع. رمز الاستجابة: ${response.code}")
                    return@withContext emptyList<Place>()
                }
                
                val jsonString = response.body?.string() ?: return@withContext emptyList<Place>()
                val jsonArray = JSONArray(jsonString)
                
                val places = mutableListOf<Place>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val coordinates = Coordinates(
                        latitude = item.getDouble("lat"),
                        longitude = item.getDouble("lon")
                    )
                    
                    val displayName = item.getString("display_name")
                    val importance = item.getDouble("importance").toFloat()
                    
                    // استخراج معلومات إضافية من بيانات العنوان إن وجدت
                    val addressObj = item.optJSONObject("address")
                    var city = ""
                    var country = ""
                    
                    if (addressObj != null) {
                        city = addressObj.optString("city", addressObj.optString("town", addressObj.optString("village", "")))
                        country = addressObj.optString("country", "")
                    }
                    
                    places.add(
                        Place(
                            name = displayName.split(",").firstOrNull()?.trim() ?: displayName,
                            address = displayName,
                            coordinates = coordinates,
                            rating = importance,
                            city = city,
                            country = country,
                            type = item.optString("type", "")
                        )
                    )
                }
                
                places
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في البحث عن الموقع: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * البحث عن الأماكن القريبة من موقع معين باستخدام Nominatim (مجاني)
     * 
     * @param latitude خط العرض للموقع المركزي
     * @param longitude خط الطول للموقع المركزي
     * @param category فئة المكان (مثلاً: مطعم، فندق، مستشفى)
     * @param radius نصف قطر البحث بالمتر (الحد الأقصى 5000 متر مع Nominatim المجاني)
     * @return قائمة بالأماكن القريبة
     */
    suspend fun findNearbyPlaces(
        latitude: Double,
        longitude: Double,
        category: String,
        radius: Int = 1000
    ): List<Place> = withContext(Dispatchers.IO) {
        try {
            // ترجمة الفئة إلى التصنيف المناسب في OpenStreetMap
            val osmCategory = translateCategoryToOsm(category)
            
            // استخدام Overpass API (مجاني) للحصول على نتائج أكثر دقة
            val overpassUrl = "https://overpass-api.de/api/interpreter"
            val bbox = calculateBoundingBox(latitude, longitude, radius)
            
            val query = """
                [out:json];
                node["$osmCategory"](${bbox.minLat},${bbox.minLon},${bbox.maxLat},${bbox.maxLon});
                out body 10;
            """.trimIndent()
            
            val requestBody = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("text/plain"), query
            )
            
            val request = Request.Builder()
                .url(overpassUrl)
                .post(requestBody)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "فشل في البحث عن الأماكن القريبة. رمز الاستجابة: ${response.code}")
                    return@withContext emptyList<Place>()
                }
                
                val jsonString = response.body?.string() ?: return@withContext emptyList<Place>()
                val jsonObject = JSONObject(jsonString)
                val elements = jsonObject.getJSONArray("elements")
                
                val places = mutableListOf<Place>()
                for (i in 0 until elements.length()) {
                    val element = elements.getJSONObject(i)
                    val nodeCoords = Coordinates(
                        latitude = element.getDouble("lat"),
                        longitude = element.getDouble("lon")
                    )
                    
                    val tags = element.optJSONObject("tags") ?: JSONObject()
                    val name = tags.optString("name", "مكان بدون اسم")
                    
                    // حساب المسافة من الموقع المركزي
                    val distance = calculateDistance(
                        latitude, longitude,
                        nodeCoords.latitude, nodeCoords.longitude
                    )
                    
                    if (distance <= radius) {
                        places.add(
                            Place(
                                name = name,
                                address = tags.optString("addr:street", "") + ", " + tags.optString("addr:city", ""),
                                coordinates = nodeCoords,
                                rating = 0f, // Overpass API لا يوفر تقييمات
                                distance = distance.toFloat(),
                                type = category
                            )
                        )
                    }
                }
                
                // ترتيب الأماكن حسب المسافة
                places.sortBy { it.distance }
                places
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في البحث عن الأماكن القريبة: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * الحصول على اتجاهات السفر بين موقعين باستخدام OSRM (مجاني)
     * 
     * @param startLatitude خط العرض لنقطة البداية
     * @param startLongitude خط الطول لنقطة البداية
     * @param endLatitude خط العرض لنقطة النهاية
     * @param endLongitude خط الطول لنقطة النهاية
     * @param profile نوع التنقل (car, bike, foot)
     * @return معلومات المسار
     */
    suspend fun getDirections(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
        profile: String = "car"
    ): RouteInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$OSRM_BASE_URL/$profile/$startLongitude,$startLatitude;$endLongitude,$endLatitude" +
                    "?overview=full&steps=true&geometries=geojson"
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "فشل في الحصول على اتجاهات السفر. رمز الاستجابة: ${response.code}")
                    return@withContext null
                }
                
                val jsonString = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(jsonString)
                
                if (jsonObject.getString("code") != "Ok") {
                    Log.e(TAG, "خطأ في استجابة OSRM: ${jsonObject.getString("code")}")
                    return@withContext null
                }
                
                val routes = jsonObject.getJSONArray("routes")
                if (routes.length() == 0) {
                    return@withContext null
                }
                
                val route = routes.getJSONObject(0)
                val distance = route.getDouble("distance") / 1000 // تحويل من متر إلى كيلومتر
                val duration = route.getDouble("duration") / 60   // تحويل من ثانية إلى دقيقة
                
                // استخراج نقاط الطريق
                val waypoints = mutableListOf<Coordinates>()
                val geometry = route.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                
                for (i in 0 until coordinates.length()) {
                    val point = coordinates.getJSONArray(i)
                    waypoints.add(
                        Coordinates(
                            latitude = point.getDouble(1),
                            longitude = point.getDouble(0)
                        )
                    )
                }
                
                // استخراج التعليمات
                val instructions = mutableListOf<String>()
                val legs = route.getJSONArray("legs")
                if (legs.length() > 0) {
                    val leg = legs.getJSONObject(0)
                    val steps = leg.getJSONArray("steps")
                    
                    for (i in 0 until steps.length()) {
                        val step = steps.getJSONObject(i)
                        val maneuver = step.getJSONObject("maneuver")
                        val type = maneuver.getString("type")
                        
                        // ترجمة نوع المناورة إلى تعليمات مفهومة
                        val instruction = translateManeuverToArabic(type, maneuver.optString("modifier", ""))
                        
                        if (instruction.isNotEmpty()) {
                            instructions.add(instruction)
                        }
                    }
                }
                
                RouteInfo(
                    distance = distance.toFloat(),
                    duration = duration.toInt(),
                    waypoints = waypoints,
                    instructions = instructions
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في الحصول على اتجاهات السفر: ${e.message}", e)
            null
        }
    }
    
    /**
     * الحصول على معلومات الطقس للموقع باستخدام OpenWeatherMap API (خطة مجانية)
     * 
     * @param latitude خط العرض
     * @param longitude خط الطول
     * @return معلومات الطقس
     */
    suspend fun getWeatherForLocation(latitude: Double, longitude: Double): WeatherInfo? = withContext(Dispatchers.IO) {
        if (WEATHER_API_KEY.isEmpty()) {
            Log.e(TAG, "مفتاح API لـ OpenWeatherMap غير متوفر")
            return@withContext null
        }
        
        try {
            val url = "$OPEN_WEATHER_BASE_URL/weather?lat=$latitude&lon=$longitude&units=metric&lang=ar&appid=$WEATHER_API_KEY"
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "فشل في الحصول على معلومات الطقس. رمز الاستجابة: ${response.code}")
                    return@withContext null
                }
                
                val jsonString = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(jsonString)
                
                val main = jsonObject.getJSONObject("main")
                val weather = jsonObject.getJSONArray("weather").getJSONObject(0)
                val wind = jsonObject.getJSONObject("wind")
                
                val location = jsonObject.getString("name") + ", " + 
                               jsonObject.getJSONObject("sys").getString("country")
                
                WeatherInfo(
                    temperature = main.getDouble("temp").toFloat(),
                    feelsLike = main.getDouble("feels_like").toFloat(),
                    humidity = main.getInt("humidity"),
                    windSpeed = wind.getDouble("speed").toFloat(),
                    condition = weather.getString("description"),
                    icon = weather.getString("icon"),
                    location = location
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في الحصول على معلومات الطقس: ${e.message}", e)
            null
        }
    }
    
    /**
     * إنشاء خطة سفر وفقًا لتفضيلات المستخدم وميزانيته
     * 
     * @param destination وجهة السفر
     * @param interests اهتمامات المستخدم (مثلاً: تاريخ، طبيعة، تسوق)
     * @param budget ميزانية السفر (منخفضة، متوسطة، مرتفعة)
     * @param duration مدة الرحلة بالأيام
     * @return خطة السفر المقترحة
     */
    suspend fun createTravelPlan(
        destination: String,
        interests: List<String>,
        budget: String,
        duration: Int
    ): TravelPlan = withContext(Dispatchers.Default) {
        try {
            // البحث عن الوجهة
            val places = searchLocation(destination, 1)
            if (places.isEmpty()) {
                return@withContext TravelPlan(
                    destination = destination,
                    summary = "تعذر العثور على وجهة السفر",
                    dailyPlans = emptyList(),
                    estimatedCost = 0.0,
                    tips = listOf("يرجى التأكد من اسم الوجهة والمحاولة مرة أخرى")
                )
            }
            
            val destinationPlace = places.first()
            
            // البحث عن أماكن الجذب وفقًا للاهتمامات
            val attractionCategories = mapInterestsToCategories(interests)
            val attractions = mutableListOf<Place>()
            
            for (category in attractionCategories) {
                val categoryAttractions = findNearbyPlaces(
                    destinationPlace.coordinates.latitude,
                    destinationPlace.coordinates.longitude,
                    category,
                    5000 // نصف قطر 5 كيلومتر
                )
                attractions.addAll(categoryAttractions)
            }
            
            // توزيع الأماكن على أيام الرحلة
            val dailyPlans = createDailyPlans(attractions, duration)
            
            // حساب التكلفة التقريبية
            val estimatedCost = estimateTravelCost(destinationPlace, budget, duration)
            
            // إنشاء نصائح السفر
            val tips = createTravelTips(destinationPlace, budget, duration)
            
            // إنشاء ملخص
            val summary = createTravelSummary(destinationPlace, interests, duration, estimatedCost)
            
            TravelPlan(
                destination = destinationPlace.name,
                summary = summary,
                dailyPlans = dailyPlans,
                estimatedCost = estimatedCost,
                tips = tips,
                location = destinationPlace
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إنشاء خطة السفر: ${e.message}", e)
            
            TravelPlan(
                destination = destination,
                summary = "حدث خطأ أثناء إنشاء خطة السفر",
                dailyPlans = emptyList(),
                estimatedCost = 0.0,
                tips = listOf("يرجى المحاولة مرة أخرى لاحقًا")
            )
        }
    }
    
    /**
     * حساب المسافة بين موقعين باستخدام صيغة هافرساين
     * 
     * @param lat1 خط عرض الموقع الأول
     * @param lon1 خط طول الموقع الأول
     * @param lat2 خط عرض الموقع الثاني
     * @param lon2 خط طول الموقع الثاني
     * @return المسافة بالمتر
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // نصف قطر الأرض بالمتر
        
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val latDiff = Math.toRadians(lat2 - lat1)
        val lonDiff = Math.toRadians(lon2 - lon1)
        
        val a = Math.sin(latDiff / 2) * Math.sin(latDiff / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(lonDiff / 2) * Math.sin(lonDiff / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * حساب المستطيل المحيط لنصف قطر حول نقطة
     * 
     * @param lat خط العرض للمركز
     * @param lon خط الطول للمركز
     * @param radiusMeters نصف القطر بالمتر
     * @return المستطيل المحيط
     */
    private fun calculateBoundingBox(lat: Double, lon: Double, radiusMeters: Int): BoundingBox {
        val earthRadius = 6371000.0 // بالمتر
        
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        
        val angularDistance = radiusMeters / earthRadius
        
        val minLat = Math.toDegrees(latRad - angularDistance)
        val maxLat = Math.toDegrees(latRad + angularDistance)
        
        val latCosine = Math.cos(latRad)
        var deltaLon: Double
        
        if (latCosine > 0.000001) { // تجنب القسمة على صفر
            deltaLon = angularDistance / latCosine
        } else {
            deltaLon = Math.PI // الدائرة الكاملة
        }
        
        val minLon = Math.toDegrees(lonRad - deltaLon)
        val maxLon = Math.toDegrees(lonRad + deltaLon)
        
        return BoundingBox(minLat, minLon, maxLat, maxLon)
    }
    
    /**
     * ترجمة فئة المكان إلى تصنيف OpenStreetMap
     * 
     * @param category فئة المكان
     * @return تصنيف OpenStreetMap
     */
    private fun translateCategoryToOsm(category: String): String {
        return when (category.toLowerCase()) {
            "مطعم", "مطاعم", "restaurant", "restaurants" -> "amenity=restaurant"
            "مقهى", "كافيه", "cafe", "coffee" -> "amenity=cafe"
            "فندق", "hotels", "hotel" -> "tourism=hotel"
            "متحف", "museum", "museums" -> "tourism=museum"
            "حديقة", "park", "parks" -> "leisure=park"
            "مستشفى", "hospital", "hospitals" -> "amenity=hospital"
            "صيدلية", "pharmacy", "pharmacies" -> "amenity=pharmacy"
            "محطة وقود", "gas station", "petrol" -> "amenity=fuel"
            "مول", "تسوق", "shopping", "mall" -> "shop=mall"
            "سينما", "cinema", "movie" -> "amenity=cinema"
            "مسجد", "mosque", "mosques" -> "amenity=place_of_worship"
            "بنك", "bank", "atm" -> "amenity=bank"
            "سوق", "market" -> "shop=marketplace"
            "معلم", "attraction", "سياحة" -> "tourism=attraction"
            "شاطئ", "beach", "beaches" -> "natural=beach"
            "جبل", "mountain", "mountains" -> "natural=peak"
            else -> "tourism=attraction" // تصنيف افتراضي
        }
    }
    
    /**
     * ترجمة نوع المناورة إلى تعليمات باللغة العربية
     * 
     * @param type نوع المناورة
     * @param modifier مُعدِّل المناورة
     * @return تعليمات المناورة
     */
    private fun translateManeuverToArabic(type: String, modifier: String): String {
        return when (type) {
            "turn" -> {
                when (modifier) {
                    "left" -> "انعطف يسارًا"
                    "right" -> "انعطف يمينًا"
                    "slight left" -> "انعطف قليلاً لليسار"
                    "slight right" -> "انعطف قليلاً لليمين"
                    "sharp left" -> "انعطف بحدة لليسار"
                    "sharp right" -> "انعطف بحدة لليمين"
                    else -> "انعطف"
                }
            }
            "continue" -> "استمر للأمام"
            "new name" -> "استمر على الطريق (اسم جديد)"
            "merge" -> "اندمج مع الطريق"
            "arrive" -> {
                when (modifier) {
                    "left" -> "الوجهة على اليسار"
                    "right" -> "الوجهة على اليمين"
                    else -> "وصلت إلى الوجهة"
                }
            }
            "roundabout" -> "ادخل الدوار"
            "rotary" -> "ادخل الدوار"
            "exit roundabout" -> "اخرج من الدوار"
            "exit rotary" -> "اخرج من الدوار"
            "fork" -> {
                when (modifier) {
                    "left" -> "خذ التفرع اليساري"
                    "right" -> "خذ التفرع اليميني"
                    "slight left" -> "خذ التفرع قليلاً لليسار"
                    "slight right" -> "خذ التفرع قليلاً لليمين"
                    else -> "استمر عند التفرع"
                }
            }
            "end of road" -> {
                when (modifier) {
                    "left" -> "انعطف يسارًا في نهاية الطريق"
                    "right" -> "انعطف يمينًا في نهاية الطريق"
                    else -> "انعطف في نهاية الطريق"
                }
            }
            else -> ""
        }
    }
    
    /**
     * ربط اهتمامات المستخدم بفئات الأماكن
     * 
     * @param interests اهتمامات المستخدم
     * @return فئات الأماكن المقابلة
     */
    private fun mapInterestsToCategories(interests: List<String>): List<String> {
        val categories = mutableListOf<String>()
        
        for (interest in interests) {
            when (interest.toLowerCase()) {
                "تاريخ", "history" -> {
                    categories.add("museum")
                    categories.add("attraction")
                }
                "طبيعة", "nature" -> {
                    categories.add("park")
                    categories.add("beach")
                    categories.add("mountain")
                }
                "تسوق", "shopping" -> {
                    categories.add("mall")
                    categories.add("market")
                }
                "طعام", "food" -> {
                    categories.add("restaurant")
                    categories.add("cafe")
                }
                "ثقافة", "culture" -> {
                    categories.add("museum")
                    categories.add("attraction")
                    categories.add("mosque")
                }
                "ترفيه", "entertainment" -> {
                    categories.add("cinema")
                    categories.add("park")
                }
                "استرخاء", "relaxation" -> {
                    categories.add("park")
                    categories.add("beach")
                    categories.add("cafe")
                }
                else -> categories.add("attraction") // فئة افتراضية
            }
        }
        
        return categories.distinct()
    }
    
    /**
     * إنشاء خطط يومية للرحلة
     * 
     * @param attractions أماكن الجذب
     * @param duration مدة الرحلة بالأيام
     * @return خطط الأيام
     */
    private fun createDailyPlans(attractions: List<Place>, duration: Int): List<DailyPlan> {
        val dailyPlans = mutableListOf<DailyPlan>()
        
        // تقسيم الأماكن على عدد الأيام
        val placesPerDay = ceil(attractions.size.toFloat() / duration).toInt()
        
        for (day in 1..duration) {
            val startIndex = (day - 1) * placesPerDay
            val endIndex = minOf(startIndex + placesPerDay, attractions.size)
            
            // إذا تجاوزنا عدد الأماكن المتاحة
            if (startIndex >= attractions.size) {
                break
            }
            
            // اختيار الأماكن لهذا اليوم
            val dayAttractions = attractions.subList(startIndex, endIndex)
            
            // إنشاء أنشطة اليوم
            val activities = mutableListOf<Activity>()
            var currentTime = 9 // نبدأ من الساعة 9 صباحًا
            
            for (attraction in dayAttractions) {
                val duration = estimateVisitDuration(attraction.type)
                
                activities.add(
                    Activity(
                        name = attraction.name,
                        place = attraction,
                        startTime = formatTime(currentTime),
                        endTime = formatTime(currentTime + duration),
                        duration = duration,
                        description = "زيارة ${attraction.name}"
                    )
                )
                
                currentTime += duration + 1 // إضافة وقت للانتقال
            }
            
            dailyPlans.add(
                DailyPlan(
                    day = day,
                    activities = activities,
                    summary = "اليوم ${day}: زيارة ${activities.size} أماكن"
                )
            )
        }
        
        return dailyPlans
    }
    
    /**
     * تقدير مدة زيارة المكان بالساعات
     * 
     * @param placeType نوع المكان
     * @return المدة المقدرة بالساعات
     */
    private fun estimateVisitDuration(placeType: String): Int {
        return when {
            placeType.contains("متحف") || placeType.contains("museum") -> 2
            placeType.contains("حديقة") || placeType.contains("park") -> 3
            placeType.contains("شاطئ") || placeType.contains("beach") -> 4
            placeType.contains("مول") || placeType.contains("تسوق") || placeType.contains("mall") -> 3
            placeType.contains("مطعم") || placeType.contains("restaurant") -> 2
            placeType.contains("معلم") || placeType.contains("attraction") -> 1
            placeType.contains("سوق") || placeType.contains("market") -> 2
            else -> 1
        }
    }
    
    /**
     * تنسيق الوقت بصيغة 24 ساعة
     * 
     * @param hour الساعة
     * @return الوقت المنسق
     */
    private fun formatTime(hour: Int): String {
        val adjustedHour = hour % 24
        return String.format("%02d:00", adjustedHour)
    }
    
    /**
     * تقدير تكلفة الرحلة
     * 
     * @param destination وجهة السفر
     * @param budget ميزانية السفر
     * @param duration مدة الرحلة بالأيام
     * @return التكلفة المقدرة
     */
    private fun estimateTravelCost(destination: Place, budget: String, duration: Int): Double {
        // معدلات افتراضية للإقامة والطعام والنقل
        val accommodationCost = when (budget.toLowerCase()) {
            "منخفضة", "low" -> 50.0
            "متوسطة", "medium" -> 100.0
            "مرتفعة", "high" -> 200.0
            else -> 100.0
        }
        
        val foodCost = when (budget.toLowerCase()) {
            "منخفضة", "low" -> 30.0
            "متوسطة", "medium" -> 60.0
            "مرتفعة", "high" -> 120.0
            else -> 60.0
        }
        
        val transportCost = when (budget.toLowerCase()) {
            "منخفضة", "low" -> 20.0
            "متوسطة", "medium" -> 40.0
            "مرتفعة", "high" -> 80.0
            else -> 40.0
        }
        
        // ضبط التكاليف بناءً على البلد
        val costMultiplier = when (destination.country.toLowerCase()) {
            "saudi arabia", "المملكة العربية السعودية", "السعودية" -> 1.2
            "united arab emirates", "الإمارات العربية المتحدة", "الإمارات" -> 1.3
            "egypt", "مصر" -> 0.7
            "jordan", "الأردن" -> 0.8
            "lebanon", "لبنان" -> 0.9
            "morocco", "المغرب" -> 0.8
            "tunisia", "تونس" -> 0.75
            "sudan", "السودان" -> 0.6
            "united states", "الولايات المتحدة" -> 1.5
            "united kingdom", "المملكة المتحدة" -> 1.4
            "france", "فرنسا" -> 1.3
            "germany", "ألمانيا" -> 1.2
            "spain", "إسبانيا" -> 1.1
            "italy", "إيطاليا" -> 1.2
            "japan", "اليابان" -> 1.4
            "south korea", "كوريا الجنوبية" -> 1.2
            "china", "الصين" -> 1.0
            "malaysia", "ماليزيا" -> 0.9
            "indonesia", "إندونيسيا" -> 0.8
            "thailand", "تايلاند" -> 0.7
            else -> 1.0
        }
        
        val dailyCost = (accommodationCost + foodCost + transportCost) * costMultiplier
        return dailyCost * duration
    }
    
    /**
     * إنشاء نصائح السفر
     * 
     * @param destination وجهة السفر
     * @param budget ميزانية السفر
     * @param duration مدة الرحلة بالأيام
     * @return قائمة بالنصائح
     */
    private fun createTravelTips(destination: Place, budget: String, duration: Int): List<String> {
        val tips = mutableListOf<String>()
        
        // نصائح عامة
        tips.add("احرص على حمل جواز السفر وبطاقة الهوية في جميع الأوقات")
        tips.add("اشترِ بطاقة SIM محلية للاتصال بالإنترنت والهاتف بتكلفة أقل")
        
        // نصائح خاصة بالبلد
        when (destination.country.toLowerCase()) {
            "saudi arabia", "المملكة العربية السعودية", "السعودية" -> {
                tips.add("احترم العادات والتقاليد المحلية وارتدِ ملابس محتشمة")
                tips.add("تأكد من وقت الصلاة حيث تغلق معظم المحلات خلال أوقات الصلاة")
            }
            "egypt", "مصر" -> {
                tips.add("احذر من الباعة الجائلين وتأكد من سعر المنتجات قبل الشراء")
                tips.add("استخدم تطبيقات النقل مثل أوبر وكريم للتنقل بأمان")
            }
            "united arab emirates", "الإمارات العربية المتحدة", "الإمارات" -> {
                tips.add("احترم القوانين المحلية وتجنب التصرفات غير اللائقة في الأماكن العامة")
                tips.add("يمكنك استخدام مترو دبي للتنقل بسهولة وبتكلفة منخفضة")
            }
            "jordan", "الأردن" -> {
                tips.add("تذكر أن المواقع السياحية مثل البتراء تحتاج ليوم كامل على الأقل")
                tips.add("اشرب كميات كافية من الماء، خاصة إذا كنت تزور في فصل الصيف")
            }
            "sudan", "السودان" -> {
                tips.add("احمل معك النقود الكافية، حيث قد يكون من الصعب العثور على أجهزة الصراف الآلي في بعض المناطق")
                tips.add("الطقس حار جداً في معظم أنحاء السودان، خاصة خلال فصل الصيف، فتأكد من ارتداء ملابس خفيفة وشرب كميات كافية من الماء")
                tips.add("احترم العادات والتقاليد المحلية وارتدِ ملابس محتشمة")
            }
            else -> {
                tips.add("تعرف على العادات والتقاليد المحلية قبل زيارة البلد")
                tips.add("احمل معك بعض العملة المحلية للمصاريف الأولية")
            }
        }
        
        // نصائح خاصة بالميزانية
        when (budget.toLowerCase()) {
            "منخفضة", "low" -> {
                tips.add("ابحث عن المطاعم الشعبية ذات الأسعار المعقولة")
                tips.add("استخدم وسائل النقل العام بدلاً من سيارات الأجرة")
                tips.add("ابحث عن العروض والخصومات على الفنادق والنزل")
            }
            "متوسطة", "medium" -> {
                tips.add("وازن بين المطاعم الراقية والمطاعم الشعبية")
                tips.add("استخدم تطبيقات حجز الفنادق للحصول على أسعار أفضل")
            }
            "مرتفعة", "high" -> {
                tips.add("استفد من خدمات الكونسيرج في الفندق لحجز المطاعم والأنشطة")
                tips.add("فكر في استئجار سيارة خاصة للتنقل براحة أكبر")
            }
        }
        
        // نصائح خاصة بمدة الإقامة
        if (duration <= 3) {
            tips.add("ركز على زيارة المعالم الرئيسية نظرًا لقصر مدة الرحلة")
        } else if (duration <= 7) {
            tips.add("وزع وقتك بين المعالم الرئيسية واستكشاف الأماكن غير السياحية")
        } else {
            tips.add("خصص يومًا أو يومين للراحة والاسترخاء خلال رحلتك الطويلة")
        }
        
        return tips
    }
    
    /**
     * إنشاء ملخص خطة السفر
     * 
     * @param destination وجهة السفر
     * @param interests اهتمامات المستخدم
     * @param duration مدة الرحلة بالأيام
     * @param cost التكلفة المقدرة
     * @return ملخص الخطة
     */
    private fun createTravelSummary(
        destination: Place,
        interests: List<String>,
        duration: Int,
        cost: Double
    ): String {
        val sb = StringBuilder()
        
        sb.append("خطة رحلة إلى ${destination.name} لمدة $duration ")
        sb.append(if (duration == 1) "يوم" else "أيام")
        sb.append(". ")
        
        if (interests.isNotEmpty()) {
            sb.append("ستتضمن الرحلة زيارة أماكن تناسب اهتماماتك: ")
            sb.append(interests.joinToString(", "))
            sb.append(". ")
        }
        
        sb.append("التكلفة التقديرية للرحلة هي $cost$ تقريبًا. ")
        
        return sb.toString()
    }
}

/**
 * مربع الحدود (المنطقة المستطيلة)
 */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

/**
 * معلومات المكان
 */
data class Place(
    val name: String,
    val address: String,
    val coordinates: Coordinates,
    val rating: Float,
    val distance: Float? = null,
    val city: String = "",
    val country: String = "",
    val type: String = ""
)

/**
 * معلومات المسار
 */
data class RouteInfo(
    val distance: Float, // كيلومتر
    val duration: Int, // دقيقة
    val waypoints: List<Coordinates>,
    val instructions: List<String>
)

/**
 * إحداثيات جغرافية
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double
)

/**
 * معلومات الطقس
 */
data class WeatherInfo(
    val temperature: Float, // درجة مئوية
    val feelsLike: Float, // درجة مئوية
    val humidity: Int, // نسبة مئوية
    val windSpeed: Float, // م/ث
    val condition: String,
    val icon: String,
    val location: String
)

/**
 * خطة السفر
 */
data class TravelPlan(
    val destination: String,
    val summary: String,
    val dailyPlans: List<DailyPlan>,
    val estimatedCost: Double,
    val tips: List<String>,
    val location: Place? = null
)

/**
 * خطة اليوم
 */
data class DailyPlan(
    val day: Int,
    val activities: List<Activity>,
    val summary: String
)

/**
 * نشاط
 */
data class Activity(
    val name: String,
    val place: Place,
    val startTime: String,
    val endTime: String,
    val duration: Int, // بالساعات
    val description: String
)

/**
 * دالة مساعدة لتقريب الأرقام للأعلى
 */
private fun ceil(value: Float): Int {
    return Math.ceil(value.toDouble()).toInt()
}