package com.stegpony.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val core = remember { coreVersion() }
    val format = remember { formatVersion() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
