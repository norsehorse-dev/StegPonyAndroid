package com.stegpony.android.ui.hide

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.keys.Keys
import com.stegpony.android.lock.ScreenPrivacy
import com.stegpony.android.ui.camera.CameraCaptureDialog
import com.stegpony.android.ui.common.CapacityMeter
import com.stegpony.android.ui.common.Clipboard
import com.stegpony.android.ui.common.CreateTypedDocument
import com.stegpony.android.ui.common.ControlShape
import com.stegpony.android.ui.common.ListDivider
import com.stegpony.android.ui.common.NewDocument
import com.stegpony.android.ui.common.OptionRow
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.RadioRow
import com.stegpony.android.ui.common.ScreenTitle
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section
import com.stegpony.android.ui.common.SectionHeader
import com.stegpony.android.ui.common.StatusText
import com.stegpony.android.ui.common.StepBar
import com.stegpony.android.ui.common.SwitchRow
import com.stegpony.android.ui.common.detectabilityColor
import com.stegpony.android.ui.common.detectabilityIcon
import com.stegpony.android.ui.common.detectabilityTitle
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Detectability
import uniffi.stegpony_ffi.ImageFormat
import uniffi.stegpony_ffi.ImageMode
import uniffi.stegpony_ffi.TextMode

/** Steps the app bar counts; the result page is not one of them. */
private const val STEPS = 4

/**
 * Hiding, one step per page: what to hide in, what to hide, what locks it,
 * then a review. The app bar counts the steps and carries the back arrow.
 */
@Composable
fun HideScreen(vm: HideViewModel) {
    // The message and passphrases are on this screen too; Settings can allow
    // screenshots here.
    if (ScreenPrivacy.secureHide) SecureScreenEffect()
    val step = vm.step

    // A result page with nothing to show (the result was cleared elsewhere).
    if (step == HideStep.Done && vm.result == null && vm.textResult == null) {
        LaunchedEffect(Unit) { vm.startOver() }
        return
    }

    val inFlow = step != HideStep.Cover && step != HideStep.Done
    ScreenTitle(
        title = if (step == HideStep.Done) {
            stringResource(R.string.hide_done_title)
        } else {
            stringResource(R.string.hide_step, step.ordinal + 1, STEPS)
        },
        // The arrow stays while hiding; back() ignores it until the work is done.
        onBack = if (inFlow) vm::back else null,
    )
    // While hiding, back is swallowed here rather than leaving the app.
    BackHandler(enabled = inFlow) { vm.back() }

    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (step != HideStep.Done) StepBar(current = step.ordinal, total = STEPS)
        when (step) {
            HideStep.Cover -> CoverStep(vm)
            HideStep.Payload -> PayloadStep(vm)
            HideStep.Key -> KeyStep(vm)
            HideStep.Review -> ReviewStep(vm)
            HideStep.Done -> DoneStep(vm)
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (step == HideStep.Cover || step == HideStep.Payload || step == HideStep.Key) {
            NextButton(vm, step)
        }
    }
}

/** Next, with the reason it is off when the step isn't finished. */
@Composable
private fun NextButton(vm: HideViewModel, step: HideStep) {
    val blocker = vm.stepBlocker(step)
    // Picking a photo needs no prompt: the hint and the spinner already say it.
    val quiet = step == HideStep.Cover && vm.carrier == Carrier.Image
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocker != null && !quiet && !vm.loading) {
            Text(
                stringResource(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(shape = ControlShape,
            onClick = vm::next,
            enabled = blocker == null && !vm.loading && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.next))
        }
    }
}

@Composable
private fun CoverStep(vm: HideViewModel) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.hide_in_title))
        OptionRow(
            icon = Icons.Outlined.Image,
            label = stringResource(R.string.carrier_photo),
            about = stringResource(R.string.carrier_photo_about),
            selected = vm.carrier == Carrier.Image,
            onSelect = { vm.selectCarrier(Carrier.Image) },
            enabled = !vm.busy,
        )
        OptionRow(
            icon = Icons.AutoMirrored.Outlined.Notes,
            label = stringResource(R.string.carrier_text),
            about = stringResource(R.string.carrier_text_about),
            selected = vm.carrier == Carrier.Text,
            onSelect = { vm.selectCarrier(Carrier.Text) },
            enabled = !vm.busy,
        )
    }
    if (vm.carrier == Carrier.Image) ImageCoverSection(vm) else TextCarrierSection(vm)
}

