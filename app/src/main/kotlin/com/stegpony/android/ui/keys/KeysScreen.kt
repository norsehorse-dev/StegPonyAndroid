package com.stegpony.android.ui.keys

import android.content.ClipData
import com.stegpony.android.ui.common.ControlShape
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.stegpony.android.qr.QrBitmap
import com.stegpony.android.ui.scan.QrScannerDialog
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stegpony.android.ui.theme.KeyMono
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.card.HardwareKey
import com.stegpony.android.ui.card.HardwareKeyDialog
import com.stegpony.android.ui.card.canUseHardwareKeys
import com.stegpony.android.keys.Contact
import com.stegpony.android.keys.KeySource
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.keys.MyKey
import com.stegpony.android.ui.common.ListDivider
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
    val hardwareKeysUsable = remember(context) { canUseHardwareKeys(context) }
    val importMine = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importMine)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (val s = state) {
            KeyringState.Loading -> CircularProgressIndicator()
            is KeyringState.Failed -> Section(stringResource(R.string.keys_unavailable_title)) {
                Text(stringResource(R.string.keys_unavailable_body, s.reason), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = ControlShape, onClick = vm::retryLoad, enabled = !vm.busy) {
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
                        if (i > 0) ListDivider()
                        MyKeyRow(
                            k,
                            onShare = { shareRecipient(context, k.recipient) },
                            onQr = { vm.qrFor = k },
                            onDelete = { vm.pendingDelete = PendingDelete.Mine(k) },
                        )
                    }
                    // Stacked, so long translated labels have the full width.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = ControlShape, onClick = vm::startCreate, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.create_key), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(shape = ControlShape, onClick = { importMine.launch(arrayOf("*/*")) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Text(stringResource(R.string.import_key), Modifier.padding(start = 8.dp))
                        }
                        if (hardwareKeysUsable) {
                            OutlinedButton(shape = ControlShape, onClick = { vm.addingCard = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Key, contentDescription = null)
                                Text(stringResource(R.string.add_card), Modifier.padding(start = 8.dp))
                            }
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
                        if (i > 0) ListDivider()
                        ContactRow(c, onDelete = { vm.pendingDelete = PendingDelete.Other(c) })
                    }
                    OutlinedButton(shape = ControlShape, onClick = vm::startContact, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
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

    if (vm.addingCard) {
        HardwareKeyDialog(
            title = stringResource(R.string.add_card),
            askPin = false,
            operation = { session, _ -> HardwareKey.readDecryptionKey(session) },
            onSuccess = vm::addHardware,
            onDismiss = { vm.addingCard = false },
        )
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

    if (vm.scanning) {
        QrScannerDialog(
            help = stringResource(R.string.scan_help),
            onDecoded = vm::onScanned,
            onClose = vm::stopScan,
            onCameraRefused = {
                vm.stopScan()
                vm.contactDraft = vm.contactDraft?.copy(error = context.getString(R.string.camera_refused))
            },
        )
    }

    vm.qrFor?.let { key ->
        QrDialog(key, onDismiss = { vm.qrFor = null })
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
                stringResource(
                    if (target.key.source == KeySource.Hardware) R.string.delete_card_body else R.string.delete_key_body,
                )
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

/** The first letter of a key's name in a circle: filled with the accent for your own keys, quiet for contacts. */
@Composable
private fun Avatar(name: String, mine: Boolean) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun MyKeyRow(k: MyKey, onShare: () -> Unit, onQr: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(k.label, mine = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(k.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                when (k.source) {
                    KeySource.Generated -> stringResource(R.string.source_generated)
                    KeySource.AgeImport -> stringResource(R.string.source_age)
                    KeySource.Pgp -> stringResource(R.string.source_pgp)
                    KeySource.Hardware -> stringResource(R.string.source_hardware)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(shortKey(k.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
            k.fingerprint?.let {
                Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
            }
        }
        // Kept together, so the row's spacing doesn't spread them out.
        Row {
            IconButton(onClick = onQr) { Icon(Icons.Outlined.QrCode2, stringResource(R.string.show_qr)) }
            // Share and delete sit in a menu, which leaves the key text room on a narrow phone.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_public_key)) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: Contact, onDelete: () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(c.name, mine = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(shortKey(c.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
            c.fingerprint?.let {
                Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete_named, c.name)) }
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
                    Text(shortKey(loaded.recipient), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
                    loaded.fingerprint?.let {
                        Text(groupFingerprint(it), style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
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
                Row {
                    TextButton(onClick = vm::startScan) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Text(stringResource(R.string.scan_qr), Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { loadFile.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text(stringResource(R.string.contact_key_file), Modifier.padding(start = 8.dp))
                    }
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
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context.applicationContext, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/** Your public key as a QR code, with the text to copy or share. */
@Composable
private fun QrDialog(key: MyKey, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val image = remember(key.recipient) { QrBitmap.encode(key.recipient).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.qr_image_description),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                SelectionContainer {
                    Text(key.recipient, style = MaterialTheme.typography.bodySmall, fontFamily = KeyMono)
                }
                Text(
                    stringResource(R.string.qr_about),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = { copyRecipient(context, key.recipient) }) { Text(stringResource(R.string.copy)) }
        },
    )
}
