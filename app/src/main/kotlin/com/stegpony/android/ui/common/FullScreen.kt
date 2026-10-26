package com.stegpony.android.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Holds the one full-screen layer (the camera) shown above the whole app.
 *
 * The camera screens used to be dialogs. A dialog is a window of its own, and on
 * current Android it is drawn under the navigation bar without being told the
 * bar is there, so the shutter ended up partly off screen. The activity window
 * is edge to edge and reports the system bars correctly, so full-screen content
 * is drawn there instead, by [FullScreenLayer] at the root.
 */
class FullScreenHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        internal set
    internal var owner: Any? = null
}

val LocalFullScreenHost = staticCompositionLocalOf<FullScreenHost> { error("no full-screen host") }

/**
 * Shows [content] over the whole app, in the activity's own window, for as long
 * as this stays in the composition. Back calls [onBack].
 */
@Composable
fun FullScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalFullScreenHost.current
    val owner = remember { Any() }
    val latest by rememberUpdatedState(content)
    DisposableEffect(host, owner) {
        host.owner = owner
        host.content = { latest() }
        onDispose {
            if (host.owner === owner) {
                host.owner = null
                host.content = null
            }
        }
    }
    BackHandler(onBack = onBack)
}

/** Draws the current full-screen content, if any, and keeps touches from reaching the app under it. */
@Composable
fun FullScreenLayer(host: FullScreenHost) {
    val content = host.content ?: return
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        content()
    }
}
