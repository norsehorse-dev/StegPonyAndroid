package com.stegpony.android.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stegpony.android.R
import com.stegpony.android.ui.common.FullScreen
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

private const val NOTE_SHOWN_MS = 1_500L

/**
 * Full-screen QR scanner: CameraX preview with zxing decoding. Asks for the
 * camera permission when opened, never earlier, and closes with a message if
 * it is refused. [onDecoded] judges each code.
 */
@Composable
fun QrScannerDialog(
    help: String,
    onDecoded: (String) -> ScanVerdict,
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

    // Drawn over the app in its own window, not in a dialog (see FullScreen).
    FullScreen(onBack = onClose) {
        ScannerContent(help, onDecoded, onClose)
    }
}

@Composable
private fun ScannerContent(help: String, onDecoded: (String) -> ScanVerdict, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val currentOnClose by rememberUpdatedState(onClose)
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var note by remember { mutableStateOf<ScanVerdict.Note?>(null) }
    var analyzer by remember { mutableStateOf<QrAnalyzer?>(null) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val qr = QrAnalyzer(ContextCompat.getMainExecutor(context)) { text ->
            when (val verdict = currentOnDecoded(text)) {
                ScanVerdict.Accept -> currentOnClose()
                is ScanVerdict.Note -> note = verdict
            }
        }
        analyzer = qr
        providerFuture.addListener({
            val resolved = providerFuture.get()
            provider = resolved
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor, qr)
            resolved.unbindAll()
            resolved.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analyzerExecutor.shutdown()
        }
    }

    // A note stays up briefly, then scanning resumes.
    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_SHOWN_MS)
            note = null
            analyzer?.resume()
        }
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
                stringResource(R.string.scan_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            val shown = note
            Text(
                shown?.message ?: help,
                color = if (shown?.isError == true) MaterialTheme.colorScheme.error else Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
