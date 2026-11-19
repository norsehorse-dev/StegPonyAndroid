package com.stegpony.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.io.Files
import com.stegpony.android.lock.ScreenPrivacy
import com.stegpony.android.ui.camera.CameraCaptureDialog
import com.stegpony.android.ui.common.ControlShape
import com.stegpony.android.ui.common.CreateTypedDocument
import com.stegpony.android.ui.common.NewDocument
import com.stegpony.android.ui.common.PassphraseField
import com.stegpony.android.ui.common.Section
import com.stegpony.android.ui.common.SecureScreenEffect
import com.stegpony.android.ui.common.StatusText
import com.stegpony.android.ui.common.detectabilityColor
import com.stegpony.android.ui.common.detectabilityIcon
import com.stegpony.android.ui.common.detectabilityTitle
import com.stegpony.android.ui.common.errorMessage
import com.stegpony.android.ui.hide.HideResult
import com.stegpony.android.ui.hide.HideViewModel
import com.stegpony.android.ui.hide.Preview
import com.stegpony.android.ui.hide.SourceButton
import com.stegpony.android.ui.hide.Working
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hiding a first message from inside the welcome tour, as PGPony's tour makes
 * a first key: the short path only (a photo, a typed message, a passphrase),
 * on the same view model as the Hide tab, so it is the real thing and the
 * result is still on the Hide tab when the tour closes.
 */
@Composable
fun TryHide(vm: HideViewModel, onClose: () -> Unit, onContinue: () -> Unit) {
    if (ScreenPrivacy.secureHide) SecureScreenEffect()
    LaunchedEffect(Unit) { vm.prepareForTour() }
    BackHandler(onBack = onClose)
    val result = vm.result
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
            }
            Text(
                stringResource(R.string.onboarding_try_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (result != null) TryResult(vm, result, onContinue) else TryForm(vm)
            vm.errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TryForm(vm: HideViewModel) {
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::pickCover)
    }
    var takingPhoto by remember { mutableStateOf(false) }
    val cover = vm.cover
    val idle = !vm.busy && !vm.loading

    Section(stringResource(R.string.onboarding_try_photo)) {
        Text(
            stringResource(R.string.hide_cover_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceButton(Icons.Outlined.PhotoCamera, stringResource(R.string.take_photo), idle) { takingPhoto = true }
            SourceButton(Icons.Outlined.Image, stringResource(R.string.pick_photo), idle) {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        if (vm.loading) Working(stringResource(R.string.reading_cover))
        if (cover != null) {
            cover.preview?.let { Preview(it) }
            Text(
                if (cover.fresh) stringResource(R.string.cover_fresh) else cover.file.name,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Section(stringResource(R.string.onboarding_try_message)) {
        OutlinedTextField(
            value = vm.text,
            onValueChange = { vm.text = it },
            label = { Text(stringResource(R.string.message_label)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Section(stringResource(R.string.onboarding_try_passphrase)) {
        Text(
            stringResource(R.string.onboarding_try_passphrase_about),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PassphraseField(vm.passphrase, { vm.passphrase = it }, stringResource(R.string.passphrase_label))
        PassphraseField(
            vm.passphraseAgain,
            { vm.passphraseAgain = it },
            stringResource(R.string.passphrase_again_label),
            isError = vm.passphraseAgain.isNotEmpty() && vm.passphraseAgain != vm.passphrase,
        )
    }
    val blocker = vm.blocker
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.busy) {
            Working(stringResource(R.string.hiding))
        } else if (blocker != null && !vm.loading) {
            Text(
                stringResource(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            shape = ControlShape,
            onClick = vm::hide,
            enabled = blocker == null && idle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.hide_button))
        }
    }
}

@Composable
private fun TryResult(vm: HideViewModel, image: HideResult, onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        uri?.let(vm::save)
    }
    image.preview?.let { Preview(it) }
    Text(
        stringResource(R.string.onboarding_try_done),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    StatusText(
        text = detectabilityTitle(image.detectability),
        color = detectabilityColor(image.detectability),
        icon = detectabilityIcon(image.detectability),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            shape = ControlShape,
            onClick = { save.launch(NewDocument(image.name, image.mime)) },
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
    Text(
        stringResource(R.string.onboarding_try_reveal_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(shape = ControlShape, onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_try_continue))
        }
        TextButton(onClick = vm::startOver) {
            Text(stringResource(R.string.hide_another))
        }
    }
}
