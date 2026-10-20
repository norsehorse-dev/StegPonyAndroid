package com.stegpony.android.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section

/** Development build: keys arrive in the next step of Phase C. */
@Composable
fun KeysScreen() {
    SecureScreenEffect()
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.tab_keys)) {
            Text(stringResource(R.string.keys_coming), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
