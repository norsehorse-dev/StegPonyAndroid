package com.stegpony.android.ui.reveal

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.stegpony.android.ui.card.HardwareKeyDialog
import com.stegpony.android.ui.card.canUseHardwareKeys
import androidx.compose.runtime.collectAsState
import com.stegpony.android.keys.Keyring
import com.stegpony.android.keys.KeyringState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.ui.common.Clipboard
import com.stegpony.android.ui.common.CreateTypedDocument
import com.stegpony.android.ui.common.NewDocument
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.Section
import com.stegpony.android.ui.common.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.stegpony_ffi.Payload

@Composable
fun RevealScreen(vm: RevealViewModel) {
    SecureScreenEffect()
    val context = LocalContext.current
    val myKeyCount = (Keyring.state.collectAsState().value as? KeyringState.Ready)?.keys?.softwareKeys?.size ?: 0
    val hardwareKeysUsable = remember(context) { canUseHardwareKeys(context) }
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::open)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }
    val saveFile = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        uri?.let(vm::saveFile)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.reveal_image_title)) {
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
                OutlinedButton(onClick = { pickFile.launch(arrayOf("image/jpeg", "image/png")) }, enabled = !vm.busy) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Text(stringResource(R.string.pick_file), Modifier.padding(start = 8.dp))
                }
            }
            val image = vm.image
            if (image != null) {
                Text(image.name, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.reveal_image_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

        Button(onClick = vm::reveal, enabled = vm.canReveal(myKeyCount > 0), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reveal_button))
        }
        if (hardwareKeysUsable) {
            OutlinedButton(
                onClick = vm::openCard,
                enabled = vm.hasImage && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Text(stringResource(R.string.reveal_with_card), Modifier.padding(start = 8.dp))
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
        if (vm.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.revealing), style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when (val outcome = vm.outcome) {
            null -> Unit
            RevealOutcome.NothingFound -> Section(stringResource(R.string.nothing_found_title)) {
                Text(stringResource(R.string.nothing_found_body), style = MaterialTheme.typography.bodyMedium)
            }
            is RevealOutcome.Found -> when (val p = outcome.payload) {
                is Payload.Text -> Section(stringResource(R.string.found_text_title)) {
                    SelectionContainer {
                        Text(p.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Clipboard.copySensitive(context, p.text) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Text(stringResource(R.string.copy), Modifier.padding(start = 8.dp))
                        }
                        TextButton(onClick = vm::close) { Text(stringResource(R.string.close)) }
                    }
                    Text(
                        stringResource(R.string.copy_clears, Clipboard.CLEAR_AFTER_SECONDS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is Payload.File -> Section(stringResource(R.string.found_file_title)) {
                    Text(
                        "${p.name} (${Formatter.formatShortFileSize(context, p.data.size.toLong())})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            saveFile.launch(NewDocument(Files.safeName(p.name), p.mime.ifBlank { "application/octet-stream" }))
                        }) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                try {
                                    val share = withContext(Dispatchers.IO) {
                                        Files.shareIntent(
                                            context,
                                            Files.AREA_REVEAL,
                                            p.data,
                                            p.name,
                                            p.mime.ifBlank { "application/octet-stream" },
                                        )
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
                        TextButton(onClick = vm::close) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }
    }
}
