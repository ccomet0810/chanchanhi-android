package com.example.aplassistmvp0.apl2.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AplButton(
    label: String,
    icon: AplIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val sizing = modifier.widthIn(max = AplTokens.maxContentWidth).defaultMinSize(minHeight = AplTokens.minTouch)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        AplGlyph(icon)
        Text(
            label,
            modifier = Modifier.padding(start = AplTokens.space12).weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = sizing,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AplTokens.space24, vertical = AplTokens.space16),
            colors = ButtonDefaults.buttonColors(),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = sizing,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AplTokens.space24, vertical = AplTokens.space16),
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = .4f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            content = content,
        )
    }
}

@Composable
fun AplSettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: AplIcon? = null,
) {
    Surface(
        modifier = modifier.widthIn(max = AplTokens.maxContentWidth).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        BoxWithConstraints {
            val valueMaxWidth = maxWidth * .36f
            // Preserve a useful reading width when Android enlarges text or the window is narrow.
            // The trailing chevron keeps its own space; the value can use the full text column.
            val stackValue = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                    .defaultMinSize(minHeight = AplTokens.minTouch).padding(AplTokens.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AplTokens.space12),
            ) {
                if (icon != null) AplGlyph(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AplTokens.space4)) {
                    Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                    if (stackValue && !value.isNullOrBlank()) Text(
                        value,
                        // The adaptive column owns the reading width, including short values.
                        // Keep the Text's measured box and paragraph constraints consistent.
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!stackValue && !value.isNullOrBlank()) Text(
                    value,
                    modifier = Modifier.widthIn(max = valueMaxWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AplGlyph(AplIcon.Chevron, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun AplToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Surface(
        modifier = Modifier.widthIn(max = AplTokens.maxContentWidth).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .defaultMinSize(minHeight = AplTokens.minTouch).padding(AplTokens.space16),
            horizontalArrangement = Arrangement.spacedBy(AplTokens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AplTokens.space4)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
fun AplFloatingNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tabs = listOf("도움" to AplIcon.Home, "기록" to AplIcon.History, "설정" to AplIcon.Settings)
    Surface(
        modifier = modifier.widthIn(max = AplTokens.maxContentWidth).fillMaxWidth(),
        shape = RoundedCornerShape(AplTokens.radiusPill),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f)),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.selectableGroup().padding(AplTokens.space8),
            horizontalArrangement = Arrangement.spacedBy(AplTokens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                val active = selected == index
                val foreground = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(AplTokens.radiusPill))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                        .defaultMinSize(minHeight = 60.dp)
                        .padding(horizontal = AplTokens.space4, vertical = AplTokens.space8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AplTokens.space4, Alignment.CenterVertically),
                ) {
                    AplGlyph(icon, tint = foreground)
                    Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
                        color = foreground, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun AplPageHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.widthIn(max = AplTokens.maxContentWidth).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AplTokens.space8),
    ) {
        if (onBack != null) IconButton(
            onClick = onBack,
            modifier = Modifier.size(AplTokens.minTouch).semantics { contentDescription = "뒤로" },
        ) { AplGlyph(AplIcon.Back) }
        Text(title, modifier = Modifier.weight(1f).padding(vertical = AplTokens.space12).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun AplCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.widthIn(max = AplTokens.maxContentWidth).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(AplTokens.space24), verticalArrangement = Arrangement.spacedBy(AplTokens.space16), content = content)
    }
}
