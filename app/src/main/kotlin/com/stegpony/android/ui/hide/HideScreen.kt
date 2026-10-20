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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stegpony.android.lock.ScreenPrivacy
import com.stegpony.android.ui.camera.CameraCaptureDialog
import uniffi.stegpony_ffi.Detectability
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
import androidx.compose.ui.res.pluralStringResource
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
import uniffi.stegpony_ffi.TextMode
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.ContentCopy
import android.content.Intent
import com.stegpony.android.ui.common.Clipboard

@Composable
fun HideScreen(vm: HideViewModel) {
    // The message and passphrases are on this screen too; Settings can allow
    // screenshots here.
    if (ScreenPrivacy.secureHide) SecureScreenEffect()
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
    var takingPhoto by remember { mutableStateOf(false) }
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
        Section(stringResource(R.string.hide_in_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.carrier == Carrier.Image,
                    onClick = { vm.selectCarrier(Carrier.Image) },
                    label = { Text(stringResource(R.string.carrier_photo)) },
                    enabled = !vm.busy,
                )
                FilterChip(
                    selected = vm.carrier == Carrier.Text,
                    onClick = { vm.selectCarrier(Carrier.Text) },
                    label = { Text(stringResource(R.string.carrier_text)) },
                    enabled = !vm.busy,
                )
            }
            Text(
                stringResource(if (vm.carrier == Carrier.Image) R.string.carrier_photo_about else R.string.carrier_text_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (vm.carrier == Carrier.Image) {
            Section(stringResource(R.string.hide_cover_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val compact = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    OutlinedButton(
                        onClick = { takingPhoto = true },
                        enabled = !vm.busy && !vm.loading,
                        contentPadding = compact,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                        Text(stringResource(R.string.take_photo), Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !vm.busy && !vm.loading,
                        contentPadding = compact,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Text(stringResource(R.string.pick_photo), Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { pickImageFile.launch(arrayOf("image/jpeg", "image/png")) },
                        enabled = !vm.busy && !vm.loading,
                        contentPadding = compact,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Text(stringResource(R.string.pick_file), Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                }
                if (takingPhoto) {
                    CameraCaptureDialog(
                        onCaptured = { bytes ->
                            takingPhoto = false
                            vm.useCaptured(bytes)
                        },
                        onClose = { takingPhoto = false },
                        onCameraRefused = {
                            takingPhoto = false
                            vm.errorText = context.getString(R.string.camera_refused_cover)
                        },
                    )
                }
                if (cover != null) {
                    Text(
                        if (cover.fresh) stringResource(R.string.cover_fresh) else cover.file.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    cover.notes.forEach { note ->
                        Text(
                            stringResource(
                                when (note) {
                                    CoverNote.Reused -> R.string.cover_note_reused
                                    CoverNote.NoCameraDetails -> R.string.cover_note_not_camera
                                    CoverNote.FromDownloads -> R.string.cover_note_downloads
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (note == CoverNote.Reused) MaterialTheme.colorScheme.error else detectabilityColor(Detectability.MEDIUM),
                        )
                    }
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
        } else {
            TextCarrierSection(vm)
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
            if (vm.carrier == Carrier.Text) {
                TextSizeNote(vm)
            } else if (capacity != null) {
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

        if (vm.carrier == Carrier.Image && cover != null && vm.mode == ImageMode.JPEG) {
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
                Text(stringResource(if (vm.carrier == Carrier.Text) R.string.hiding_text else R.string.hiding), style = MaterialTheme.typography.bodySmall)
            }
        } else if (blocker != null && (cover != null || vm.carrier == Carrier.Text) && !vm.loading) {
            Text(
                stringResource(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        val textResult = vm.textResult
        if (vm.carrier == Carrier.Text && textResult != null) {
            TextResultSection(
                result = textResult,
                onSave = { saveImage.launch(NewDocument(textResult.name, "text/plain")) },
                onError = { vm.errorText = it },
            )
        }

        if (vm.carrier == Carrier.Image && result != null) {
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

@Composable
private fun TextCarrierSection(vm: HideViewModel) {
    Section(stringResource(R.string.text_kind_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.textMode == TextMode.WORDS,
                onClick = { vm.selectTextMode(TextMode.WORDS) },
                label = { Text(stringResource(R.string.text_mode_words)) },
                enabled = !vm.busy,
            )
            FilterChip(
                selected = vm.textMode == TextMode.ZERO_WIDTH,
                onClick = { vm.selectTextMode(TextMode.ZERO_WIDTH) },
                label = { Text(stringResource(R.string.text_mode_zero_width)) },
                enabled = !vm.busy,
            )
        }
        Text(
            stringResource(if (vm.textMode == TextMode.WORDS) R.string.text_mode_words_about else R.string.text_mode_zero_width_about),
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.textMode == TextMode.WORDS) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                detectabilityColor(Detectability.HIGH)
            },
        )
        if (vm.textMode == TextMode.ZERO_WIDTH) {
            OutlinedTextField(
                value = vm.coverText,
                onValueChange = { vm.coverText = it },
                label = { Text(stringResource(R.string.cover_text_label)) },
                supportingText = { Text(stringResource(R.string.cover_text_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TextSizeNote(vm: HideViewModel) {
    val fit = vm.textFit
    val words = vm.textMode == TextMode.WORDS
    val note = when {
        fit == null -> null
        !fit.fits -> stringResource(R.string.text_too_long, fit.maxTextBytes.toLong())
        words -> pluralStringResource(R.plurals.text_size_words, fit.size.toInt(), fit.size.toLong())
        else -> pluralStringResource(R.plurals.text_size_chars, fit.size.toInt(), fit.size.toLong())
    }
    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = if (fit?.fits == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val room = vm.textRoom
    if (room != null) {
        Text(
            stringResource(R.string.text_size_room, room),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Longest stretch of hidden text shown on screen; the rest is in Copy, Share and Save. */
private const val PREVIEW_CHARS = 1200

@Composable
private fun TextResultSection(result: TextResult, onSave: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    Section(stringResource(R.string.hide_done_title)) {
        val shown = if (result.mode == TextMode.ZERO_WIDTH) visibleText(result.text) else result.text
        val preview = if (shown.length > PREVIEW_CHARS) shown.take(PREVIEW_CHARS) + "…" else shown
        SelectionContainer {
            Text(preview, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            if (result.mode == TextMode.WORDS) {
                pluralStringResource(R.plurals.text_done_words, result.size.toInt(), result.size)
            } else {
                pluralStringResource(R.plurals.text_done_chars, result.size.toInt(), result.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Clipboard.copySensitive(context, result.text) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.copy), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = {
                try {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, result.text)
                    context.startActivity(Intent.createChooser(send, null))
                } catch (e: Exception) {
                    onError(errorMessage(context, e))
                }
            }) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onSave) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
            }
        }
        Text(
            stringResource(
                if (result.mode == TextMode.WORDS) R.string.text_send_words else R.string.text_send_zero_width,
                Clipboard.CLEAR_AFTER_SECONDS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
