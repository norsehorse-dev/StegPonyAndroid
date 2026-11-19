package com.stegpony.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegpony.android.R
import com.stegpony.android.i18n.LanguageManager
import com.stegpony.android.i18n.LanguageState
import com.stegpony.android.i18n.SupportedLanguage
import com.stegpony.android.ui.common.ControlShape
import com.stegpony.android.ui.common.ListDivider

/** The language page in Settings. Picking one switches the app at once. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    SubPage(stringResource(R.string.language_title), onBack) {
        LanguageList()
        Text(
            stringResource(R.string.language_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every shipped language under its own name, with the one in use marked. Also the first welcome page. */
@Composable
fun LanguageList(modifier: Modifier = Modifier) {
    val current by LanguageState.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape),
    ) {
        val all = SupportedLanguage.entries
        all.forEachIndexed { index, language ->
            val selected = current == language.tag
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton) {
                        if (!selected) LanguageManager.setLanguage(language)
                    }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language.nativeName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (index < all.lastIndex) ListDivider()
        }
    }
}
