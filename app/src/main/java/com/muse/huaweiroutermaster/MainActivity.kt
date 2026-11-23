package com.muse.huaweiroutermaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muse.huaweiroutermaster.data.HuaweiRouterClient
import com.muse.huaweiroutermaster.data.ReadyProfile
import com.muse.huaweiroutermaster.data.SignalInfo
import com.muse.huaweiroutermaster.ui.RouterUiState
import com.muse.huaweiroutermaster.ui.RouterViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RouterViewModel = viewModel()
            val state by vm.state.collectAsState()
            // واجهة عربية RTL + وضع داكن افتراضياً
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = if (state.darkMode) darkColorScheme() else lightColorScheme()) {
                    if (state.isLoggedIn) DashboardScreen(state, vm) else LoginScreen(state, vm)
                }
            }
        }
    }
}

private fun orDash(v: String?): String = if (v.isNullOrBlank()) "—" else v
/** القيمة هنا منزوعة الوحدة أصلاً في العميل — نضيف الوحدة مرة واحدة بس */
private fun formatUnit(v: String?, unit: String): String = if (v.isNullOrBlank()) "—" else "$v $unit"

private val LTE_BANDS = listOf(1, 3, 7, 8, 20, 28)
private val NR_BANDS = listOf(1, 3, 28, 41, 77, 78)
private val MODES = listOf(
    "00" to "تلقائي (Auto)",
    "03" to "4G فقط",
    "04" to "5G NSA",
    "05" to "5G SA",
    "06" to "5G فقط (NSA+SA)"
)

