package com.muse.huaweiroutermaster.data

/**
 * قيم الإشارة من GET /api/device/signal — حقيقية فقط.
 * أي حقل لا يرجعه الراوتر يبقى null ويظهر "—" في الواجهة،
 * ولا نخترع له قيمة أبداً.
 *
 * ملاحظة مهمة (مصحّحة من نسخة سابقة):
 * EARFCN يأتي فقط من <earfcn>.
 * <dlbandwidth> هو عرض القناة (مثلاً 20MHz) وليس EARFCN.
 *
 * حقول NR (nrearfcn / nrband / nrrsrp …) يرجعها الراوتر فقط
 * عندما يكون متصلاً بـ 5G NSA — وإلا تبقى null وتظهر "—".
 * الراوتر H153-381 أظهر في اختبار حقيقي: LTE B3 (EARFCN 1850)
 * مع تجميع NR n41 (NR-ARFCN 505992).
 */
data class SignalInfo(
    val rsrp: String? = null,
    val rsrq: String? = null,
    val sinr: String? = null,
    val rssi: String? = null,
    val band: String? = null,
    val pci: String? = null,
    val earfcn: String? = null,
    val cellId: String? = null,
    val bandwidth: String? = null, // dlbandwidth
    val ulBandwidth: String? = null, // ulbandwidth
    val mode: String? = null,
    // حقول 5G NR — موجودة فقط عند اتصال NSA، وإلا null
    val nrEarfcn: String? = null, // nrearfcn / nrarfcn
    val nrBand: String? = null, // nrband
    val nrRsrp: String? = null, // nrrsrp
    val nrRsrq: String? = null, // nrrsrq
    val nrSinr: String? = null // nrsinr
) {
    /**
     * عرض Carrier Aggregation من الحقول الحقيقية فقط.
     * الأساسي LTE من <band> و <earfcn>، والثانوي NR من <nrearfcn>/<nrband>.
     * إن لم يرجع الراوتر حقل NR، نعرض "—" ولا نخترع رقماً.
     */
    val primaryCaLabel: String
        get() = if (band.isNullOrBlank() && earfcn.isNullOrBlank()) "—"
        else "LTE" + (band?.let { " B$it" } ?: "") +
            (earfcn?.let { " — EARFCN $it" } ?: "") +
            (bandwidth?.let { " — $it MHz" } ?: "")

    val secondaryCaLabel: String
        get() = if (nrEarfcn.isNullOrBlank() && nrBand.isNullOrBlank()) "— (الراوتر لم يرجع حقل NR)"
        else "NR" + (nrBand?.let { " n$it" } ?: "") +
            (nrEarfcn?.let { " — NR-ARFCN $it" } ?: "") +
            (nrRsrp?.let { " — RSRP $it" } ?: "")

    /** ترجمة صادقة لكود الوضع. الكود 7 هو LTE في HiLink API. */
    val modeLabel: String
        get() = when (mode?.trim()) {
            null -> "—"
            "0" -> "لا توجد خدمة"
            "7" -> "LTE"
            else -> "كود $mode"
        }
}

/** من GET /api/monitoring/status */
data class NetworkStatus(
    val currentNetworkTypeRaw: String? = null,
    val connectionStatus: String? = null,
    val signalIcon: String? = null,
    val currentWifiUser: String? = null,
    val totalWifiUser: String? = null
) {
    /** ترجمة صادقة لكود نوع الشبكة. الكود غير المعروف يُعرض كما هو. */
    val networkTypeLabel: String
        get() = when (currentNetworkTypeRaw?.trim()) {
            null -> "—"
            "0" -> "لا توجد خدمة"
            "19" -> "4G LTE"
            "101" -> "5G"
            "102" -> "5G"
            else -> "نوع الشبكة (كود $currentNetworkTypeRaw)"
        }
}

/** من GET /api/device/information — SoftwareVersion يُفكّ ترميز HTML entities */
data class DeviceInfo(
    val deviceName: String? = null,
    val softwareVersion: String? = null,
    val serialNumber: String? = null,
    val imei: String? = null,
    val hardwareVersion: String? = null,
    val productFamily: String? = null,
    val macAddress: String? = null
)

/** وضع الشبكة الحالي من GET /api/net/net-mode */
data class NetModeInfo(
    val networkMode: String? = null,
    val networkBand: String? = null,
    val lteBand: String? = null
) {
    val modeLabel: String
        get() = when (networkMode?.trim()) {
            null -> "—"
            "00" -> "تلقائي (Auto)"
            "03" -> "4G فقط"
            "04" -> "5G NSA"
            "05" -> "5G SA"
            "06" -> "5G فقط (NSA+SA)"
            else -> "كود $networkMode"
        }
}

/** جهاز متصل — عنصر واحد من GET /api/wlan/host-list */
data class HostInfo(
    val hostName: String? = null,
    val macAddress: String? = null,
    val ipAddress: String? = null
)

/** من GET /api/monitoring/traffic-statistics — السرعات بايت/ثانية، والإجمالي بايت */
data class TrafficStatistics(
    val currentDownloadRate: String? = null,
    val currentUploadRate: String? = null,
    val totalDownload: String? = null,
    val totalUpload: String? = null,
    val currentConnectTime: String? = null,
    val totalConnectTime: String? = null
)

/** بروفايل جاهز: وضع شبكة + نطاقات LTE + تفضيل NR محلي (لا يُرسل للراوتر) */
data class ReadyProfile(
    val name: String,
    val description: String,
    val networkMode: String,
    val lteBands: Set<Int>,
    val nrPreference: Set<Int>
) {
    companion object {
        val ALL = listOf(
            ReadyProfile(
                "أسرع 5G", "أولوية 5G مع نطاقات LTE السريعة",
                "04", setOf(1, 3, 7), setOf(41, 78)
            ),
            ReadyProfile(
                "تغطية أوسع", "تلقائي مع نطاقات التغطية البعيدة",
                "00", setOf(8, 20, 28), setOf(28)
            ),
            ReadyProfile(
                "ثبات للألعاب والبث", "4G فقط على B3 و B7 لتقليل التنقل بين الأبراج",
                "03", setOf(3, 7), emptySet()
            ),
            ReadyProfile(
                "4G مستقرة", "4G فقط على B3 — نطاق واحد ثابت",
                "03", setOf(3), emptySet()
            )
        )
    }
}
