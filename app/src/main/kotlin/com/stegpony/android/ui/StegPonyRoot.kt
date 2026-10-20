package com.stegpony.android.ui

import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    Hide(R.string.tab_hide, Icons.Outlined.VisibilityOff),
    Reveal(R.string.tab_reveal, Icons.Outlined.Visibility),
    Keys(R.string.tab_keys, Icons.Outlined.Key),
    Settings(R.string.tab_settings, Icons.Outlined.Settings),
}

@Composable
fun StegPonyRoot(incoming: Uri?, onIncomingHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Hide) }
    val hideVm: HideViewModel = viewModel()
    val revealVm: RevealViewModel = viewModel()
    val keysVm: KeysViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(Unit) { Keyring.load(context.applicationContext) }

    // Waits out a running reveal rather than dropping an image shared in meanwhile.
    LaunchedEffect(incoming, revealVm.blocked) {
        if (incoming != null && !revealVm.blocked) {
            tab = Tab.Reveal
            if (revealVm.open(incoming)) onIncomingHandled()
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
