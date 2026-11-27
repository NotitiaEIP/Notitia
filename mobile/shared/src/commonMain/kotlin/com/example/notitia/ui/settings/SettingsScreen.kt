package com.example.notitia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.meeting.AudioKeeping
import com.example.notitia.meeting.KeepAudio
import com.example.notitia.ui.components.BackButton
import com.example.notitia.ui.components.CardChevron
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.cardShadow
import com.example.notitia.ui.theme.NotitiaColors
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.ThemeChoice
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.check
import notitia.shared.generated.resources.info
import notitia.shared.generated.resources.lock
import notitia.shared.generated.resources.meetings_on_device
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.settings
import notitia.shared.generated.resources.settings_about
import notitia.shared.generated.resources.settings_about_notitia
import notitia.shared.generated.resources.settings_appearance
import notitia.shared.generated.resources.settings_announce
import notitia.shared.generated.resources.settings_announce_detail
import notitia.shared.generated.resources.settings_data
import notitia.shared.generated.resources.settings_devices
import notitia.shared.generated.resources.settings_devices_none
import notitia.shared.generated.resources.settings_keep_audio
import notitia.shared.generated.resources.settings_language
import notitia.shared.generated.resources.settings_language_english
import notitia.shared.generated.resources.settings_language_french
import notitia.shared.generated.resources.settings_licenses
import notitia.shared.generated.resources.settings_lock
import notitia.shared.generated.resources.settings_lock_detail
import notitia.shared.generated.resources.settings_recording
import notitia.shared.generated.resources.settings_soon
import notitia.shared.generated.resources.settings_theme
import notitia.shared.generated.resources.settings_theme_dark
import notitia.shared.generated.resources.settings_theme_light
import notitia.shared.generated.resources.settings_theme_system
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsRoute(
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    audioKeeping: AudioKeeping,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keepAudio by audioKeeping.keepAudio.collectAsStateWithLifecycle()
    var keepAudioOpen by rememberSaveable { mutableStateOf(false) }
    // The settings find their scroll and their switches again when the deeper page goes, as under a stack.
    val pages = rememberSaveableStateHolder()
    if (keepAudioOpen) {
        KeepAudioScreen(
            chosen = keepAudio,
            keeping = audioKeeping,
            onBack = { keepAudioOpen = false },
            modifier = modifier,
        )
    } else {
        pages.SaveableStateProvider(SETTINGS_PAGE) {
            SettingsScreen(
                theme = theme,
                onThemeChange = onThemeChange,
                keepAudio = keepAudio,
                onKeepAudio = { keepAudioOpen = true },
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

private const val SETTINGS_PAGE = "settings"

// The theme and how long the audio stays work; only the second is kept across launches, by the core. The other
// settings come with their own issue (announce US-15, lock US-83, devices US-102).
@Composable
fun SettingsScreen(
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    keepAudio: KeepAudio?,
    onKeepAudio: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var announce by rememberSaveable { mutableStateOf(true) }
    var lock by rememberSaveable { mutableStateOf(false) }

    SettingsFrame(
        back = stringResource(Res.string.meetings_title),
        backDescription = stringResource(Res.string.minutes_back_description),
        title = stringResource(Res.string.settings),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsGroup(stringResource(Res.string.settings_recording)) {
            SwitchRow(
                label = stringResource(Res.string.settings_announce),
                detail = stringResource(Res.string.settings_announce_detail),
                checked = announce,
                onCheckedChange = { announce = it },
            )
        }

        SettingsGroup(stringResource(Res.string.settings_data)) {
            LinkRow(
                label = stringResource(Res.string.settings_keep_audio),
                value = keepAudio?.let { stringResource(it.label) },
                onClick = onKeepAudio,
            )
            RowDivider()
            SwitchRow(
                label = stringResource(Res.string.settings_lock),
                detail = stringResource(Res.string.settings_lock_detail),
                checked = lock,
                onCheckedChange = { lock = it },
            )
            RowDivider()
            LinkRow(
                label = stringResource(Res.string.settings_devices),
                value = stringResource(Res.string.settings_devices_none),
            )
        }

        SettingsGroup(stringResource(Res.string.settings_appearance)) {
            ThemeRow(theme, onThemeChange)
        }

        // English comes after the Greenlight (AGENTS.md): the choice is shown, not offered yet.
        SettingsGroup(stringResource(Res.string.settings_language), Modifier.selectableGroup()) {
            ChoiceRow(label = stringResource(Res.string.settings_language_french), selected = true)
            RowDivider()
            ChoiceRow(
                label = stringResource(Res.string.settings_language_english),
                selected = false,
                enabled = false,
                value = stringResource(Res.string.settings_soon),
            )
        }

        SettingsGroup(stringResource(Res.string.settings_about)) {
            LinkRow(label = stringResource(Res.string.settings_about_notitia))
            RowDivider()
            LinkRow(label = stringResource(Res.string.settings_licenses))
        }

        // Violet, as everywhere the app shows that the meetings stay here (DESIGN.md, principle 6).
        SettingsNote(
            icon = Res.drawable.lock,
            text = stringResource(Res.string.meetings_on_device),
            tint = NotitiaTheme.colors.accentContent,
            modifier = Modifier.padding(top = NotitiaTheme.spacing.xl),
        )
    }
}

// Every settings page: its back button names the page under it, as on iPhone, and the system's back goes the same
// way.
@Composable
internal fun SettingsFrame(
    back: String,
    backDescription: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
    )
    val spacing = NotitiaTheme.spacing
    Column(modifier.fillMaxSize()) {
        BackButton(
            label = back,
            description = backDescription,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = spacing.xs),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
        ) {
            Text(
                text = title,
                style = NotitiaTheme.typography.title1,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

// A section of the iPhone's settings: a title above the card, unless the page's title already says it, and a
// sentence under it when a choice needs one.
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = NotitiaTheme.spacing
    if (title != null) {
        GroupTitle(title, Modifier.padding(top = spacing.xl, bottom = spacing.s))
    } else {
        Spacer(Modifier.height(spacing.xl))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .cardShadow(NotitiaTheme.shapes.card)
            .background(NotitiaTheme.colors.surface),
        content = content,
    )
    if (footer != null) {
        SettingsNote(
            icon = Res.drawable.info,
            text = footer,
            tint = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.s),
        )
    }
}

// A sentence read without a touch, as Android's settings end theirs with ⓘ: a bubble behind an icon would hide it.
// The icon keeps to the first line when the text wraps.
@Composable
private fun SettingsNote(icon: DrawableResource, text: String, tint: Color, modifier: Modifier = Modifier) {
    val style = NotitiaTheme.typography.caption
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s)) {
        Box(
            modifier = Modifier.height(with(LocalDensity.current) { style.lineHeight.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
            )
        }
        Text(text = text, style = style, color = NotitiaTheme.colors.textSecondary)
    }
}

// Opens a deeper screen, so it ends with the chevron the cards use.
@Composable
private fun LinkRow(label: String, value: String? = null, onClick: () -> Unit = {}) {
    SettingRow(
        label = label,
        value = value,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        CardChevron()
    }
}

// The whole row flips the switch, so the target is large and screen readers read it in one go.
@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingRow(
        label = label,
        detail = detail,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

// The value sits at the end while it fits beside the label, and under it otherwise, as in the value cells of iOS:
// « Jusqu’à la transcription » never fits beside « Conserver l’audio » on a phone.
@Composable
private fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    end: @Composable () -> Unit,
) {
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.field)
            .padding(horizontal = spacing.l, vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // Keeps a gap before a value on the same line.
                Text(text = label, style = NotitiaTheme.typography.body, modifier = Modifier.padding(end = spacing.s))
                if (value != null) {
                    Text(
                        text = value,
                        style = NotitiaTheme.typography.callout,
                        color = NotitiaTheme.colors.textSecondary,
                    )
                }
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = NotitiaTheme.typography.caption,
                    color = NotitiaTheme.colors.textSecondary,
                )
            }
        }
        end()
    }
}

