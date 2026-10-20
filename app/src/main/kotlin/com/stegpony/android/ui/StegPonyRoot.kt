package com.stegpony.android.ui

import com.stegpony.android.Incoming
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stegpony.android.R
import com.stegpony.android.ui.hide.HideScreen
import com.stegpony.android.ui.hide.HideViewModel
import com.stegpony.android.keys.Keyring
import com.stegpony.android.ui.keys.KeysScreen
import com.stegpony.android.ui.keys.KeysViewModel
import com.stegpony.android.ui.reveal.RevealScreen
import com.stegpony.android.ui.reveal.RevealViewModel
import com.stegpony.android.ui.settings.SettingsScreen

/** The selected tab, provided from above the lock screen (see MainActivity). */
val LocalTab = staticCompositionLocalOf<MutableState<Tab>> { error("no tab state") }

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    Hide(R.string.tab_hide, Icons.Outlined.VisibilityOff),
    Reveal(R.string.tab_reveal, Icons.Outlined.Visibility),
    Keys(R.string.tab_keys, Icons.Outlined.Key),
    Settings(R.string.tab_settings, Icons.Outlined.Settings),
}

@Composable
fun StegPonyRoot(incoming: Incoming?, onIncomingHandled: () -> Unit) {
    // Held by the activity's composition, above the lock screen, so the tab
    // survives being locked and unlocked.
    var tab by LocalTab.current
    val hideVm: HideViewModel = viewModel()
    val revealVm: RevealViewModel = viewModel()
    val keysVm: KeysViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(Unit) { Keyring.load(context.applicationContext) }

    // An image waits out a running reveal rather than being dropped.
    LaunchedEffect(incoming, revealVm.blocked) {
        when (incoming) {
            is Incoming.Image -> if (!revealVm.blocked) {
                tab = Tab.Reveal
                if (revealVm.open(incoming.uri)) onIncomingHandled()
            }
            is Incoming.KeyText -> {
                tab = Tab.Keys
                keysVm.receiveShared(incoming.text.toByteArray(Charsets.UTF_8))
                onIncomingHandled()
            }
            is Incoming.KeyFile -> {
                tab = Tab.Keys
                keysVm.receiveFile(incoming.uri)
                onIncomingHandled()
            }
            null -> Unit
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when (tab) {
                Tab.Hide -> HideScreen(hideVm)
                Tab.Reveal -> RevealScreen(revealVm)
                Tab.Keys -> KeysScreen(keysVm)
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
