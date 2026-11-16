package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccomet.chanchanhi04.R
import kotlin.math.max
import kotlin.math.roundToInt

private enum class SetupTargetKind{ITEM,SWITCH,POPUP_FOOTER}
private data class SetupTarget(val instruction:String,val label:String,val kind:SetupTargetKind)
private fun setupTargets(direct:Boolean,serviceName:String)=buildList{
    if(!direct){
        add(SetupTarget("목록에서 ‘설치된 앱’을 눌러주세요.","설치된 앱",SetupTargetKind.ITEM))
        add(SetupTarget("아래 이름을 눌러주세요.",serviceName,SetupTargetKind.ITEM))
    }
    add(SetupTarget("‘사용 안 함’ 옆의 버튼을 눌러주세요.","사용 안 함",SetupTargetKind.SWITCH))
    add(SetupTarget("팝업 아래 ‘허용’을 눌러주세요.","허용",SetupTargetKind.POPUP_FOOTER))
}

private object SetupControlStyle{
    const val SwitchWidthFont=1.5f
    const val SwitchHeightFont=.85f
    const val FocusPadding=2
    const val FocusRadius=4
    const val FocusStroke=1
    const val Gap=4
    const val RowPadding=4
    const val InstructionScale=.86f
    const val PlaceholderHeight=8
}

@Composable fun PipChecklistFrame(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit){
    Box(modifier.background(Color(Foundation.Green)).padding(SetupCueStyle.FrameWidthDp.dp)
        .clip(RoundedCornerShape(SetupCueStyle.FrameInnerRadiusDp.dp)),content=content)
}

/** Each numbered instruction is followed by an inert illustration of its real Settings target. */
@Composable fun PipSetupChecklist(direct:Boolean,modifier:Modifier=Modifier){
    // Share Android's installed-services label, including its punctuation and emoji.
    val serviceName=stringResource(R.string.accessibility_name)
    val targets=remember(direct,serviceName){setupTargets(direct,serviceName)}
    val measurer=rememberTextMeasurer();val density=LocalDensity.current
    fun style(size:Float,instruction:Boolean=false)=TextStyle(fontSize=size.sp,
        lineHeight=(size*SetupCueStyle.ChecklistLineScale).sp,
        fontWeight=if(instruction)FontWeight.Bold else FontWeight.SemiBold,color=SamsungPreviewStyle.Ink)
    BoxWithConstraints(modifier.background(SamsungPreviewStyle.Background).padding(SetupCueStyle.PaddingDp.dp)
        .clearAndSetSemantics{contentDescription="화면 안내 설정. "+targets.mapIndexed{i,target->"${i+1}. ${target.instruction} 예시: ${target.label}"}.joinToString(" ")}){
        val font=remember(measurer,constraints,density.fontScale,targets){
            fun px(dp:Int)=with(density){dp.dp.roundToPx()}
            val padding=px(SetupControlStyle.RowPadding);val gap=px(SetupControlStyle.Gap)
            fun control(size:Int,ratio:Float)=(with(density){size.sp.toPx()}*ratio).roundToInt()+px(SetupControlStyle.FocusPadding)*2
            fun textHeight(text:String,size:Float,width:Int,instruction:Boolean=false)=measurer.measure(AnnotatedString(text),style(size,instruction),
                constraints=Constraints(maxWidth=width.coerceAtLeast(1))).size.height
            // Android controls the initial PiP size. Fit every explanation and example together.
            (SetupCueStyle.TitleSp downTo 1).firstOrNull{size->
                val body=targets.mapIndexed{i,target->
                    val explanation=textHeight("${i+1}. ${target.instruction}",size*SetupControlStyle.InstructionScale,constraints.maxWidth,true)
                    val example=when(target.kind){
                        SetupTargetKind.ITEM->textHeight(target.label,size.toFloat(),constraints.maxWidth-padding*2)+padding*2
                        SetupTargetKind.SWITCH->max(textHeight(target.label,size.toFloat(),constraints.maxWidth-padding*2-control(size,SetupControlStyle.SwitchWidthFont)-gap),control(size,SetupControlStyle.SwitchHeightFont))+padding*2
                        SetupTargetKind.POPUP_FOOTER->max(px(SetupControlStyle.PlaceholderHeight),
                            textHeight("허용",size*SetupControlStyle.InstructionScale,(constraints.maxWidth-padding*2-gap*2)/3-padding*2))+padding*4
                    }
                    explanation+gap+example
                }.sum()
                body+gap*(targets.size-1)<=constraints.maxHeight
            }?.toFloat()?:1f
        }
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceBetween){
            targets.forEachIndexed{i,target->
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(SetupControlStyle.Gap.dp)){
                    Text("${i+1}. ${target.instruction}",style=style(font*SetupControlStyle.InstructionScale,true))
                    if(target.kind==SetupTargetKind.POPUP_FOOTER)PopupFooter(font)
                    else Surface(shape=RoundedCornerShape(SetupControlStyle.FocusRadius.dp),color=Color.White,
                        border=if(target.kind==SetupTargetKind.ITEM)BorderStroke(SetupControlStyle.FocusStroke.dp,Color(Foundation.Green))else null){
                        Row(Modifier.fillMaxWidth().padding(SetupControlStyle.RowPadding.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SetupControlStyle.Gap.dp)){
                            Text(target.label,Modifier.weight(1f),style=style(font))
                            if(target.kind==SetupTargetKind.SWITCH){
                                val unit=with(density){font.sp.toDp()}
                                val height=unit*SetupControlStyle.SwitchHeightFont
                                Surface(shape=RoundedCornerShape(SetupControlStyle.FocusRadius.dp),color=Color.White,
                                    border=BorderStroke(SetupControlStyle.FocusStroke.dp,Color(Foundation.Green))){
                                    Box(Modifier.padding(SetupControlStyle.FocusPadding.dp)){
                                        Box(Modifier.size(unit*SetupControlStyle.SwitchWidthFont,height)
                                            .background(SamsungPreviewStyle.Muted,CircleShape).padding(height*.12f),contentAlignment=Alignment.CenterStart){
                                            Box(Modifier.size(height*.76f).background(Color.White,CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Only the confirmation popup's bottom actions. Actual selection happens in Settings. */
@Composable private fun PopupFooter(font:Float){
    Surface(shape=RoundedCornerShape(10.dp),color=Color.White){
        Row(Modifier.fillMaxWidth().padding(SetupControlStyle.RowPadding.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(SetupControlStyle.Gap.dp)){
            repeat(2){
                Box(Modifier.weight(1f).padding(SetupControlStyle.RowPadding.dp)){
                    Box(Modifier.fillMaxWidth().height(SetupControlStyle.PlaceholderHeight.dp)
                        .background(SamsungPreviewStyle.Divider,RoundedCornerShape(3.dp)))
                }
            }
            Surface(Modifier.weight(1f),shape=RoundedCornerShape(SetupControlStyle.FocusRadius.dp),color=Color.White,
                border=BorderStroke(SetupControlStyle.FocusStroke.dp,Color(Foundation.Green))){
                Text("허용",Modifier.padding(SetupControlStyle.RowPadding.dp),
                    fontSize=(font*SetupControlStyle.InstructionScale).sp,
                    lineHeight=(font*SetupControlStyle.InstructionScale*SetupCueStyle.ChecklistLineScale).sp,
                    fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                    color=SamsungPreviewStyle.Blue)
            }
        }
    }
}
