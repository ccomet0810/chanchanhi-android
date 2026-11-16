package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Navigation belongs to the journey, not to the permission being displayed. */
enum class PageLayout { ONBOARDING, SETTINGS }

@Composable fun OnboardingPage(onSettings:(()->Unit)?=null,actions:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    AppPage("찬찬히",onSettings=onSettings,actions=actions,content=content)
}
@Composable fun SettingsPage(title:String,onBack:(()->Unit)?=null,actions:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    AppPage(title,onBack=onBack,actions=actions,content=content)
}
@Composable fun PreparationPage(layout:PageLayout,title:String,onBack:(()->Unit)?,actions:@Composable ()->Unit,content:@Composable ColumnScope.()->Unit){
    if(layout==PageLayout.ONBOARDING)OnboardingPage(actions=actions,content=content)
    else SettingsPage(title,onBack,actions,content)
}

/** One safe-area owner, one header, one bottom action area on every application page. */
@Composable fun AppPage(title:String,onBack:(()->Unit)?=null,onSettings:(()->Unit)?=null,
    actions:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime))
         ){
        AppHeader(title,onBack,onSettings)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal=Foundation.PagePaddingDp.dp).padding(top=Foundation.ContentTopDp.dp).clipToBounds(),content=content)
        if(actions!=null)Box(Modifier.fillMaxWidth().padding(horizontal=Foundation.PagePaddingDp.dp).padding(top=Foundation.GapDp.dp,bottom=8.dp)){actions()}
    }
}

// Material 3 1.4 supplies 4dp slot padding and a 12dp minimum title start.
// Adapt its slots, rather than moving/clipping the whole bar or shrinking touch targets.
private val MaterialHeaderSlotInset=4.dp
private val MaterialHeaderTitleStart=12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppHeader(title:String,onBack:(()->Unit)?=null,onSettings:(()->Unit)?=null){
    val buttonEdge=(Foundation.PagePaddingDp.dp-MaterialHeaderSlotInset).coerceAtLeast(0.dp)
    val titleGap=Foundation.HeaderTitleGapDp.dp
    // With no navigation button Material measures a 4dp slot, then places the title at 12dp.
    // Account for that extra 8dp at the other end so long titles still clear the action slot.
    val titleEnd=titleGap+if(onBack==null)MaterialHeaderTitleStart-MaterialHeaderSlotInset else 0.dp
    TopAppBar(
        title={Text(title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(start=if(onBack!=null)titleGap else 0.dp,end=titleEnd,top=Foundation.HeadingGapDp.dp,bottom=Foundation.HeadingGapDp.dp).semantics{heading()})},
        navigationIcon={if(onBack!=null)Box(Modifier.padding(start=buttonEdge)){
            HeaderIconButton("뒤로가기",Glyph.BACK,onBack)
        }},
        actions={if(onSettings!=null)Box(Modifier.padding(end=buttonEdge)){
            HeaderIconButton("설정",Glyph.SETTINGS,onSettings)
        }},
        windowInsets=WindowInsets(0,0,0,0),
        colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))
}

@Composable private fun HeaderIconButton(label:String,glyph:Glyph,onClick:()->Unit){
    IconButton(onClick,Modifier.size(Foundation.HeaderTouchDp.dp).semantics{contentDescription=label}){AppGlyph(glyph)}
}

@Composable fun PageHeading(title:String,description:String?=null,action:(@Composable ()->Unit)?=null){
    Column(verticalArrangement=Arrangement.spacedBy(Foundation.HeadingGapDp.dp)){
        Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})
        if(!description.isNullOrBlank())Text(description,style=MaterialTheme.typography.bodyLarge)
        if(action!=null)action()
    }
}
