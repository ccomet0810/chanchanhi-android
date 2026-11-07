package com.example.aplassistmvp0.apl2.design

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.R

enum class AplIcon { Home, History, Settings, Mic, Edit, Close, Back, Chevron, Volume, Check, Trash, Help, Phone, Lock, ArrowRight }

/** Google Material Symbols Rounded; the containing control supplies the accessible label. */
@Composable
fun AplGlyph(icon: AplIcon, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val resource = when (icon) {
        AplIcon.Home -> R.drawable.ms_home
        AplIcon.History -> R.drawable.ms_history
        AplIcon.Settings -> R.drawable.ms_settings
        AplIcon.Mic -> R.drawable.ms_mic
        AplIcon.Edit -> R.drawable.ms_edit
        AplIcon.Close -> R.drawable.ms_close
        AplIcon.Back -> R.drawable.ms_arrow_back
        AplIcon.Chevron -> R.drawable.ms_chevron_right
        AplIcon.Volume -> R.drawable.ms_volume_up
        AplIcon.Check -> R.drawable.ms_check
        AplIcon.Trash -> R.drawable.ms_delete
        AplIcon.Help -> R.drawable.ms_help
        AplIcon.Phone -> R.drawable.ms_smartphone
        AplIcon.Lock -> R.drawable.ms_lock
        AplIcon.ArrowRight -> R.drawable.ms_arrow_forward
    }
    Icon(painterResource(resource), contentDescription = null, modifier = modifier.size(24.dp), tint = tint)
}
