package dev.ccomet.chanchanhi04.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.ccomet.chanchanhi04.R

/** Shared tokens for Compose and native accessibility windows. All dimensions are dp/sp. */
object Foundation {
    const val Ink=0xFF19231D.toInt()
    const val Muted=0xFF3D4B42.toInt()
    const val Green=0xFF14532D.toInt()
    const val Paper=0xFFF7F8F5.toInt()
    const val Surface=0xFFFFFFFF.toInt()
    const val Selected=0xFFDDEBDD.toInt()
    const val Outline=0xFF829087.toInt()
    const val Divider=0xFFD7DFD8.toInt()
    const val Disabled=0xFFE4E9E4.toInt()
    const val Error=0xFFAA2E25.toInt()
    const val TextSp=22
    const val SecondaryTextSp=20
    const val ActionSp=22
    const val ActionLineSp=30
    const val GuideSp=30
    const val GuideLineSpacing=1.2f
    const val EmphasisFontWeight=700
    const val TouchDp=64
    const val ActionHorizontalPaddingDp=16
    const val ActionVerticalPaddingDp=12
    const val IconDp=28
    const val SmallIconDp=24
    const val GapDp=12
    const val HeaderTouchDp=48
    const val HeaderTitleGapDp=0
    const val ContentTopDp=0
    const val HeadingGapDp=8
    const val SectionGapDp=20
    const val PagePaddingDp=16
    const val PanelPaddingDp=16
    const val SheetMarginDp=12
    const val ButtonRadiusDp=20
    const val CardRadiusDp=24
    const val SheetRadiusDp=28
    const val MaxWidthDp=560
    const val SheetAlpha=246
    const val PanelBorder=0xFF56745F.toInt()
    const val PanelShadow=0x55334B3C
    const val PanelBorderDp=2
    const val PanelElevationDp=12
    const val PanelShadowInsetDp=8
    const val MotionMs=320
    const val GestureSpeed=.75f
    const val FocusStrokeDp=3
    const val FocusRadiusDp=8
    const val VoicePreviewLines=2
    const val VoiceTitleMaxHeightFraction=.4f
    const val SuccessIconDp=80
    const val SuccessMinHeightDp=280
}

/** PiP cues have a compact type scale; normal pages retain the large user-facing scale. */
object SetupCueStyle{
    const val PipAspectWidth=2
    const val PipAspectHeight=3
    const val FrameWidthDp=4
    const val FrameInnerRadiusDp=12
    const val PaddingDp=6
    const val TimerHeightDp=4
    const val TitleSp=19
    const val TitleLineSp=25
    const val HintSp=14
    const val HintLineSp=19
    const val TargetSp=18
    const val TargetLineSp=24
    const val MetaSp=11
    const val ChecklistMinSp=13
    const val ChecklistHeaderSp=16
    const val ChecklistLineScale=1.25f
    const val ChecklistGapDp=4
}

object UiCopy{
    const val EndGuidance="안내 종료"
    const val PipAfterAction="누른 뒤, 안내창에서 →를 눌러주세요."
}

/** Shared with the native guidance surface; used by live speech input and the simulation. */
@Composable fun AssistantSurface(modifier:Modifier=Modifier,content:@Composable ()->Unit){
    val shape=RoundedCornerShape(Foundation.SheetRadiusDp.dp)
    Surface(modifier.shadow(Foundation.PanelElevationDp.dp,shape,clip=false,ambientColor=Color(Foundation.PanelShadow),spotColor=Color(Foundation.PanelShadow)),
        shape=shape,color=Color(Foundation.Surface).copy(alpha=Foundation.SheetAlpha/255f),
        border=BorderStroke(Foundation.PanelBorderDp.dp,Color(Foundation.PanelBorder)),content=content)
}

