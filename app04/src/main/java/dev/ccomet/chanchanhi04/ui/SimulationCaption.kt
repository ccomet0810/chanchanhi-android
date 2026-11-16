package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shared footer for introductory and mission illustrations. It can grow with accessibility text. */
@Composable fun SimulationCaption(text:String){
    Box(Modifier.fillMaxWidth().heightIn(min=64.dp),contentAlignment=Alignment.Center){
        Text(text,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,
            fontWeight=FontWeight(Foundation.EmphasisFontWeight),color=Color(Foundation.Ink),textAlign=TextAlign.Center)
    }
}
