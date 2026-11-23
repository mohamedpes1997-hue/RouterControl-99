package com.muse.huaweiroutermaster.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * فك ترميز HTML entities في نصوص الراوتر، مثلاً:
 * "H6619SP1C37&#41;40#&4.0.0.5" تعني أن &#41; هو ")" — فتظهر النسخة صح.
 * يفكّ أيضاً &#40; و &#38; و &amp; و &lt; و &gt; و &quot; و &#39;.
 */
fun decodeHtmlEntities(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    var s = raw
    val numeric = Regex("&#(\\d+);")
    s = numeric.replace(s) { m ->
        val code = m.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) code.toChar().toString() else m.value
    }
    s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
    return s
}

/**
 * الراوتر يرجع قيم الإشارة وهي تحمل الوحدة أصلاً، مثلاً "-76dBm".
 * هذه الدالة تشيل الوحدة من آخر القيمة عشان الواجهة تضيف الوحدة مرة واحدة بس،
 * وإلا يظهر الخطأ اللي شفناه في لقطات المستخدم: "-76dBm dBm-".
 */
fun stripUnitSuffix(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var s = raw.trim()
    for (unit in listOf("dBm", "MHz", "KHz", "Hz", "dB")) {
        if (s.endsWith(unit, ignoreCase = true)) {
            s = s.dropLast(unit.length).trim()
            break
        }
    }
    return s.ifBlank { null }
}

/** نتيجة اختبار سرعة تنزيل حقيقي — أرقام مقيسة فقط، لا شيء مخترع */
data class SpeedTestResult(
    val avgMbps: Double,
    val bytesDownloaded: Long,
    val durationMs: Long
)

/** نتيجة تطبيق وضع الشبكة: الرد الخام + إعادة القراءة بعد التثبيت */
data class NetModeApplyResult(
    val applied: Boolean,
    val rawResponse: String,
    val readBack: NetModeInfo?
)

/**
 * Huawei HiLink API — دخول أصلي من داخل التطبيق، بدون متصفح وبدون WebView.
 *
 * خطوات الدخول (نفس المستخدمة في مكتبة huawei_lte_api مفتوحة المصدر):
 *  1) GET  /api/webserver/SesTokInfo  -> SesInfo + TokInfo
 *  2) تشفير كلمة السر password_type=4:
 *     base64( sha256_hex( username + base64(sha256_hex(password)) + TokInfo ) )
 *  3) POST /api/user/login مع هيدر __RequestVerificationToken وكوكي الجلسة.
 *  4) بعد كل رد، نحدّث التوكن من هيدر __RequestVerificationToken.
 *
 * خطأ 125002 (ERROR_SESSION) يعني: لازم تسجيل دخول أولاً قبل قراءة الإشارة.
 *
 * يعمل مع Huawei B535 وروترات Huawei 4G/5G CPE التي تعرض نفس الـ API،
 * مثل H153-381 (موديل المستخدم — تم تأكيده من لقطة شاشة 2026-11-23)
 * و H122-373 و H155-381 وغيرها.
 */