/** Google's official Material Symbols Rounded; paths and provenance are vendored, never redrawn. */
enum class Glyph(@param:DrawableRes val resource:Int) {
    HOME(R.drawable.ms_home), SETTINGS(R.drawable.ms_settings), MIC(R.drawable.ms_mic),
    WRITE(R.drawable.ms_edit), LOCK(R.drawable.ms_lock), NEXT(R.drawable.ms_arrow_forward),
    BACK(R.drawable.ms_arrow_back), CHECK(R.drawable.ms_check), CLOSE(R.drawable.ms_close),
    SCIENCE(R.drawable.ms_science), INFO(R.drawable.ms_info),
    WIFI(R.drawable.ms_wifi), VOLUME(R.drawable.ms_volume_up), NOTIFICATIONS(R.drawable.ms_notifications),
    PIP(R.drawable.ms_picture_in_picture_alt),
    SUN(R.drawable.ms_light_mode), BATTERY(R.drawable.ms_battery_full), SEARCH(R.drawable.ms_search),
    PREVIOUS_SCENE(R.drawable.ms_skip_previous), NEXT_SCENE(R.drawable.ms_skip_next),
    PLAY(R.drawable.ms_play_arrow), PAUSE(R.drawable.ms_pause), REPEAT(R.drawable.ms_replay), LOG(R.drawable.ms_history),
    LINK(R.drawable.ms_link), SHIELD(R.drawable.ms_accessibility_new), CHEVRON(R.drawable.ms_chevron_right),
    ASSISTANT(R.drawable.ms_assistant), SEND(R.drawable.ms_send), CAMERA(R.drawable.ms_photo_camera), PHOTOS(R.drawable.ms_photo_library)
}
fun symbolDrawable(context:Context,glyph:Glyph,size:Int,tint:Int=Foundation.Green):Drawable =
    requireNotNull(ContextCompat.getDrawable(context,glyph.resource)).mutate().apply{setTint(tint);setBounds(0,0,size,size)}

