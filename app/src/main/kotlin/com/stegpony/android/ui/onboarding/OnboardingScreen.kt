package com.stegpony.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stegpony.android.R
import com.stegpony.android.lock.AppLock
import com.stegpony.android.lock.ScreenPrivacy
import com.stegpony.android.ui.common.ControlShape
import com.stegpony.android.ui.common.FullScreenHost
import com.stegpony.android.ui.common.FullScreenLayer
import com.stegpony.android.ui.common.LocalFullScreenHost
import com.stegpony.android.ui.common.ListDivider
import com.stegpony.android.ui.common.Panel
import com.stegpony.android.ui.common.SwitchRow
import com.stegpony.android.ui.common.findActivity
import com.stegpony.android.ui.hide.HideViewModel
import com.stegpony.android.ui.settings.LanguageList
import kotlinx.coroutines.launch

private enum class Extra { None, Language, Try, Protect }

/** One page of the tour. A null [icon] shows the brand mark. */
private class Slide(
    val icon: ImageVector?,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val extra: Extra = Extra.None,
)

// Language comes first, so everything after it is read in the right one.
private val SLIDES = listOf(
    Slide(Icons.Outlined.Language, R.string.language_title, R.string.onboarding_language_body, Extra.Language),
    Slide(null, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    Slide(Icons.Outlined.PhotoCamera, R.string.onboarding_cover_title, R.string.onboarding_cover_body, Extra.Try),
    Slide(Icons.Outlined.Key, R.string.onboarding_lock_title, R.string.onboarding_lock_body),
    Slide(Icons.Outlined.AttachFile, R.string.onboarding_send_title, R.string.onboarding_send_body),
    Slide(Icons.Outlined.Lock, R.string.onboarding_protect_title, R.string.onboarding_protect_body, Extra.Protect),
    Slide(Icons.Outlined.Info, R.string.onboarding_limits_title, R.string.onboarding_limits_body),
)

/**
 * The welcome tour, after PGPony's: full-screen pages to swipe through, with
 * the language choice first, a first message hidden from inside the tour
 * (TryHide), and the two privacy switches set in place. Shown on first run and
 * again from Settings.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(initialPage = Onboarding.startPage, pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex
    val hideVm: HideViewModel = viewModel()
    var trying by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !trying && pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    // The camera draws over everything through this, as it does in the app.
    val fullScreen = remember { FullScreenHost() }
    CompositionLocalProvider(LocalFullScreenHost provides fullScreen) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(),
            ) {
                if (trying) {
                    TryHide(
                        vm = hideVm,
                        onClose = { trying = false },
                        onContinue = {
                            trying = false
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        },
                    )
                } else {
                    TourPages(pager, last, hidden = hideVm.result != null, onTry = { trying = true }, onDone = onDone)
                }
            }
            FullScreenLayer(fullScreen)
        }
    }
}

@Composable
private fun ColumnScope.TourPages(pager: PagerState, last: Boolean, hidden: Boolean, onTry: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!last) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) { index ->
        SlidePage(SLIDES[index], hidden, onTry)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val position = stringResource(R.string.onboarding_page, pager.currentPage + 1, SLIDES.size)
        Row(
            Modifier.semantics { contentDescription = position },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SLIDES.indices.forEach { index ->
                val active = pager.currentPage == index
                Box(
                    Modifier
                        .size(if (active) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Button(
            shape = ControlShape,
            onClick = {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (last) R.string.onboarding_start else R.string.next))
        }
    }
}

@Composable
private fun SlidePage(slide: Slide, hidden: Boolean, onTry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(128.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (slide.icon != null) {
                Icon(
                    slide.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_brand_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(slide.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(slide.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (slide.extra) {
            Extra.Language -> {
                Spacer(Modifier.height(24.dp))
                LanguageList()
            }
            Extra.Try -> {
                Spacer(Modifier.height(24.dp))
                Button(shape = ControlShape, onClick = onTry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (hidden) R.string.onboarding_try_show else R.string.onboarding_try_button))
                }
                if (!hidden) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.onboarding_try_hint),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Extra.Protect -> {
                Spacer(Modifier.height(24.dp))
                ProtectSwitches()
            }
            Extra.None -> Unit
        }
    }
}

/** The same two switches as Settings, so they can be set before first use. */
@Composable
private fun ProtectSwitches() {
    val context = LocalContext.current
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var lockError by remember { mutableStateOf<String?>(null) }
    val lockTitle = stringResource(R.string.lock_title)
    Panel {
        SwitchRow(
            title = stringResource(R.string.app_lock_switch),
            about = stringResource(R.string.app_lock_about),
            checked = lockOn,
            onChange = { on ->
                lockError = null
                if (!on) {
                    AppLock.setEnabled(context, false)
                    lockOn = false
                } else if (!AppLock.canUse(context)) {
                    lockError = context.getString(R.string.app_lock_no_screen_lock)
                } else {
                    // Prove the check works before relying on it.
                    val activity = context.findActivity() as? FragmentActivity
                    if (activity != null) {
                        AppLock.authenticate(
                            activity,
                            lockTitle,
                            onSuccess = {
                                AppLock.setEnabled(context, true)
                                lockOn = true
                            },
                            onError = { message -> lockError = message },
                        )
                    }
                }
            },
        )
        lockError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        ListDivider()
        SwitchRow(
            title = stringResource(R.string.secure_hide_switch),
            about = stringResource(R.string.secure_hide_about),
            checked = ScreenPrivacy.secureHide,
            onChange = { ScreenPrivacy.setSecureHide(context, it) },
        )
    }
}