class HuaweiRouterClient(var baseUrl: String = "http://192.168.8.1") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .cookieJar(SimpleCookieJar())
        .followRedirects(false)
        .build()

    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var sessionId: String? = null
    private var token: String? = null

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    /** أول اسم موجود من قائمة بدائل (لأن أسماء حقول NR تختلف بين الفيرمويرات) */
    private fun tagAny(xml: String, vararg names: String): String? {
        for (n in names) tag(xml, n)?.let { return it }
        return null
    }

    private fun updateToken(headers: okhttp3.Headers) {
        headers["__RequestVerificationTokenone"]?.let { token = it }
        headers["__RequestVerificationToken"]?.let { token = it }
    }

    suspend fun fetchSesTok(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/webserver/SesTokInfo").get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string() ?: return@withContext false
            sessionId = tag(body, "SesInfo")
            token = tag(body, "TokInfo")
            res.headers("Set-Cookie").firstOrNull()?.let {
                if (sessionId == null) sessionId = it.substringBefore(";")
            }
            sessionId != null && token != null
        }
    }

    suspend fun login(username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(fetchSesTok()) {
                    "تعذّر الاتصال بالراوتر — تأكد أنك متصل بواي فاي الراوتر وأن الـ IP صحيح"
                }
                val tok = token!!
                val encoded = b64(sha256Hex(username + b64(sha256Hex(password)) + tok))
                val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Username>$username</Username><Password>$encoded</Password><password_type>4</password_type></request>"""
                val req = Request.Builder().url("$baseUrl/api/user/login")
                    .header("__RequestVerificationToken", tok)
                    .apply { sessionId?.let { header("Cookie", it) } }
                    .post(xml.toRequestBody("application/xml".toMediaType())).build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    updateToken(res.headers)
                    check(body.contains("OK", ignoreCase = true)) {
                        "فشل تسجيل الدخول — تأكد من اسم المستخدم وكلمة السر: $body"
                    }
                    "تم تسجيل الدخول بنجاح"
                }
            }
        }

    suspend fun logout(): Result<String> =
        post("/api/user/logout", """<?xml version="1.0" encoding="UTF-8"?><request><Logout>1</Logout></request>""")

    private suspend fun get(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url("$baseUrl$path").get()
            sessionId?.let { b.header("Cookie", it) }
            token?.let { b.header("__RequestVerificationToken", it) }
            client.newCall(b.build()).execute().use { res ->
                updateToken(res.headers)
                val body = res.body?.string() ?: error("رد فارغ من الراوتر")
                check(!body.contains("<code>125002</code>")) {
                    "انتهت الجلسة (125002) — سجّل الدخول من جديد"
                }
                body
            }
        }
    }

    private suspend fun post(path: String, xmlBody: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (token == null) fetchSesTok()
            val b = Request.Builder().url("$baseUrl$path")
                .post(xmlBody.toRequestBody("application/xml".toMediaType()))
            sessionId?.let { b.header("Cookie", it) }
            token?.let { b.header("__RequestVerificationToken", it) }
            client.newCall(b.build()).execute().use { res ->
                updateToken(res.headers)
                val body = res.body?.string().orEmpty()
                check(res.isSuccessful) { "خطأ ${res.code}: $body" }
                body
            }
        }
    }

    /**
     * POST خام بدون رفض الرد غير الناجح — لأن الراوتر أحياناً يرد بكود خطأ
     * حتى عندما يطبّق الأمر فعلاً (سلوك موثّق في huawei-lte-api).
     * الحكم الحقيقي يكون بإعادة قراءة الوضع بعد التثبيت، لا بالرد وحده.
     */
    private suspend fun postRaw(path: String, xmlBody: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (token == null) fetchSesTok()
            val b = Request.Builder().url("$baseUrl$path")
                .post(xmlBody.toRequestBody("application/xml".toMediaType()))
            sessionId?.let { b.header("Cookie", it) }
            token?.let { b.header("__RequestVerificationToken", it) }
            client.newCall(b.build()).execute().use { res ->
                updateToken(res.headers)
                res.body?.string().orEmpty()
            }
        }
    }

    /** GET /api/device/signal — RSRP, RSRQ, SINR, RSSI, Band, PCI, Cell ID, EARFCN + حقول NR */
    suspend fun signalInfo(): Result<SignalInfo> = get("/api/device/signal").map { xml ->
        SignalInfo(
            rsrp = stripUnitSuffix(tag(xml, "rsrp")),
            rsrq = stripUnitSuffix(tag(xml, "rsrq")),
            sinr = stripUnitSuffix(tag(xml, "sinr")),
            rssi = stripUnitSuffix(tag(xml, "rssi")),
            band = tag(xml, "band"),
            pci = tag(xml, "pci"),
            earfcn = tag(xml, "earfcn"), // فقط من <earfcn> — ليس من dlbandwidth
            cellId = tag(xml, "cell_id"),
            bandwidth = stripUnitSuffix(tag(xml, "dlbandwidth")),
            ulBandwidth = stripUnitSuffix(tag(xml, "ulbandwidth")),
            mode = tag(xml, "mode"),
            nrEarfcn = tagAny(xml, "nrearfcn", "nrarfcn", "nr_earfcn"),
            nrBand = tagAny(xml, "nrband", "nr_band"),
            nrRsrp = stripUnitSuffix(tagAny(xml, "nrrsrp", "nr_rsrp")),
            nrRsrq = stripUnitSuffix(tagAny(xml, "nrrsrq", "nr_rsrq")),
            nrSinr = stripUnitSuffix(tagAny(xml, "nrsinr", "nr_sinr"))
        )
    }

    /** GET /api/monitoring/status — نوع الشبكة وحالة الاتصال وعدد مستخدمي الواي فاي */
    suspend fun networkStatus(): Result<NetworkStatus> = get("/api/monitoring/status").map { xml ->
        NetworkStatus(
            currentNetworkTypeRaw = tag(xml, "CurrentNetworkType"),
            connectionStatus = tag(xml, "ConnectionStatus"),
            signalIcon = tag(xml, "SignalIcon"),
            currentWifiUser = tag(xml, "CurrentWifiUser"),
            totalWifiUser = tag(xml, "TotalWifiUser")
        )
    }

    /** GET /api/device/information — اسم الجهاز وإصدار السوفتوير (بعد فك HTML entities) */
    suspend fun deviceInfo(): Result<DeviceInfo> = get("/api/device/information").map { xml ->
        DeviceInfo(
            deviceName = decodeHtmlEntities(tag(xml, "DeviceName")),
            softwareVersion = decodeHtmlEntities(tag(xml, "SoftwareVersion")),
            serialNumber = tag(xml, "SerialNumber"),
            imei = tag(xml, "Imei"),
            hardwareVersion = tag(xml, "HardwareVersion"),
            productFamily = tag(xml, "ProductFamily"),
            macAddress = tagAny(xml, "MacAddress1", "MacAddress")
        )
    }

    /** GET /api/net/net-mode — وضع الشبكة الحالي */
    suspend fun currentNetMode(): Result<NetModeInfo> = get("/api/net/net-mode").map { xml ->
        NetModeInfo(
            networkMode = tag(xml, "NetworkMode"),
            networkBand = tag(xml, "NetworkBand"),
            lteBand = tag(xml, "LTEBand")
        )
    }

    /**
     * POST /api/net/net-mode — تطبيق وضع الشبكة + قفل LTE.
     *
     * الطلب يحمل 3 حقول فقط: NetworkMode و NetworkBand و LTEBand.
     * لا يوجد حقل NR في هذا الطلب — اختيار نطاقات NR في الواجهة هو
     * تفضيل محلي فقط، ولا يُرسل للراوتر، والواجهة تقول ذلك صراحة.
     *
     * قيم NetworkMode (huawei-lte-api):
     *  "00" تلقائي، "03" 4G فقط، "04" 5G NSA، "05" 5G SA، "06" 5G فقط (NSA+SA).
     * بعد الإرسال ننتظر 8 ثوانٍ (الراديو يعيد التسجيل) ثم نعيد قراءة
     * الوضع من الراوتر — هذه إعادة القراءة هي الحكم، لأن رد الـ POST
     * نفسه أحياناً يكون كود خطأ حتى مع نجاح التطبيق.
     */
    suspend fun applyNetMode(networkMode: String, lteBands: Set<Int>): Result<NetModeApplyResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lteHex = bandMaskHex(lteBands)
                val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>$networkMode</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>$lteHex</LTEBand></request>"""
                val raw = postRaw("/api/net/net-mode", xml).getOrElse { throw it }
                delay(8000)
                val readBack = currentNetMode().getOrNull()
                val applied = readBack?.networkMode?.trim() == networkMode.trim() &&
                    (lteBands.isEmpty() || readBack.lteBand?.trim()?.equals(lteHex, ignoreCase = true) == true)
                NetModeApplyResult(applied = applied, rawResponse = raw.ifBlank { "(رد فارغ)" }, readBack = readBack)
            }
        }

    /** GET /api/wlan/host-list — الأجهزة المتصلة بالواي فاي */
    suspend fun hostList(): Result<List<HostInfo>> = get("/api/wlan/host-list").map { xml ->
        val hostBlocks = Regex("<Host>(.*?)</Host>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        hostBlocks.map { h ->
            HostInfo(
                hostName = decodeHtmlEntities(tag(h, "HostName")),
                macAddress = tag(h, "MacAddress"),
                ipAddress = tag(h, "IpAddress")
            )
        }
    }

    /** GET /api/monitoring/traffic-statistics — سرعات وإجمالي الاستهلاك */
    suspend fun trafficStatistics(): Result<TrafficStatistics> =
        get("/api/monitoring/traffic-statistics").map { xml ->
            TrafficStatistics(
                currentDownloadRate = tag(xml, "CurrentDownloadRate"),
                currentUploadRate = tag(xml, "CurrentUploadRate"),
                totalDownload = tag(xml, "TotalDownload"),
                totalUpload = tag(xml, "TotalUpload"),
                currentConnectTime = tag(xml, "CurrentConnectTime"),
                totalConnectTime = tag(xml, "TotalConnectTime")
            )
        }

    /**
     * اختبار سرعة تنزيل حقيقي: تنزيل ملف من Cloudflare مع قياس الوقت.
     * يقيس سرعة التنزيل من الإنترنت عبر الراوتر (نفس الشبكة التي يتصل بها الهاتف).
     * لا يوجد اختبار رفع هنا — تركناه عمداً بدلاً من اختراع رقم.
     * onProgress يُستدعى أثناء التنزيل: (بايتات، ميجابت/ث لحظية).
     */
    suspend fun speedTest(
        onProgress: (bytesRead: Long, mbps: Double) -> Unit
    ): Result<SpeedTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=25000000")
                .get().build()
            val start = System.nanoTime()
            var total = 0L
            speedClient.newCall(req).execute().use { res ->
                check(res.isSuccessful) { "فشل اختبار السرعة: HTTP ${res.code}" }
                val body = res.body ?: error("رد فارغ من خادم اختبار السرعة")
                val buf = ByteArray(8192)
                var n: Int
                val stream = body.byteStream()
                while (stream.read(buf).also { n = it } != -1) {
                    total += n
                    val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
                    if (elapsedSec > 0) {
                        onProgress(total, total * 8.0 / elapsedSec / 1_000_000.0)
                    }
                }
            }
            val durationMs = (System.nanoTime() - start) / 1_000_000
            val seconds = durationMs / 1000.0
            check(seconds > 0 && total > 0) { "اختبار السرعة لم ينزّل أي بيانات" }
            SpeedTestResult(
                avgMbps = total * 8.0 / seconds / 1_000_000.0,
                bytesDownloaded = total,
                durationMs = durationMs
            )
        }
    }

    companion object {
        /**
         * قفل الخلية (PCI): لا يوجد Endpoint موثّق في HiLink API
         * لقفل الخلية على H153-381، لذلك لا نعرض زر قفل خلية وهمي —
         * الواجهة تقول "غير مدعوم بعد على هذا الموديل" صراحة.
         * كذلك قفل 5G NR: طلب net-mode لا يحمل حقل NR.
         */
        fun bandMaskHex(bands: Set<Int>): String {
            if (bands.isEmpty()) return "7FFFFFFFFFFFFFFF"
            var mask = 0L
            bands.forEach { n -> if (n in 1..63) mask = mask or (1L shl (n - 1)) }
            return mask.toString(16).uppercase()
        }

        /** تنسيق بايت/ثانية إلى قراءة بشرية (KB/s أو MB/s) */
        fun formatBytesPerSecond(raw: String?): String {
            val v = raw?.trim()?.toLongOrNull() ?: return "—"
            return when {
                v >= 1_000_000 -> "%.2f MB/s".format(v / 1_000_000.0)
                v >= 1_000 -> "%.1f KB/s".format(v / 1_000.0)
                else -> "$v B/s"
            }
        }

        /** تنسيق إجمالي البايتات إلى MB أو GB */
        fun formatTotalBytes(raw: String?): String {
            val v = raw?.trim()?.toLongOrNull() ?: return "—"
            return when {
                v >= 1_073_741_824 -> "%.2f GB".format(v / 1_073_741_824.0)
                v >= 1_048_576 -> "%.1f MB".format(v / 1_048_576.0)
                v >= 1024 -> "%.1f KB".format(v / 1024.0)
                else -> "$v B"
            }
        }

        /** تنسيق ثواني الاتصال إلى ساعات:دقائق */
        fun formatConnectTime(raw: String?): String {
            val v = raw?.trim()?.toLongOrNull() ?: return "—"
            val h = v / 3600
            val m = (v % 3600) / 60
            return "%d ساعة %d دقيقة".format(h, m)
        }

        /** تقريب ميجابت/ثانية لرقم واحد عشري */
        fun formatMbps(v: Double): String = "%.1f".format(v)
    }
}

class SimpleCookieJar : okhttp3.CookieJar {
    private val store = mutableMapOf<String, List<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        store[url.host] = cookies
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
        store[url.host] ?: emptyList()
}