@Composable fun AppGlyph(glyph:Glyph,modifier:Modifier=Modifier,color:Color=Color(Foundation.Green)) {
    Icon(painterResource(glyph.resource),contentDescription=null,modifier=modifier.size(Foundation.IconDp.dp),tint=color)
}
@Composable fun ChanchanhiTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(
        primary=Color(Foundation.Green),onPrimary=Color.White,primaryContainer=Color(Foundation.Selected),onPrimaryContainer=Color(Foundation.Green),
        secondary=Color(Foundation.Green),onSecondary=Color.White,secondaryContainer=Color(Foundation.Selected),onSecondaryContainer=Color(Foundation.Ink),
        background=Color(Foundation.Paper),onBackground=Color(Foundation.Ink),surface=Color.White,onSurface=Color(Foundation.Ink),
        surfaceVariant=Color(Foundation.Disabled),onSurfaceVariant=Color(Foundation.Muted),surfaceContainer=Color(Foundation.Paper),
        outline=Color(Foundation.Outline),outlineVariant=Color(Foundation.Divider),error=Color(Foundation.Error),onError=Color.White),
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(Foundation.ButtonRadiusDp.dp),large=RoundedCornerShape(Foundation.CardRadiusDp.dp),extraLarge=RoundedCornerShape(Foundation.SheetRadiusDp.dp)),
        typography=Typography(
            headlineLarge=TextStyle(fontSize=36.sp,lineHeight=46.sp,fontWeight=FontWeight.Bold),
            headlineMedium=TextStyle(fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold),
            headlineSmall=TextStyle(fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold),
            titleLarge=TextStyle(fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold),
            titleMedium=TextStyle(fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold),
            titleSmall=TextStyle(fontSize=22.sp,lineHeight=32.sp,fontWeight=FontWeight.SemiBold),
            bodyLarge=TextStyle(fontSize=22.sp,lineHeight=32.sp),bodyMedium=TextStyle(fontSize=20.sp,lineHeight=30.sp),
            bodySmall=TextStyle(fontSize=18.sp,lineHeight=26.sp),labelLarge=TextStyle(fontSize=Foundation.ActionSp.sp,lineHeight=Foundation.ActionLineSp.sp,fontWeight=FontWeight(Foundation.EmphasisFontWeight)),
            labelMedium=TextStyle(fontSize=Foundation.ActionSp.sp,lineHeight=Foundation.ActionLineSp.sp,fontWeight=FontWeight(Foundation.EmphasisFontWeight)),labelSmall=TextStyle(fontSize=18.sp,lineHeight=26.sp)),content=content)
}
@Composable fun LargeAction(label:String,glyph:Glyph,onClick:()->Unit,modifier:Modifier=Modifier,secondary:Boolean=false,enabled:Boolean=true) {
    val shape=RoundedCornerShape(Foundation.ButtonRadiusDp.dp)
    val sizing=modifier.fillMaxWidth().heightIn(min=Foundation.TouchDp.dp)
    val padding=PaddingValues(Foundation.ActionHorizontalPaddingDp.dp,Foundation.ActionVerticalPaddingDp.dp)
    val body:@Composable RowScope.()->Unit={
        AppGlyph(glyph,color=if(!enabled)Color(Foundation.Muted)else if(secondary)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(Foundation.GapDp.dp))
        Text(label,Modifier.weight(1f,fill=false),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelLarge)
    }
    if(secondary)OutlinedButton(onClick,sizing,enabled=enabled,shape=shape,
        border=BorderStroke(1.5.dp,if(enabled)MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.White,disabledContentColor=Color(Foundation.Muted)),contentPadding=padding,content=body)
    else Button(onClick,sizing,enabled=enabled,shape=shape,
        colors=ButtonDefaults.buttonColors(disabledContainerColor=Color(Foundation.Disabled),disabledContentColor=Color(Foundation.Muted)),contentPadding=padding,content=body)
}
@Composable fun SettingsAction(title:String,status:String?,glyph:Glyph,enabled:Boolean=true,onClick:()->Unit){
    Surface(onClick,enabled=enabled,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.heightIn(min=(if(status==null)Foundation.TouchDp else 104).dp).padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            AppGlyph(glyph)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(title,style=MaterialTheme.typography.titleMedium);if(status!=null)Text(status,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppGlyph(if(enabled)Glyph.CHEVRON else Glyph.CHECK,Modifier.size(24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun SettingsToggle(title:String,explanation:String?,checked:Boolean,enabled:Boolean=true,onChecked:(Boolean)->Unit){
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.fillMaxWidth().toggleable(value=checked,enabled=enabled,role=Role.Switch,onValueChange=onChecked).semantics(mergeDescendants=true){}.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked,onCheckedChange=null,enabled=enabled,modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=64.dp))
            }
            if(explanation!=null)Text(explanation,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun FloatingNavigation(settings:Boolean,onSelect:(Boolean)->Unit){
    val stacked=LocalDensity.current.fontScale>=1.3f
    Surface(shape=RoundedCornerShape(32.dp),shadowElevation=4.dp,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().padding(vertical=12.dp)){
        Row(Modifier.selectableGroup().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(false to ("홈" to Glyph.HOME),true to ("설정" to Glyph.SETTINGS)).forEach{(selected,label)->
                Surface(shape=RoundedCornerShape(24.dp),color=if(settings==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.weight(1f)){
                    val action=Modifier.selectable(settings==selected,role=Role.Tab,onClick={onSelect(selected)}).heightIn(min=64.dp).padding(12.dp)
                    if(stacked)Column(action,verticalArrangement=Arrangement.spacedBy(4.dp),horizontalAlignment=Alignment.CenterHorizontally){AppGlyph(label.second);Text(label.first,style=MaterialTheme.typography.labelLarge)}
                    else Row(action,horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically){AppGlyph(label.second);Text(label.first,style=MaterialTheme.typography.labelLarge)}
                }
            }
        }
    }
}
@Composable fun DialogBody(text:String){Text(text,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodyMedium)}
@Composable fun SelectionOption(label:String,selected:Boolean,enabled:Boolean=true,checkbox:Boolean=false,onSelect:()->Unit){
    Surface(shape=MaterialTheme.shapes.medium,color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border=BorderStroke(if(selected)1.5.dp else 1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){
        val control=if(checkbox)Modifier.toggleable(selected,enabled=enabled,role=Role.Checkbox,onValueChange={onSelect()})else Modifier.selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onSelect)
        Row(Modifier.fillMaxWidth().heightIn(min=76.dp).then(control).semantics(mergeDescendants=true){}.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            if(checkbox)Checkbox(selected,null,enabled=enabled)else RadioButton(selected,null,enabled=enabled)
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable fun SettingsAction(title:String,status:String?,glyph:Glyph,onClick:()->Unit)=SettingsAction(title,status,glyph,true,onClick)

@Composable fun InformationTextLink(label:String,onClick:()->Unit){
    TextButton(onClick,modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=0.dp,vertical=8.dp)){
        Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
    }
}