@Composable
private fun LoginScreen(state: RouterUiState, vm: RouterViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Huawei Router Master", fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("التحكم في راوتر هواوي 4G / 5G", fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("دخول أصلي من داخل التطبيق — بدون متصفح وبدون WebView", fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.ip, onValueChange = vm::setIp, label = { Text("IP الراوتر") },
            placeholder = { Text("192.168.8.1") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.username, onValueChange = vm::setUsername, label = { Text("اسم المستخدم") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password, onValueChange = vm::setPassword, label = { Text("كلمة السر") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.savePassword, onCheckedChange = vm::setSavePassword)
            Text("حفظ كلمة السر على الهاتف")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الوضع الداكن")
            Spacer(Modifier.weight(1f))
            Switch(checked = state.darkMode, onCheckedChange = vm::setDarkMode)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::login, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLoading) "جارٍ الاتصال…" else "تسجيل الدخول")
        }
        if (state.isLoading) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text("لازم تكون متصل بواي فاي الراوتر نفسه. الافتراضي في معظم روترات هواوي: 192.168.8.1", fontSize = 13.sp)
        Text("المرحلة 2: تحكم في وضع الشبكة وقفل LTE، أجهزة متصلة، إحصائيات استهلاك، واختبار سرعة تنزيل حقيقي. قفل الخلية غير مدعوم بعد على H153-381 — مفيش زر وهمي ليه.", fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardScreen(state: RouterUiState, vm: RouterViewModel) {
    val s: SignalInfo? = state.signal
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Huawei Router Master", fontSize = 20.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = vm::logout) { Text("خروج") }
        }
        Text("متصل: ${state.ip}", fontSize = 14.sp)
        state.device?.deviceName?.let { Text("الجهاز: $it", fontSize = 14.sp) }
        state.device?.softwareVersion?.let { Text("إصدار السوفتوير: $it", fontSize = 14.sp) }
        state.device?.hardwareVersion?.let { Text("إصدار الهاردوير: $it", fontSize = 14.sp) }
        state.device?.serialNumber?.let { Text("السيريال: $it", fontSize = 14.sp) }
        state.lastUpdate?.let { Text("آخر تحديث: $it", fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))

        // ---- الإشارة ----
        SectionTitle("الإشارة — بيانات حقيقية من الراوتر")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalCard("RSRP", formatUnit(s?.rsrp, "dBm"), Modifier.weight(1f))
            SignalCard("RSRQ", formatUnit(s?.rsrq, "dB"), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalCard("SINR", formatUnit(s?.sinr, "dB"), Modifier.weight(1f))
            SignalCard("RSSI", formatUnit(s?.rssi, "dBm"), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        // ---- تجميع الحامل CA ----
        SectionTitle("تجميع الحامل (Carrier Aggregation)")
        InfoRow("الأساسي", s?.primaryCaLabel ?: "—")
        InfoRow("الثانوي", s?.secondaryCaLabel ?: "—")
        if (!s?.nrRsrp.isNullOrBlank()) InfoRow("NR RSRP", formatUnit(s?.nrRsrp, "dBm"))
        if (!s?.nrSinr.isNullOrBlank()) InfoRow("NR SINR", formatUnit(s?.nrSinr, "dB"))
        Spacer(Modifier.height(16.dp))

        // ---- الشبكة والخلية ----
        SectionTitle("الشبكة والخلية")
        InfoRow("نوع الشبكة", state.network?.networkTypeLabel ?: "—")
        InfoRow("التردد / Band", orDash(s?.band))
        InfoRow("PCI", orDash(s?.pci))
        InfoRow("Cell ID", orDash(s?.cellId))
        InfoRow("EARFCN", "DL: ${orDash(s?.earfcn)}")
        InfoRow("عرض القناة", s?.bandwidth?.let { "$it MHz" } ?: "—")
        InfoRow("الوضع", s?.modeLabel ?: "—")
        InfoRow("حالة الاتصال", orDash(state.network?.connectionStatus))
        InfoRow("الوضع الحالي في الراوتر", state.netMode?.modeLabel ?: "—")
        state.netMode?.lteBand?.let { InfoRow("قفل LTE الحالي (Hex)", it) }
        Spacer(Modifier.height(16.dp))

        // ---- التحكم في الشبكة والترددات ----
        SectionTitle("التحكم في الشبكة والترددات")
        Text("وضع الشبكة:", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MODES.forEach { (code, label) ->
                if (state.selectedMode == code) {
                    Button(onClick = { vm.setSelectedMode(code) }) { Text(label, fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { vm.setSelectedMode(code) }) { Text(label, fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("قفل نطاقات LTE (يُرسل للراوتر):", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LTE_BANDS.forEach { b ->
                if (b in state.selectedLteBands) {
                    Button(onClick = { vm.toggleLteBand(b) }) { Text("B$b", fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { vm.toggleLteBand(b) }) { Text("B$b", fontSize = 13.sp) }
                }
            }
        }
        Text("لو مفيش نطاق مختار، الراوتر يستخدم كل النطاقات.", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("نطاقات 5G NR (تفضيل محلي فقط):", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NR_BANDS.forEach { b ->
                if (b in state.selectedNrBands) {
                    Button(onClick = { vm.toggleNrBand(b) }) { Text("n$b", fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { vm.toggleNrBand(b) }) { Text("n$b", fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        WarningCard("تنبيه مهم: طلب net-mode بتاع هواوي بيحمل وضع الشبكة وقفل LTE بس — مفيهوش حقل NR. نطاقات NR اللي اخترتها فوق تفضيل محلي بس، ومش بتتبعت للراوتر، عشان منخترعش حقل وهمي ممكن الراوتر يرفض بسببه الطلب كله.")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = vm::applyNetMode,
            enabled = !state.isApplyingNetMode && !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isApplyingNetMode) "جارٍ التطبيق والتحقق… (حوالي 10 ثوانٍ)" else "تطبيق على الراوتر")
        }
        state.netModeApplied?.let { applied ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (applied) "✓ تم التطبيق وتأكدنا بإعادة القراءة من الراوتر."
                else "✗ الراوتر لم يثبّت الإعداد الجديد — بقي على الوضع السابق.",
                color = if (applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        state.lastNetModeRaw?.let { raw ->
            Spacer(Modifier.height(8.dp))
            Text("الرد الخام من الراوتر:", fontSize = 13.sp)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(raw, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- البروفايلات الجاهزة ----
        SectionTitle("بروفايلات جاهزة")
        ReadyProfile.ALL.forEach { p ->
            OutlinedButton(onClick = { vm.applyProfile(p) }, modifier = Modifier.fillMaxWidth()) {
                Text("${p.name} — ${p.description}", fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))

        // ---- الأجهزة المتصلة ----
        SectionTitle("الأجهزة المتصلة (${state.hosts.size})")
        if (state.hosts.isEmpty()) {
            Text("لا توجد أجهزة معروضة — اضغط \"تحديث الآن\"، أو الراوتر لم يرجع قائمة.", fontSize = 13.sp)
        } else {
            state.hosts.forEach { h ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(orDash(h.hostName), fontSize = 14.sp)
                        Text("MAC: ${orDash(h.macAddress)}", fontSize = 12.sp)
                        Text("IP: ${orDash(h.ipAddress)}", fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- إحصائيات الاستهلاك ----
        SectionTitle("استهلاك البيانات")
        val t = state.traffic
        InfoRow("سرعة التنزيل الآن", HuaweiRouterClient.formatBytesPerSecond(t?.currentDownloadRate))
        InfoRow("سرعة الرفع الآن", HuaweiRouterClient.formatBytesPerSecond(t?.currentUploadRate))
        InfoRow("إجمالي التنزيل", HuaweiRouterClient.formatTotalBytes(t?.totalDownload))
        InfoRow("إجمالي الرفع", HuaweiRouterClient.formatTotalBytes(t?.totalUpload))
        InfoRow("مدة الاتصال الحالية", HuaweiRouterClient.formatConnectTime(t?.currentConnectTime))
        Spacer(Modifier.height(16.dp))

        // ---- اختبار السرعة ----
        SectionTitle("اختبار سرعة التنزيل")
        Text("قياس حقيقي: تنزيل ملف من الإنترنت عبر الراوتر وقياس الوقت. لا يوجد اختبار رفع هنا عمداً — بدلاً من رقم مخترع.", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::startSpeedTest,
            enabled = !state.speedTestRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.speedTestRunning) "جارٍ الاختبار…" else "ابدأ اختبار السرعة")
        }
        if (state.speedTestRunning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "نزّلنا ${"%.1f".format(state.speedTestBytes / 1_048_576.0)} MB — السرعة الآن ${HuaweiRouterClient.formatMbps(state.speedTestProgressMbps)} Mbps",
                fontSize = 13.sp
            )
        }
        state.speedTestResult?.let { r ->
            Spacer(Modifier.height(8.dp))
            Text(
                "النتيجة: متوسط ${HuaweiRouterClient.formatMbps(r.avgMbps)} Mbps — نزّل ${"%.1f".format(r.bytesDownloaded / 1_048_576.0)} MB في ${"%.1f".format(r.durationMs / 1000.0)} ثانية",
                fontSize = 14.sp
            )
        }
        state.speedTestError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        // ---- الأبراج المفضلة + قفل الخلية ----
        SectionTitle("الأبراج المفضلة وقفل الخلية")
        OutlinedButton(onClick = vm::saveCurrentTower, modifier = Modifier.fillMaxWidth()) {
            Text("حفظ البرج الحالي (PCI ${orDash(s?.pci)} — EARFCN ${orDash(s?.earfcn)})")
        }
        Text("الحفظ ده محلي على الهاتف بس — مش أمر للراوتر.", fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        if (state.favouriteTowers.isEmpty()) {
            Text("لا توجد أبراج محفوظة بعد.", fontSize = 13.sp)
        } else {
            state.favouriteTowers.forEach { tower ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(tower, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    OutlinedButton(onClick = { vm.removeFavouriteTower(tower) }) { Text("حذف") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        WarningCard("قفل الخلية: غير مدعوم بعد على موديل H153-381 — لا يوجد Endpoint موثّق في HiLink API لقفل خلية بـ PCI على هذا الموديل، لذلك لا يوجد زر قفل خلية هنا بدلاً من زر وهمي.")
        Spacer(Modifier.height(16.dp))

        // ---- التحديث ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("تحديث تلقائي كل 5 ثوانٍ")
            Spacer(Modifier.weight(1f))
            Switch(checked = state.autoRefresh, onCheckedChange = vm::setAutoRefresh)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الوضع الداكن")
            Spacer(Modifier.weight(1f))
            Switch(checked = state.darkMode, onCheckedChange = vm::setDarkMode)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::refreshAll, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLoading) "جارٍ التحديث…" else "تحديث الآن")
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
        Spacer(Modifier.height(12.dp))
        Text("ملاحظة: قيم \"—\" تعني أن الراوتر لم يرجع هذه القيمة، وليست صفراً ولا قيمة مخترعة.", fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SignalCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontSize = 14.sp)
            Text(value, fontSize = 20.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}
