package com.stegpony.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.stegpony.android.keys.CoverHistory
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.BuildConfig
import com.stegpony.android.R
import com.stegpony.android.ui.common.Section
import uniffi.stegpony_ffi.coreVersion
import uniffi.stegpony_ffi.formatVersion

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var historyOn by remember { mutableStateOf(CoverHistory.isEnabled(context)) }
    var remembered by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { remembered = CoverHistory.count(context) }
    val core = remember { coreVersion() }
    val format = remember { formatVersion() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.covers_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cover_history_switch), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.cover_history_about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = historyOn,
                    onCheckedChange = { on ->
                        historyOn = on
                        scope.launch {
                            CoverHistory.setEnabled(context, on)
                            remembered = CoverHistory.count(context)
                        }
                    },
                )
            }
            if (historyOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringCount(context, remembered),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                CoverHistory.clear(context)
                                remembered = 0
                            }
                        },
                        enabled = remembered > 0,
                    ) { Text(stringResource(R.string.cover_history_forget)) }
                }
            }
        }
        Section(stringResource(R.string.about_title)) {
            Text(stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_core_version, core), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_format_version, format.toInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Section(stringResource(R.string.limits_title)) {
            Text(stringResource(R.string.limits_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun pluralStringCount(context: android.content.Context, n: Int): String =
    context.resources.getQuantityString(R.plurals.cover_history_count, n, n)