@Composable
private fun ImageCoverSection(vm: HideViewModel) {
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickCover)
    }
    val pickImageFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickCover)
    }
    var takingPhoto by remember { mutableStateOf(false) }
    val cover = vm.cover
    val idle = !vm.busy && !vm.loading

    Section(stringResource(R.string.hide_cover_title)) {
        Text(
            stringResource(R.string.hide_cover_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Stacked, so a long translated label has the full width.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceButton(Icons.Outlined.PhotoCamera, stringResource(R.string.take_photo), idle) { takingPhoto = true }
            SourceButton(Icons.Outlined.Image, stringResource(R.string.pick_photo), idle) {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            SourceButton(Icons.Outlined.Folder, stringResource(R.string.pick_file), idle) {
                pickImageFile.launch(arrayOf("image/jpeg", "image/png"))
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
        if (vm.loading) {
            Working(stringResource(R.string.reading_cover))
        }
        if (cover != null) {
            cover.preview?.let { Preview(it) }
            Text(
                if (cover.fresh) stringResource(R.string.cover_fresh) else cover.file.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            cover.notes.forEach { note ->
                val reused = note == CoverNote.Reused
                StatusText(
                    text = stringResource(
                        when (note) {
                            CoverNote.Reused -> R.string.cover_note_reused
                            CoverNote.NoCameraDetails -> R.string.cover_note_not_camera
                            CoverNote.FromDownloads -> R.string.cover_note_downloads
                        },
                    ),
                    color = detectabilityColor(if (reused) Detectability.HIGH else Detectability.MEDIUM),
                    icon = detectabilityIcon(if (reused) Detectability.HIGH else Detectability.MEDIUM),
                )
            }
            if (cover.modes.size > 1) {
                Column(Modifier.selectableGroup()) {
                    cover.modes.forEach { m ->
                        RadioRow(
                            label = stringResource(if (m == ImageMode.JPEG) R.string.mode_jpeg else R.string.mode_lossless),
                            selected = vm.mode == m,
                            onSelect = { vm.selectMode(m) },
                            about = stringResource(modeAbout(m, cover.format)),
                            enabled = idle,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(modeAbout(vm.mode, cover.format)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun modeAbout(mode: ImageMode, format: ImageFormat): Int = when {
    mode == ImageMode.JPEG -> R.string.mode_jpeg_about
    format == ImageFormat.JPEG -> R.string.mode_lossless_from_jpeg_about
    else -> R.string.mode_lossless_about
}

@Composable
internal fun SourceButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(shape = ControlShape, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** The cover or the result, shown small. Decoration for screen readers: the name beside it says what it is. */
@Composable
internal fun Preview(image: ImageBitmap) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(ControlShape),
    )
}

/** A small spinner beside what is being done. */
@Composable
internal fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TextCarrierSection(vm: HideViewModel) {
    Section(stringResource(R.string.text_kind_title)) {
        Column(Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(R.string.text_mode_words),
                selected = vm.textMode == TextMode.WORDS,
                onSelect = { vm.selectTextMode(TextMode.WORDS) },
                about = stringResource(R.string.text_mode_words_about),
                enabled = !vm.busy,
            )
            RadioRow(
                label = stringResource(R.string.text_mode_zero_width),
                selected = vm.textMode == TextMode.ZERO_WIDTH,
                onSelect = { vm.selectTextMode(TextMode.ZERO_WIDTH) },
                about = stringResource(R.string.text_mode_zero_width_about),
                // The weak option says so in the warning color.
                aboutColor = detectabilityColor(Detectability.HIGH),
                enabled = !vm.busy,
            )
        }
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
private fun PayloadStep(vm: HideViewModel) {
    val context = LocalContext.current
    val pickPayloadFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickFile)
    }
    Section(stringResource(R.string.hide_message_title)) {
        Column(Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(R.string.payload_text),
                selected = vm.payloadKind == PayloadKind.Text,
                onSelect = { vm.payloadKind = PayloadKind.Text },
            )
            RadioRow(
                label = stringResource(R.string.payload_file),
                selected = vm.payloadKind == PayloadKind.File,
                onSelect = { vm.payloadKind = PayloadKind.File },
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
                OutlinedButton(shape = ControlShape, onClick = { pickPayloadFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null)
                    Text(stringResource(R.string.pick_payload_file), Modifier.padding(start = 8.dp))
                }
                vm.file?.let { f ->
                    val size = f.size?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                    Text("${f.name} ($size)", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        SizeNote(vm)
    }
}

/** How much room is left and how the result would rate, for a photo or for text. */
@Composable
private fun SizeNote(vm: HideViewModel) {
    if (vm.carrier == Carrier.Text) {
        TextSizeNote(vm)
        return
    }
    val capacity = vm.capacity ?: return
    val payloadLen = vm.payloadLen
    if (payloadLen != null) {
        CapacityMeter(
            used = payloadLen,
            max = capacity.maxPayloadBytes.toLong(),
            rating = vm.fit?.detectability,
            // The zones are for a payload on its own; a decoy shifts them, so they are left off.
            lowMax = if (vm.decoyOn) null else capacity.lowPayloadBytes.toLong(),
            mediumMax = if (vm.decoyOn) null else capacity.mediumPayloadBytes.toLong(),
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
            pluralStringResource(R.plurals.text_size_room, room.toInt(), room),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyStep(vm: HideViewModel) {
    val context = LocalContext.current
    val keyring = Keyring.state.collectAsState().value
    val keys = (keyring as? KeyringState.Ready)?.keys ?: Keys()
    Section(stringResource(R.string.hide_key_title)) {
        Column(Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(R.string.key_passphrase),
                selected = vm.keyKind == KeyKind.Passphrase,
                onSelect = { vm.keyKind = KeyKind.Passphrase },
            )
            RadioRow(
                label = stringResource(R.string.key_recipient),
                selected = vm.keyKind == KeyKind.Recipient,
                onSelect = { vm.keyKind = KeyKind.Recipient },
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
                // A generated passphrase is shown in the clear, so it can be read
                // and passed on, until the field is edited or the hide is done.
                val generated = vm.generatedPassphrase
                if (generated != null && generated == vm.passphrase) {
                    SelectionContainer {
                        Text(generated, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        stringResource(R.string.passphrase_generated_about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = { Clipboard.copySensitive(context, generated) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.copy), Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.copy_clears, Clipboard.CLEAR_AFTER_SECONDS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::generatePassphrase) {
                    Text(stringResource(R.string.passphrase_generate))
                }
            }
            KeyKind.Recipient -> {
                val saved = (
                    keys.contacts.map { it.name to it.recipient } +
                        keys.mine.map { stringResource(R.string.recipient_yours, it.label) to it.recipient }
                    ).distinctBy { it.second }
                // A picked key that has since been deleted falls back to pasting.
                // Only once the keyring has loaded: before that the list is empty.
                LaunchedEffect(saved, keyring) {
                    val picked = vm.pickedRecipient
                    if (keyring is KeyringState.Ready && picked != null && saved.none { it.second == picked }) {
                        vm.pickedRecipient = null
                    }
                }
                if (saved.isNotEmpty()) {
                    Column(Modifier.selectableGroup()) {
                        saved.forEachIndexed { i, (name, r) ->
                            if (i > 0) ListDivider()
                            RadioRow(name, vm.pickedRecipient == r, { vm.pickedRecipient = r })
                        }
                        ListDivider()
                        RadioRow(
                            stringResource(R.string.recipient_paste),
                            vm.pickedRecipient == null,
                            { vm.pickedRecipient = null },
                        )
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
        SwitchRow(
            title = stringResource(R.string.decoy_add),
            about = stringResource(R.string.decoy_about),
            checked = vm.decoyOn,
            onChange = { vm.decoyOn = it },
        )
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
}

@Composable
private fun ReviewStep(vm: HideViewModel) {
    val cover = vm.cover
    Section(stringResource(R.string.review_title)) {
        Column {
            if (vm.carrier == Carrier.Image) {
                SummaryRow(
                    stringResource(R.string.review_cover),
                    if (cover == null || cover.fresh) stringResource(R.string.review_cover_fresh) else cover.file.name,
                )
                ListDivider()
                SummaryRow(
                    stringResource(R.string.review_format),
                    stringResource(if (vm.mode == ImageMode.JPEG) R.string.mode_jpeg else R.string.mode_lossless),
                )
            } else {
                SummaryRow(
                    stringResource(R.string.text_kind_title),
                    stringResource(if (vm.textMode == TextMode.WORDS) R.string.text_mode_words else R.string.text_mode_zero_width),
                )
            }
            ListDivider()
            SummaryRow(
                stringResource(R.string.hide_message_title),
                when (vm.payloadKind) {
                    PayloadKind.Text -> stringResource(R.string.review_payload_text)
                    PayloadKind.File -> vm.file?.name.orEmpty()
                },
            )
            ListDivider()
            SummaryRow(
                stringResource(R.string.hide_key_title),
                when (vm.keyKind) {
                    KeyKind.Passphrase -> stringResource(R.string.key_passphrase)
                    KeyKind.Recipient -> vm.recipientSummary
                },
            )
            ListDivider()
            SummaryRow(
                stringResource(R.string.hide_decoy_title),
                stringResource(if (vm.decoyOn) R.string.review_decoy_on else R.string.review_decoy_off),
            )
        }
        SizeNote(vm)
    }

    if (vm.carrier == Carrier.Image && cover != null && vm.mode == ImageMode.JPEG) {
        SwitchRow(
            title = stringResource(R.string.keep_metadata),
            about = stringResource(R.string.keep_metadata_about),
            checked = vm.keepMetadata,
            onChange = { vm.keepMetadata = it },
            enabled = !vm.busy,
        )
    }

    val blocker = vm.blocker
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.busy) {
            Working(stringResource(if (vm.carrier == Carrier.Text) R.string.hiding_text else R.string.hiding))
        } else if (blocker != null) {
            // Something changed on an earlier step; say what, so they can go back to it.
            Text(
                stringResource(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(shape = ControlShape,
            onClick = vm::hide,
            enabled = blocker == null && !vm.busy && !vm.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.hide_button))
        }
    }
}

/** One line of the review: what was chosen, under what it is. */
@Composable
private fun SummaryRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Longest stretch of hidden text shown on screen; the rest is in Copy, Share and Save. */
private const val PREVIEW_CHARS = 1200

@Composable
private fun DoneStep(vm: HideViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        uri?.let(vm::save)
    }
    val image = vm.result
    val text = vm.textResult

    if (vm.carrier == Carrier.Image && image != null) {
        image.preview?.let { Preview(it) }
        StatusText(
            text = detectabilityTitle(image.detectability),
            color = detectabilityColor(image.detectability),
            icon = detectabilityIcon(image.detectability),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(shape = ControlShape, onClick = { save.launch(NewDocument(image.name, image.mime)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(shape = ControlShape,
                onClick = {
                    scope.launch {
                        try {
                            val share = withContext(Dispatchers.IO) {
                                Files.shareIntent(context, Files.AREA_HIDE, image.bytes, image.name, image.mime)
                            }
                            context.startActivity(share)
                        } catch (e: Exception) {
                            vm.errorText = errorMessage(context, e)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
            }
        }
        Text(
            stringResource(R.string.send_as_file),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (text != null) {
        val shown = if (text.mode == TextMode.ZERO_WIDTH) visibleText(text.text) else text.text
        val preview = if (shown.length > PREVIEW_CHARS) shown.take(PREVIEW_CHARS) + "…" else shown
        SelectionContainer {
            Text(preview, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            if (text.mode == TextMode.WORDS) {
                pluralStringResource(R.plurals.text_done_words, text.size.toInt(), text.size)
            } else {
                pluralStringResource(R.plurals.text_done_chars, text.size.toInt(), text.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(shape = ControlShape, onClick = { Clipboard.copySensitive(context, text.text) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.copy), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(shape = ControlShape,
                onClick = {
                    try {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text.text)
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (e: Exception) {
                        vm.errorText = errorMessage(context, e)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(shape = ControlShape, onClick = { save.launch(NewDocument(text.name, "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
            }
        }
        Text(
            stringResource(
                if (text.mode == TextMode.WORDS) R.string.text_send_words else R.string.text_send_zero_width,
                Clipboard.CLEAR_AFTER_SECONDS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton(onClick = vm::startOver) {
        Text(stringResource(R.string.hide_another))
    }
}
