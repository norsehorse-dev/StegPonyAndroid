package com.stegpony.android.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.keys.Contact
import com.stegpony.android.keys.KeySource
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.keys.MyKey
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section

/** age1qyqszqgpqyqszqgpqy…xyz12345, short enough for one line. */
fun shortKey(recipient: String): String =
    if (recipient.length <= 24) recipient else recipient.take(14) + "…" + recipient.takeLast(8)

/** Fingerprints in groups of four, the way OpenPGP tools show them. */
fun groupFingerprint(fp: String): String = fp.chunked(4).joinToString(" ")

@Composable
fun KeysScreen(vm: KeysViewModel) {
    SecureScreenEffect()
    val context = LocalContext.current
    val state by Keyring.state.collectAsState()
    val importMine = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importMine)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            KeyringState.Loading -> CircularProgressIndicator()
            is KeyringState.Failed -> Section(stringResource(R.string.keys_unavailable_title)) {
                Text(stringResource(R.string.keys_unavailable_body, s.reason), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::retryLoad, enabled = !vm.busy) {
                        Text(stringResource(R.string.keys_retry))
                    }
                    TextButton(onClick = { vm.confirmReset = true }, enabled = !vm.busy) {
                        Text(stringResource(R.string.keys_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            is KeyringState.Ready -> {
                Section(stringResource(R.string.my_keys_title)) {
                    if (s.keys.mine.isEmpty()) {
                        Text(
                            stringResource(R.string.my_keys_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    s.keys.mine.forEachIndexed { i, k ->
                        if (i > 0) HorizontalDivider()
                        MyKeyRow(
                            k,
                            onShare = { shareRecipient(context, k.recipient) },
                            onCopy = { copyRecipient(context, k.recipient) },
                            onDelete = { vm.pendingDelete = PendingDelete.Mine(k) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::startCreate, enabled = !vm.busy) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.create_key), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = { importMine.launch(arrayOf("*/*")) }, enabled = !vm.busy) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Text(stringResource(R.string.import_key), Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.import_key_about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Section(stringResource(R.string.contacts_title)) {
                    if (s.keys.contacts.isEmpty()) {
                        Text(
                            stringResource(R.string.contacts_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    s.keys.contacts.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider()
                        ContactRow(c, onDelete = { vm.pendingDelete = PendingDelete.Other(c) })
                    }
                    OutlinedButton(onClick = vm::startContact, enabled = !vm.busy) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                        Text(stringResource(R.string.add_contact), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        vm.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        vm.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }

    vm.newKeyLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { vm.newKeyLabel = null },
            title = { Text(stringResource(R.string.create_key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.create_key_about), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { vm.newKeyLabel = it },
                        label = { Text(stringResource(R.string.key_name_label)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::create) { Text(stringResource(R.string.create)) } },
            dismissButton = { TextButton(onClick = { vm.newKeyLabel = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    vm.pendingPgp?.let { pending ->
        var passphrase by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::cancelPgp,
            title = { Text(stringResource(R.string.pgp_passphrase_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pgp_passphrase_about), style = MaterialTheme.typography.bodySmall)
                    PassphraseField(
                        passphrase,
                        { passphrase = it },
                        stringResource(R.string.passphrase_label),
                        isError = pending.error != null,
                    )
                    pending.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.unlockPgp(passphrase) }, enabled = passphrase.isNotEmpty() && !vm.busy) {
                    Text(stringResource(R.string.unlock))
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelPgp) { Text(stringResource(R.string.cancel)) } },
        )
    }

    vm.contactDraft?.let { draft ->
        ContactDialog(vm, draft)
    }

    if (vm.confirmReset) {
        AlertDialog(
            onDismissRequest = { vm.confirmReset = false },
            title = { Text(stringResource(R.string.keys_reset_title)) },
            text = { Text(stringResource(R.string.keys_reset_body)) },
            confirmButton = {
                TextButton(onClick = vm::reset) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { vm.confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    vm.pendingDelete?.let { target ->
        val (title, body) = when (target) {
            is PendingDelete.Mine -> stringResource(R.string.delete_key_title, target.key.label) to
                stringResource(R.string.delete_key_body)
            is PendingDelete.Other -> stringResource(R.string.delete_contact_title, target.contact.name) to
                stringResource(R.string.delete_contact_body)
        }
        AlertDialog(
            onDismissRequest = { vm.pendingDelete = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { vm.pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MyKeyRow(k: MyKey, onShare: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(k.label, style = MaterialTheme.typography.titleSmall)
            Text(
                when (k.source) {
                    KeySource.Generated -> stringResource(R.string.source_generated)
                    KeySource.AgeImport -> stringResource(R.string.source_age)
                    KeySource.Pgp -> stringResource(R.string.source_pgp)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(shortKey(k.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            k.fingerprint?.let {
                Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.share_public_key)) }
        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_public_key)) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
    }
}

@Composable
private fun ContactRow(c: Contact, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall)
            Text(shortKey(c.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            c.fingerprint?.let {
                Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
    }
}

@Composable
private fun ContactDialog(vm: KeysViewModel, draft: ContactDraft) {
    val loadFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadContactFile)
    }
    AlertDialog(
        onDismissRequest = { vm.contactDraft = null },
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { vm.contactDraft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.contact_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val loaded = draft.loaded
                if (loaded != null) {
                    Text(stringResource(R.string.contact_key_loaded), style = MaterialTheme.typography.bodySmall)
                    Text(shortKey(loaded.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    loaded.fingerprint?.let {
                        Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    OutlinedTextField(
                        value = draft.keyText,
                        onValueChange = { vm.contactDraft = draft.copy(keyText = it, error = null) },
                        label = { Text(stringResource(R.string.contact_key_label)) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { loadFile.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                    Text(stringResource(R.string.contact_key_file), Modifier.padding(start = 8.dp))
                }
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = vm::saveContact,
                enabled = draft.loaded != null || draft.keyText.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { vm.contactDraft = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun shareRecipient(context: Context, recipient: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, recipient)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** A public key isn't secret, so this is an ordinary copy. */
private fun copyRecipient(context: Context, recipient: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("age recipient", recipient))
}
