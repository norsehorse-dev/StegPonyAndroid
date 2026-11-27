package com.stegpony.android.ui.reveal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.pgpony.PgponyKey
import com.stegpony.android.ui.common.ListDivider
import com.stegpony.android.ui.keys.groupFingerprint
import com.stegpony.android.ui.theme.KeyMono

/**
 * PGPony's Curve25519 keys, for the user to pick the one to reveal with. Each
 * row shows the key's name, the subkey fingerprint PGPony will show again in
 * its confirmation, and where the secret is held.
 */
@Composable
fun PgponyKeyDialog(keys: List<PgponyKey>, onPick: (PgponyKey) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pgpony_choose_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.pgpony_choose_about),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                keys.forEachIndexed { i, key ->
                    if (i > 0) ListDivider()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(key) }
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            key.label.ifBlank { stringResource(R.string.pgpony_key_unnamed) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            groupFingerprint(key.subkeyFingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = KeyMono,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (key.onCard) Icons.Outlined.Key else Icons.Outlined.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(if (key.onCard) R.string.pgpony_key_on_card else R.string.pgpony_key_on_phone),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
