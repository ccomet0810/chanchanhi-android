package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One grouping rule for every settings category, including administration. */
fun LazyListScope.settingsGroup(title:String,first:Boolean=false,content:LazyListScope.()->Unit){
    if(!first)item{HorizontalDivider(Modifier.padding(vertical=4.dp))}
    item{Text(title,style=MaterialTheme.typography.titleMedium)}
    content()
}
