package com.nestgallery.viewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import com.nestgallery.viewer.data.UiPrefs
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.nsfw.NsfwScannerManager
import com.nestgallery.viewer.data.nsfw.NsfwAccelerator
import com.nestgallery.viewer.data.nsfw.NsfwModel
import com.nestgallery.viewer.data.nsfw.NsfwScanService
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** App settings: whether NSFW scan is available at all, and (when it is) the hardware it runs on, with a speed test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val nsfw = remember { NsfwScannerManager.getInstance(context) }
    val uiPrefs = remember { UiPrefs.getInstance(context) }
    val nsfwEnabled by uiPrefs.nsfwEnabled.collectAsState()
    val model by nsfw.model.collectAsState()
    val status by nsfw.status.collectAsState()
    val scanning = status.activeFolder != null
    BackHandler { onBack() }

    Scaffold(
        contentWindowInsets = ScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            NsfwSwitch(nsfwEnabled) { on ->
                if (!on) nsfw.stopScan()          // hidden from now on, so don't leave a scan running out of sight
                uiPrefs.setNsfwEnabled(on)
            }
            if (!nsfwEnabled) return@Column
            Spacer(Modifier.height(20.dp))
            if (scanning) {
                Text(
                    "An NSFW scan is running. Stop it to change the hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            HardwareSection(nsfw, model, scanning)
        }
    }
}

/** The on/off switch for the whole NSFW scan feature (off by default). */
@Composable
private fun NsfwSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("NSFW scan", style = MaterialTheme.typography.titleMedium)
            Text(
                "Finds and filters explicit photos on this phone. When off, NSFW scan is hidden everywhere in the app; " +
                    "results from earlier scans are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/** Where the NSFW model runs, plus a speed test that measures every option on this phone. */
@Composable
private fun HardwareSection(nsfw: NsfwScannerManager, model: NsfwModel, scanning: Boolean) {
    val accel by nsfw.accelerator.collectAsState()
    val backend by nsfw.backend.collectAsState()
    val scope = rememberCoroutineScope()
    var crashed by remember { mutableStateOf(nsfw.crashedAccelerators()) }
    var testing by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<NsfwScannerManager.SpeedResult>() }
    var testedModel by remember { mutableStateOf<String?>(null) }

    Text("NSFW scan hardware", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    Text(
        "If the chosen hardware doesn't work on this phone, the scan falls back to the CPU." +
            (backend?.let { "\nIn use now: ${it.label}" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
    )
    Column(Modifier.selectableGroup()) {
        for (a in NsfwAccelerator.entries) {
            val selected = a == accel
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !scanning && !testing, role = Role.RadioButton) { nsfw.setAccelerator(a) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                RadioButton(selected = selected, onClick = null, enabled = !scanning && !testing, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f).padding(top = 8.dp, end = 8.dp)) {
                    Text(a.title + if (a.name in crashed) "  (crashed before, skipped)" else "", style = MaterialTheme.typography.titleSmall)
                    Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (crashed.isNotEmpty()) {
        TextButton(onClick = { nsfw.forgetCrashes(); crashed = nsfw.crashedAccelerators() }) { Text("Try crashed hardware again") }
    }

    Spacer(Modifier.height(8.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speed test", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Runs ${model.title} on each option the way a scan would. The first NPU test compiles the model (up to a minute).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !scanning && !testing,
                    onClick = {
                        testing = true
                        results.clear()
                        testedModel = model.title
                        scope.launch {
                            try {
                                nsfw.speedTest(model) { r -> scope.launch { results.add(r) } }
                            } finally {
                                testing = false
                                crashed = nsfw.crashedAccelerators()
                            }
                        }
                    }
                ) { Text(if (testing) "Testing…" else "Run") }
            }
            if (testing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val best = results.filter { it.photosPerSecond != null }.maxByOrNull { it.photosPerSecond!! }
                for (r in results) {
                    val rate = r.photosPerSecond
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(r.accelerator.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(120.dp))
                        Column(Modifier.weight(1f)) {
                            if (rate != null) {
                                Text(
                                    "%.1f photos/s · 20,000 photos ≈ %s".format(rate, NsfwScanService.formatEta((20_000 / rate).toLong())) +
                                        if (r == best) "  ★ fastest" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (r == best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            val extra = listOfNotNull(
                                r.note.takeIf { it.isNotEmpty() },
                                r.setupMs.takeIf { rate != null && it > 1500 }?.let { "setup %.1f s".format(it / 1000.0) }
                            ).joinToString(" · ")
                            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!testing) {
                    // everything, including QNN's own log lines when the NPU / GPU failed: something to paste into a report
                    val clipboard = LocalClipboardManager.current
                    TextButton(onClick = {
                        val text = buildString {
                            append("NestGallery speed test · ${testedModel ?: ""} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ")
                            append("SoC ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"} · Android ${android.os.Build.VERSION.RELEASE}\n")
                            for (r in results) {
                                append("${r.accelerator.title}: ")
                                append(r.photosPerSecond?.let { "%.2f photos/s, setup %d ms".format(it, r.setupMs) } ?: "-")
                                if (r.note.isNotEmpty()) append(" · ${r.note}")
                                append('\n')
                                if (r.details.isNotEmpty()) append(r.details.trim()).append('\n')
                            }
                        }
                        clipboard.setText(AnnotatedString(text))
                    }) { Text("Copy details") }
                }
                if (!testing && best != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Tested ${testedModel ?: ""} on a 640×480 picture; real photos add decoding time.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (best.accelerator != accel) {
                        TextButton(enabled = !scanning, onClick = { nsfw.setAccelerator(best.accelerator) }) { Text("Use ${best.accelerator.title}") }
                    }
                }
            }
        }
    }
}
