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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.nsfw.NsfwScannerManager
import com.nestgallery.viewer.data.nsfw.NsfwModels
import java.text.NumberFormat

/** App settings. For now: which NudeNet variant the NSFW scan uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val nsfw = remember { NsfwScannerManager.getInstance(context) }
    val model by nsfw.model.collectAsState()
    val status by nsfw.status.collectAsState()
    val scanning = status.activeFolder != null
    val counts by produceState(emptyMap<String, Int>(), model.id, scanning) { value = nsfw.resultCounts() }
    val n = remember { NumberFormat.getInstance() }
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
            Text("NSFW detection model", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Text(
                "Both run on this device. Each model keeps its own results: switching shows that model's results, and photos it " +
                    "has not scanned yet need a scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
            Column(Modifier.selectableGroup()) {
                for (m in NsfwModels.ALL) {
                    val selected = m.id == model.id
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clickable(enabled = !scanning, role = Role.RadioButton) { nsfw.setModel(m) }
                    ) {
                        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                            RadioButton(selected = selected, onClick = null, enabled = !scanning, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${n.format(counts[m.id] ?: 0)} photos scanned with it",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            if (scanning) {
                Text(
                    "An NSFW scan is running. Stop it to change the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
