package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp

/** Shared geometry for the two independent onboarding processes. */
@Composable fun JourneyProgress(label:String,current:Int,total:Int,completed:Int=current){
    val fraction=completed.coerceIn(0,total).toFloat()/total.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(bottom=Foundation.SectionGapDp.dp).clearAndSetSemantics {
        contentDescription="$label $current/$total"
        progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f)
    },verticalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
        Text("$label $current/$total",style=MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
    }
}
