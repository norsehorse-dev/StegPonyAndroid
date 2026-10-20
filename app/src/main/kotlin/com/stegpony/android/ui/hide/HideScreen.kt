package com.stegpony.android.ui.hide

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.keys.Keys
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.ui.common.CapacityMeter
import com.stegpony.android.ui.common.CreateTypedDocument
import com.stegpony.android.ui.common.NewDocument
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section
import com.stegpony.android.ui.common.detectabilityColor
import com.stegpony.android.ui.common.detectabilityLabel
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.ImageFormat
import uniffi.stegpony_ffi.ImageMode

@Composable
fun HideScreen(vm: HideViewModel) {
    // The message and passphrases are on this screen too.
    SecureScreenEffect()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickCover)
    }
    val pickImageFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickCover)
    }
    val pickPayloadFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickFile)
    }
    val saveImage = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        uri?.let(vm::save)
    }

    val keys = (Keyring.state.collectAsState().value as? KeyringState.Ready)?.keys ?: Keys()
    val cover = vm.cover
    val capacity = vm.capacity
    val payloadLen = vm.payloadLen
    val fit = vm.fit
    val result = vm.result

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.hide_cover_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = !vm.busy,
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Text(stringResource(R.string.pick_photo), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { pickImageFile.launch(arrayOf("image/jpeg", "image/png")) },
                    enabled = !vm.busy,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Text(stringResource(R.string.pick_file), Modifier.padding(start = 8.dp))
                }
            }
            if (cover != null) {
                Text(cover.file.name, style = MaterialTheme.typography.bodyMedium)
                if (cover.modes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cover.modes.forEach { m ->
                            FilterChip(
                                selected = vm.mode == m,
                                onClick = { vm.selectMode(m) },
                                label = { Text(stringResource(if (m == ImageMode.JPEG) R.string.mode_jpeg else R.string.mode_lossless)) },
                                enabled = !vm.busy,
                            )
                        }
                    }
                }
                Text(
                    stringResource(
                        when {
                            vm.mode == ImageMode.JPEG -> R.string.mode_jpeg_about
                            cover.format == ImageFormat.JPEG -> R.string.mode_lossless_from_jpeg_about
                            else -> R.string.mode_lossless_about
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.hide_cover_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.reading_cover), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section(stringResource(R.string.hide_message_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.payloadKind == PayloadKind.Text,
                    onClick = { vm.payloadKind = PayloadKind.Text },
                    label = { Text(stringResource(R.string.payload_text)) },
                )
                FilterChip(
                    selected = vm.payloadKind == PayloadKind.File,
                    onClick = { vm.payloadKind = PayloadKind.File },
                    label = { Text(stringResource(R.string.payload_file)) },
                )
            }
            when (vm.payloadKind) {
                PayloadKind.Text -> OutlinedTextField(
                    value = vm.text,
                    onValueChange = { vm.text = it },
                    label = { Text(stringResource(R.string.message_label)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                PayloadKind.File -> {
                    OutlinedButton(onClick = { pickPayloadFile.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Text(stringResource(R.string.pick_payload_file), Modifier.padding(start = 8.dp))
                    }
                    vm.file?.let { f ->
                        val size = f.size?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                        Text("${f.name} ($size)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (capacity != null) {
                if (payloadLen != null) {
                    CapacityMeter(
                        used = payloadLen,
                        max = capacity.maxPayloadBytes.toLong(),
                        rating = fit?.detectability,
                    )
                } else {
                    Text(
                        if (capacity.lowPayloadBytes > 0uL) {
                            stringResource(
                                R.string.capacity_summary,
                                capacity.maxTextBytes.toLong(),
                                capacity.lowPayloadBytes.toLong(),
                            )
                        } else {
                            stringResource(R.string.capacity_summary_no_low, capacity.maxTextBytes.toLong())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section(stringResource(R.string.hide_key_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.keyKind == KeyKind.Passphrase,
                    onClick = { vm.keyKind = KeyKind.Passphrase },
                    label = { Text(stringResource(R.string.key_passphrase)) },
                )
                FilterChip(
                    selected = vm.keyKind == KeyKind.Recipient,
                    onClick = { vm.keyKind = KeyKind.Recipient },
                    label = { Text(stringResource(R.string.key_recipient)) },
                )
            }
            when (vm.keyKind) {
                KeyKind.Passphrase -> {
                    PassphraseField(vm.passphrase, { vm.passphrase = it }, stringResource(R.string.passphrase_label))
                    PassphraseField(
                        vm.passphraseAgain,
                        { vm.passphraseAgain = it },
                        stringResource(R.string.passphrase_again_label),
                        isError = vm.passphraseAgain.isNotEmpty() && vm.passphraseAgain != vm.passphrase,
                    )
                }
                KeyKind.Recipient -> {
                    val saved = (
                        keys.contacts.map { it.name to it.recipient } +
                            keys.mine.map { stringResource(R.string.recipient_yours, it.label) to it.recipient }
                        ).distinctBy { it.second }
                    // A picked key that has since been deleted falls back to pasting.
                    LaunchedEffect(saved) {
                        val picked = vm.pickedRecipient
                        if (picked != null && saved.none { it.second == picked }) vm.pickedRecipient = null
                    }
                    saved.forEach { (name, r) ->
                        RecipientOption(name, vm.pickedRecipient == r) { vm.pickedRecipient = r }
                    }
                    if (saved.isNotEmpty()) {
                        RecipientOption(stringResource(R.string.recipient_paste), vm.pickedRecipient == null) {
                            vm.pickedRecipient = null
                        }
                    }
                    if (vm.pickedRecipient == null) {
                        OutlinedTextField(
                            value = vm.recipient,
                            onValueChange = { vm.recipient = it },
                            label = { Text(stringResource(R.string.recipient_label)) },
                            supportingText = { Text(stringResource(R.string.recipient_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Section(stringResource(R.string.hide_decoy_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.decoy_about),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = vm.decoyOn, onCheckedChange = { vm.decoyOn = it })
            }
            if (vm.decoyOn) {
                OutlinedTextField(
                    value = vm.decoyText,
                    onValueChange = { vm.decoyText = it },
                    label = { Text(stringResource(R.string.decoy_message_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                PassphraseField(vm.decoyPassphrase, { vm.decoyPassphrase = it }, stringResource(R.string.decoy_passphrase_label))
            }
        }

        if (cover != null && vm.mode == ImageMode.JPEG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.keep_metadata), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.keep_metadata_about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.keepMetadata, onCheckedChange = { vm.keepMetadata = it })
            }
        }

        val blocker = vm.blocker
        Button(
            onClick = vm::hide,
            enabled = blocker == null && !vm.busy && !vm.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.hide_button))
        }
        if (vm.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.hiding), style = MaterialTheme.typography.bodySmall)
            }
        } else if (blocker != null && cover != null && !vm.loading) {
            Text(
                stringResource(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (result != null) {
            Section(stringResource(R.string.hide_done_title)) {
                Text(
                    stringResource(R.string.hide_done_rating, detectabilityLabel(result.detectability)),
                    color = detectabilityColor(result.detectability),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveImage.launch(NewDocument(result.name, result.mime)) }) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val share = withContext(Dispatchers.IO) {
                                    Files.shareIntent(context, Files.AREA_HIDE, result.bytes, result.name, result.mime)
                                }
                                context.startActivity(share)
                            } catch (e: Exception) {
                                vm.errorText = errorMessage(context, e)
                            }
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    stringResource(R.string.send_as_file),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecipientOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
