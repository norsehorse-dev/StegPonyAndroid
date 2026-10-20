package com.stegpony.android.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stegpony.android.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Takes a fresh cover photo with the back camera. The JPEG is written to the
 * app's cache only long enough to read it back (that is how CameraX records
 * the orientation in it), then deleted; nothing goes to the gallery. Asks for
 * the camera permission when opened.
 */
@Composable
fun CameraCaptureDialog(
    onCaptured: (ByteArray) -> Unit,
    onClose: () -> Unit,
    onCameraRefused: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted = true else onCameraRefused()
    }
    LaunchedEffect(Unit) {
        if (!granted) ask.launch(Manifest.permission.CAMERA)
    }
    if (!granted) return

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CaptureContent(onCaptured, onClose)
    }
}

@Composable
private fun CaptureContent(onCaptured: (ByteArray) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val previewView = remember { PreviewView(context) }
    val ioExecutor = remember { Executors.newSingleThreadExecutor() }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var taking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val resolved = providerFuture.get()
            provider = resolved
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            // Full quality, the setting phone cameras use and the one the
            // detectability ratings were measured at.
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build()
            resolved.unbindAll()
            resolved.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            capture = imageCapture
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            capture = null
            ioExecutor.shutdown()
        }
    }

    fun shoot() {
        val imageCapture = capture ?: return
        if (taking) return
        taking = true
        failed = false
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "cover.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    file.delete()
                    ContextCompat.getMainExecutor(context).execute {
                        taking = false
                        if (bytes != null) currentOnCaptured(bytes) else failed = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    ContextCompat.getMainExecutor(context).execute {
                        taking = false
                        failed = true
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Text(
                stringResource(R.string.camera_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(
                    stringResource(R.string.camera_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            val shutterLabel = stringResource(R.string.camera_shutter)
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .background(if (capture != null && !taking) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable(enabled = capture != null && !taking) { shoot() }
                    .semantics {
                        role = Role.Button
                        contentDescription = shutterLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (taking) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
