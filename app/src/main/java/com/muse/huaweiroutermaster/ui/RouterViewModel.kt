package com.muse.huaweiroutermaster.ui

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.huaweiroutermaster.data.DeviceInfo
import com.muse.huaweiroutermaster.data.HostInfo
import com.muse.huaweiroutermaster.data.HuaweiRouterClient
import com.muse.huaweiroutermaster.data.NetModeInfo
import com.muse.huaweiroutermaster.data.NetworkStatus
import com.muse.huaweiroutermaster.data.ReadyProfile
import com.muse.huaweiroutermaster.data.SignalInfo
import com.muse.huaweiroutermaster.data.SpeedTestResult
import com.muse.huaweiroutermaster.data.TrafficStatistics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Application.dataStore by preferencesDataStore(name = "router_master")

data class RouterUiState(
    val ip: String = "192.168.8.1",
    val username: String = "admin",
    val password: String = "",
    val savePassword: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val signal: SignalInfo? = null,
    val network: NetworkStatus? = null,
    val device: DeviceInfo? = null,
    val lastUpdate: String? = null,
    val autoRefresh: Boolean = true,
    val darkMode: Boolean = true,
    // المرحلة 2 — التحكم
    val netMode: NetModeInfo? = null,
    val selectedMode: String = "00",
    val selectedLteBands: Set<Int> = emptySet(),
    val selectedNrBands: Set<Int> = emptySet(),
    val hosts: List<HostInfo> = emptyList(),
    val traffic: TrafficStatistics? = null,
    val isApplyingNetMode: Boolean = false,
    val lastNetModeRaw: String? = null,
    val netModeApplied: Boolean? = null,
    val favouriteTowers: Set<String> = emptySet(),
    // اختبار السرعة — قياس حقيقي فقط
    val speedTestRunning: Boolean = false,
    val speedTestProgressMbps: Double = 0.0,
    val speedTestBytes: Long = 0L,
    val speedTestResult: SpeedTestResult? = null,
    val speedTestError: String? = null
)

class RouterViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RouterUiState())
    val state = _state.asStateFlow()

    private var client = HuaweiRouterClient()
    private var refreshJob: Job? = null
    private var speedJob: Job? = null

    private val KEY_IP = stringPreferencesKey("ip")
    private val KEY_USER = stringPreferencesKey("username")
    private val KEY_PASS = stringPreferencesKey("password")
    private val KEY_SAVE = booleanPreferencesKey("save_password")
    private val KEY_TOWERS = stringSetPreferencesKey("favourite_towers")

    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _state.update {
                it.copy(
                    ip = prefs[KEY_IP] ?: "192.168.8.1",
                    username = prefs[KEY_USER] ?: "admin",
                    password = if (prefs[KEY_SAVE] == true) (prefs[KEY_PASS] ?: "") else "",
                    savePassword = prefs[KEY_SAVE] ?: false,
                    favouriteTowers = prefs[KEY_TOWERS] ?: emptySet()
                )
            }
        }
    }

    fun setIp(v: String) = _state.update { it.copy(ip = v.trim(), error = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v.trim(), error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setSavePassword(v: Boolean) = _state.update { it.copy(savePassword = v) }
    fun setAutoRefresh(v: Boolean) {
        _state.update { it.copy(autoRefresh = v) }
        if (v) startAutoRefresh() else refreshJob?.cancel()
    }
    fun setDarkMode(v: Boolean) = _state.update { it.copy(darkMode = v) }

    // ---- اختيار وضع الشبكة والنطاقات (اختيار محلي حتى الضغط على "تطبيق") ----
    fun setSelectedMode(v: String) = _state.update { it.copy(selectedMode = v, netModeApplied = null) }
    fun toggleLteBand(band: Int) = _state.update {
        it.copy(
            selectedLteBands = if (band in it.selectedLteBands) it.selectedLteBands - band else it.selectedLteBands + band,
            netModeApplied = null
        )
    }
    fun toggleNrBand(band: Int) = _state.update {
        it.copy(
            selectedNrBands = if (band in it.selectedNrBands) it.selectedNrBands - band else it.selectedNrBands + band
        )
    }

    fun applyProfile(profile: ReadyProfile) {
        _state.update {
            it.copy(
                selectedMode = profile.networkMode,
                selectedLteBands = profile.lteBands,
                selectedNrBands = profile.nrPreference,
                netModeApplied = null,
                message = "تم اختيار بروفايل \"${profile.name}\" — اضغط \"تطبيق على الراوتر\" لإرساله"
            )
        }
    }

    /** إرسال وضع الشبكة + LTE للراوتر، ثم التحقق بإعادة القراءة */
    fun applyNetMode() {
        val s = _state.value
        if (!s.isLoggedIn) return
        viewModelScope.launch {
            _state.update { it.copy(isApplyingNetMode = true, error = null, message = null, netModeApplied = null) }
            val result = client.applyNetMode(s.selectedMode, s.selectedLteBands)
            result.onSuccess { r ->
                _state.update {
                    it.copy(
                        isApplyingNetMode = false,
                        lastNetModeRaw = r.rawResponse,
                        netModeApplied = r.applied,
                        netMode = r.readBack ?: it.netMode,
                        message = if (r.applied) {
                            "تم التطبيق وتأكدنا بإعادة القراءة من الراوتر"
                        } else {
                            "الراوتر لم يثبّت الإعداد الجديد — بقي على الوضع السابق. راجع الرد الخام بالأسفل"
                        }
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isApplyingNetMode = false, error = e.message ?: "فشل تطبيق الإعداد") }
            }
        }
    }

    /** حفظ البرج الحالي (PCI+EARFCN) محلياً على الهاتف فقط — ليس أمراً للراوتر */
    fun saveCurrentTower() {
        val s = _state.value
        val pci = s.signal?.pci
        val earfcn = s.signal?.earfcn
        if (pci.isNullOrBlank() || earfcn.isNullOrBlank()) {
            _state.update { it.copy(error = "لا توجد بيانات خلية حالية للحفظ") }
            return
        }
        val key = "PCI $pci — EARFCN $earfcn"
        val updated = s.favouriteTowers + key
        _state.update { it.copy(favouriteTowers = updated, message = "تم حفظ البرج محلياً على الهاتف (ليس قفلاً على الراوتر)") }
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs -> prefs[KEY_TOWERS] = updated }
        }
    }

    fun removeFavouriteTower(key: String) {
        val updated = _state.value.favouriteTowers - key
        _state.update { it.copy(favouriteTowers = updated) }
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs -> prefs[KEY_TOWERS] = updated }
        }
    }

    /** اختبار سرعة تنزيل حقيقي — لا يوجد اختبار رفع (تُرِك عمداً بدلاً من رقم مخترع) */
    fun startSpeedTest() {
        if (_state.value.speedTestRunning) return
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    speedTestRunning = true, speedTestProgressMbps = 0.0,
                    speedTestBytes = 0L, speedTestResult = null, speedTestError = null
                )
            }
            val result = client.speedTest { bytes, mbps ->
                _state.update { it.copy(speedTestProgressMbps = mbps, speedTestBytes = bytes) }
            }
            result.onSuccess { r ->
                _state.update { it.copy(speedTestRunning = false, speedTestResult = r) }
            }.onFailure { e ->
                _state.update { it.copy(speedTestRunning = false, speedTestError = e.message ?: "فشل اختبار السرعة") }
            }
        }
    }

    fun login() {
        val s = _state.value
        if (s.ip.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "املأ الـ IP واسم المستخدم وكلمة السر") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, message = null) }
            val base = "http://" + s.ip.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            client = HuaweiRouterClient(base)
            val result = client.login(s.username, s.password)
            result.onSuccess { msg ->
                // حفظ محلي على الهاتف فقط، إذا اختار المستخدم ذلك
                getApplication<Application>().dataStore.edit { prefs ->
                    prefs[KEY_IP] = s.ip
                    prefs[KEY_USER] = s.username
                    prefs[KEY_SAVE] = s.savePassword
                    if (s.savePassword) prefs[KEY_PASS] = s.password else prefs.remove(KEY_PASS)
                }
                _state.update { it.copy(isLoggedIn = true, isLoading = false, message = msg) }
                refreshAll()
                startAutoRefresh()
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message ?: "فشل تسجيل الدخول") }
            }
        }
    }

    fun refreshAll() {
        if (!_state.value.isLoggedIn) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val signal = client.signalInfo().getOrNull()
            val network = client.networkStatus().getOrNull()
            val device = client.deviceInfo().getOrNull()
            val netMode = client.currentNetMode().getOrNull()
            val hosts = client.hostList().getOrNull()
            val traffic = client.trafficStatistics().getOrNull()
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (signal == null && network == null) {
                _state.update { it.copy(isLoading = false, error = "تعذّر قراءة الإشارة — قد تكون الجلسة انتهت، سجّل الدخول من جديد") }
            } else {
                _state.update {
                    it.copy(
                        isLoading = false, signal = signal ?: it.signal,
                        network = network ?: it.network, device = device ?: it.device,
                        netMode = netMode ?: it.netMode,
                        hosts = hosts ?: it.hosts, traffic = traffic ?: it.traffic,
                        // مزامنة الاختيار مع الراوتر مرة واحدة فقط عند أول قراءة،
                        // حتى لا يمسح التحديث التلقائي اختيار المستخدم قبل أن يضغط "تطبيق"
                        selectedMode = if (it.netMode == null) {
                            netMode?.networkMode?.trim() ?: it.selectedMode
                        } else it.selectedMode,
                        lastUpdate = time
                    )
                }
            }
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                if (_state.value.isLoggedIn && _state.value.autoRefresh) refreshAll()
            }
        }
    }

    fun logout() {
        refreshJob?.cancel()
        speedJob?.cancel()
        viewModelScope.launch { runCatching { client.logout() } }
        _state.update {
            it.copy(
                isLoggedIn = false, signal = null, network = null, device = null,
                netMode = null, hosts = emptyList(), traffic = null,
                message = null, lastUpdate = null, lastNetModeRaw = null, netModeApplied = null,
                speedTestResult = null, speedTestRunning = false
            )
        }
    }
}
