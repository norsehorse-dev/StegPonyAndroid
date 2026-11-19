package com.stegpony.android.ui.reveal

import android.content.ClipboardManager
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
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SearchOff
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import com.stegpony.android.ui.card.HardwareKeyDialog
import com.stegpony.android.ui.card.canUseHardwareKeys
import com.stegpony.android.ui.common.Clipboard
import com.stegpony.android.ui.common.ControlShape
import com.stegpony.android.ui.common.CreateTypedDocument
import com.stegpony.android.ui.common.NewDocument
import com.stegpony.android.ui.common.OptionRow
import com.stegpony.android.ui.common.Panel
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.ScreenTitle
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section
import com.stegpony.android.ui.common.SectionHeader
import com.stegpony.android.ui.common.VerdictPanel
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Payload

/**
 * Revealing: one page to say where to look and what to open it with, and a
 * page of its own for what was found.
 */
@Composable
fun RevealScreen(vm: RevealViewModel) {
    SecureScreenEffect()
    val outcome = vm.outcome
    if (outcome is RevealOutcome.Found) {
        FoundPage(vm, outcome.payload)
    } else {
        LookPage(vm, nothingFound = outcome == RevealOutcome.NothingFound)
    }
}

@Composable
private fun LookPage(vm: RevealViewModel, nothingFound: Boolean) {
    // Set here, not by the caller, so the title and arrow of the found page never outlive it.
    ScreenTitle(stringResource(R.string.tab_reveal))
    val context = LocalContext.current
    val myKeyCount = (Keyring.state.collectAsState().value as? KeyringState.Ready)?.keys?.softwareKeys?.size ?: 0
    val hardwareKeysUsable = remember(context) { canUseHardwareKeys(context) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::open)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(stringResource(R.string.reveal_in_title))
            OptionRow(
                icon = Icons.Outlined.Image,
                label = stringResource(R.string.reveal_image_title),
                about = null,
                selected = vm.source == RevealSource.Image,
                onSelect = { vm.selectSource(RevealSource.Image) },
                enabled = !vm.blocked,
            )
            OptionRow(
                icon = Icons.AutoMirrored.Outlined.Notes,
                label = stringResource(R.string.reveal_text_title),
                about = null,
                selected = vm.source == RevealSource.Text,
                onSelect = { vm.selectSource(RevealSource.Text) },
                enabled = !vm.blocked,
            )
        }

        if (vm.source == RevealSource.Image) {
            Section(stringResource(R.string.reveal_image_title)) {
                // Stacked, so a long translated label has the full width.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Text(stringResource(R.string.pick_photo), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = { pickFile.launch(arrayOf("image/jpeg", "image/png")) },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Text(stringResource(R.string.pick_file), Modifier.padding(start = 8.dp))
                    }
                }
                if (vm.loading) {
                    Working(stringResource(R.string.reading_cover))
                }
                val image = vm.image
                if (image != null) {
                    vm.preview?.let { preview ->
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(ControlShape),
                        )
                    }
                    Text(image.name, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        stringResource(R.string.reveal_image_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Section(stringResource(R.string.reveal_text_title)) {
                OutlinedTextField(
                    value = vm.pastedText,
                    onValueChange = { vm.pastedText = it },
                    label = { Text(stringResource(R.string.reveal_text_label)) },
                    minLines = 3,
                    maxLines = 10,
                    enabled = !vm.blocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (pasted != null) vm.pastedText = pasted
                        },
                        enabled = !vm.blocked,
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Text(stringResource(R.string.paste), Modifier.padding(start = 8.dp))
                    }
                    if (vm.pastedText.isNotEmpty()) {
                        TextButton(onClick = { vm.pastedText = "" }, enabled = !vm.blocked) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
                Text(
                    stringResource(R.string.reveal_text_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(stringResource(R.string.reveal_key_title)) {
            PassphraseField(vm.passphrase, { vm.passphrase = it }, stringResource(R.string.reveal_secret_label))
            Text(
                if (myKeyCount > 0) {
                    context.resources.getQuantityString(R.plurals.reveal_tries_keys, myKeyCount, myKeyCount)
                } else {
                    stringResource(R.string.reveal_no_keys)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (nothingFound) {
            VerdictPanel(
                title = stringResource(R.string.nothing_found_title),
                detail = stringResource(
                    if (vm.source == RevealSource.Text) R.string.nothing_found_text_body else R.string.nothing_found_body,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                icon = Icons.Outlined.SearchOff,
            )
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.busy) {
                Working(stringResource(R.string.revealing))
            }
            Button(
                shape = ControlShape,
                onClick = vm::reveal,
                enabled = vm.canReveal(myKeyCount > 0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reveal_button))
            }
            if (hardwareKeysUsable) {
                OutlinedButton(
                    shape = ControlShape,
                    onClick = vm::openCard,
                    enabled = vm.hasInput && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Key, contentDescription = null)
                    Text(stringResource(R.string.reveal_with_card), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    val cardOp = vm.cardOperation
    if (cardOp != null) {
        HardwareKeyDialog(
            title = stringResource(R.string.reveal_with_card),
            askPin = true,
            operation = cardOp,
            onSuccess = vm::cardResult,
            onDismiss = vm::closeCard,
        )
    }
}

/** A small spinner beside what is being done. */
@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** What was hidden, on a page of its own. Back and Close both put it away. */
@Composable
private fun FoundPage(vm: RevealViewModel, payload: Payload) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFile = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        uri?.let(vm::saveFile)
    }
    ScreenTitle(
        title = stringResource(if (payload is Payload.Text) R.string.found_text_title else R.string.found_file_title),
        onBack = vm::close,
    )
    BackHandler { vm.close() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (payload) {
            is Payload.Text -> {
                Panel {
                    SelectionContainer {
                        Text(payload.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        shape = ControlShape,
                        onClick = { Clipboard.copySensitive(context, payload.text) },
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
            }
            is Payload.File -> {
                val mime = payload.mime.ifBlank { "application/octet-stream" }
                Panel {
                    Text(payload.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        Formatter.formatShortFileSize(context, payload.data.size.toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        shape = ControlShape,
                        onClick = { saveFile.launch(NewDocument(Files.safeName(payload.name), mime)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = {
                            scope.launch {
                                try {
                                    val share = withContext(Dispatchers.IO) {
                                        Files.shareIntent(context, Files.AREA_REVEAL, payload.data, payload.name, mime)
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
            }
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = vm::close) { Text(stringResource(R.string.close)) }
    }
}