@Composable
private fun ThemeRow(theme: ThemeChoice, onThemeChange: (ThemeChoice) -> Unit) {
    val spacing = NotitiaTheme.spacing
    val choices = ThemeChoice.entries
    Column(
        modifier = Modifier.padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Text(text = stringResource(Res.string.settings_theme), style = NotitiaTheme.typography.body)
        NotitiaSegmentedControl(
            options = choices.map { stringResource(it.label) },
            selected = choices.indexOf(theme),
            onSelect = { onThemeChange(choices[it]) },
        )
    }
}

private val ThemeChoice.label: StringResource
    get() = when (this) {
        ThemeChoice.System -> Res.string.settings_theme_system
        ThemeChoice.Light -> Res.string.settings_theme_light
        ThemeChoice.Dark -> Res.string.settings_theme_dark
    }

// One choice among others: its parent groups them with selectableGroup(), so TalkBack says « 2 sur 4 ».
@Composable
internal fun ChoiceRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    value: String? = null,
    onClick: () -> Unit = {},
) {
    SettingRow(
        label = label,
        value = value,
        modifier = modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else NotitiaColors.DISABLED_ALPHA),
    ) {
        if (selected) {
            Icon(
                painter = painterResource(Res.drawable.check),
                contentDescription = null,
                tint = NotitiaTheme.colors.accentContent,
                modifier = Modifier.size(NotitiaTheme.sizes.icon),
            )
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        color = NotitiaTheme.colors.divider,
        modifier = Modifier.padding(start = NotitiaTheme.spacing.l),
    )
}

@NotitiaPreviews
@Composable
private fun SettingsScreenPreview() = NotitiaTheme {
    SettingsScreen(
        theme = ThemeChoice.System,
        onThemeChange = {},
        keepAudio = KeepAudio.Month,
        onKeepAudio = {},
        onBack = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
