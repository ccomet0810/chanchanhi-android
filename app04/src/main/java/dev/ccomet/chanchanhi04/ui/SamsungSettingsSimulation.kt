package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A simplified Galaxy-style illustration. No copied screenshots, account data or Samsung assets. */
internal object SamsungPreviewStyle{
    val Background=Color(0xFFF0F0F4)
    val Ink=Color(0xFF080808)
    val Blue=Color(0xFF3777FF)
    val Muted=Color(0xFF858589)
    val Divider=Color(0xFFE1E1E6)
}

@Composable private fun SamsungText(text:String,modifier:Modifier=Modifier,size:Float=17f,color:Color=SamsungPreviewStyle.Ink,bold:Boolean=false){
    Text(text,modifier,fontSize=size.sp,lineHeight=(size*1.35f).sp,color=color,fontWeight=if(bold)FontWeight.Bold else FontWeight.Normal)
}
@Composable internal fun SamsungHeader(title:String,root:Boolean=false){
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
        if(!root){AppGlyph(Glyph.BACK,Modifier.size(24.dp),SamsungPreviewStyle.Ink);Spacer(Modifier.width(12.dp))}
        SamsungText(title,Modifier.weight(1f),size=24f,bold=true)
        if(root)AppGlyph(Glyph.SEARCH,Modifier.size(24.dp),SamsungPreviewStyle.Ink)
    }
}
@Composable internal fun SamsungGroup(content:@Composable ColumnScope.()->Unit){
    Surface(Modifier.fillMaxWidth().padding(horizontal=10.dp),shape=RoundedCornerShape(28.dp),color=Color.White){Column(content=content)}
}
@Composable internal fun SamsungDivider(icon:Boolean=false){HorizontalDivider(Modifier.padding(start=if(icon)60.dp else 16.dp,end=16.dp),color=SamsungPreviewStyle.Divider)}
@Composable internal fun SamsungRow(label:String,modifier:Modifier=Modifier,subtitle:String?=null,icon:Glyph?=null,iconColor:Color=SamsungPreviewStyle.Blue,toggle:Boolean?=null,pressed:Boolean=false){
    Row(modifier.fillMaxWidth().background(if(pressed)Color(Foundation.Selected)else Color.Transparent).heightIn(min=54.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        if(icon!=null){Box(Modifier.size(30.dp).background(iconColor,CircleShape),contentAlignment=Alignment.Center){AppGlyph(icon,Modifier.size(20.dp),Color.White)};Spacer(Modifier.width(14.dp))}
        Column(Modifier.weight(1f)){
            SamsungText(label)
            subtitle?.let{SamsungText(it,size=13f,color=SamsungPreviewStyle.Blue)}
        }
        toggle?.let{SamsungToggle(it)}
    }
}
@Composable private fun SamsungToggle(checked:Boolean){
    Box(Modifier.size(34.dp,22.dp).background(if(checked)SamsungPreviewStyle.Blue else Color(0xFFA0A0A6),CircleShape).padding(3.dp),contentAlignment=if(checked)Alignment.CenterEnd else Alignment.CenterStart){
        Box(Modifier.size(16.dp).background(Color.White,CircleShape))
    }
}

/** Only the relevant settings are shown; this is not a pixel-for-pixel Samsung screen. */
@Composable internal fun SamsungSettingsScene(frame:IntroFrame,target:()->Modifier){
    val root=frame.page==IntroPage.SETTINGS
    Column(Modifier.fillMaxSize().background(SamsungPreviewStyle.Background)){
        SamsungHeader(if(root)"설정"else"디스플레이",root)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()){
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(18.dp)){
                if(root){
                    SamsungGroup{
                        SamsungRow("연결",icon=Glyph.WIFI);SamsungDivider(true)
                        SamsungRow("기기 간 연결",icon=Glyph.LINK)
                    }
                    SamsungGroup{
                        SamsungRow("소리 및 진동",icon=Glyph.VOLUME,iconColor=Color(0xFF7952FF));SamsungDivider(true)
                        SamsungRow("알림",icon=Glyph.NOTIFICATIONS,iconColor=Color(0xFFF26A21))
                    }
                    SamsungGroup{
                        SamsungRow("디스플레이",target(),icon=Glyph.SUN,iconColor=Color(0xFF9CD81B),pressed=frame.showUserTouch);SamsungDivider(true)
                        SamsungRow("배터리",icon=Glyph.BATTERY,iconColor=Color(0xFF67BE32))
                    }
                }else{
                    SamsungGroup{ThemePreview();SamsungDivider();SamsungRow("다크 모드 설정")}
                    SamsungGroup{
                        SamsungRow("글자 크기와 스타일",target(),pressed=frame.showUserTouch)
                        SamsungDivider();SamsungRow("화면 크게/작게")
                    }
                    SamsungGroup{
                        SamsungRow("부드러운 모션 및 화면 전환",subtitle="최적화");SamsungDivider()
                        SamsungRow("편안하게 화면 보기",toggle=false);SamsungDivider()
                        SamsungRow("화면 모드",subtitle="선명한 화면")
                    }
                }
            }
        }
    }
}

@Composable private fun ThemePreview(){
    Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=16.dp),horizontalArrangement=Arrangement.spacedBy(38.dp)){
        for(dark in listOf(false,true))Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(Modifier.fillMaxWidth().height(76.dp),shape=RoundedCornerShape(14.dp),color=if(dark)Color(0xFF373F44)else Color(0xFFDFE4E7)){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    repeat(2){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).background(Color(0xFF528DE6),CircleShape));Spacer(Modifier.width(8.dp));Box(Modifier.fillMaxWidth().height(6.dp).background(if(dark)Color(0xFF90999F)else Color.White))}}
                }
            }
            SamsungText(if(dark)"다크"else"라이트",color=if(dark)SamsungPreviewStyle.Ink else SamsungPreviewStyle.Blue,bold=!dark)
            Box(Modifier.size(20.dp).border(2.dp,if(dark)SamsungPreviewStyle.Muted else SamsungPreviewStyle.Blue,CircleShape).padding(4.dp)){
                if(!dark)Box(Modifier.fillMaxSize().background(SamsungPreviewStyle.Blue,CircleShape))
            }
        }
    }
}

@Composable internal fun SamsungFontSizeScene(frame:IntroFrame,target:Modifier,onThumb:(LayoutCoordinates)->Unit){
    val grow=if(frame.step==IntroStep.COMPLETE)1f else frame.contactProgress
    BoxWithConstraints(Modifier.fillMaxSize().background(SamsungPreviewStyle.Background)){
        val previewHeight=maxHeight*.48f
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)){
            SamsungHeader("글자 크기와 스타일")
            Surface(Modifier.fillMaxWidth().padding(horizontal=10.dp).height(previewHeight),shape=RoundedCornerShape(28.dp),color=Color.White){
                Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=24.dp),verticalArrangement=Arrangement.Bottom,horizontalAlignment=Alignment.CenterHorizontally){
                    Text("글자가 이 크기로 표시됩니다.",fontSize=(18+grow*10).sp,lineHeight=(25+grow*10).sp,color=SamsungPreviewStyle.Ink,textAlign=TextAlign.Center)
                    SamsungText("1234567890!@#%&*()_+-=",size=18+grow*3)
                }
            }
            SamsungGroup{SamsungRow("글꼴",subtitle="기본");SamsungDivider();SamsungRow("글자 굵게",toggle=false)}
            Spacer(Modifier.weight(1f))
            Surface(Modifier.fillMaxWidth().padding(horizontal=10.dp),shape=RoundedCornerShape(28.dp),color=Color.White){
                Row(Modifier.height(72.dp).padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically){
                    SamsungText("A",size=22f,bold=true);Spacer(Modifier.width(20.dp))
                    BoxWithConstraints(target.weight(1f).height(36.dp)){
                        val value=.25f+.5f*grow
                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(14.dp).background(SamsungPreviewStyle.Divider,CircleShape))
                        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){repeat(9){Box(Modifier.size(7.dp).background(Color(0xFFA0A0A6),CircleShape))}}
                        Box(Modifier.align(Alignment.CenterStart).offset(x=(maxWidth-20.dp)*value).size(20.dp).background(Color.White,CircleShape).border(2.dp, SamsungPreviewStyle.Blue,CircleShape).onGloballyPositioned(onThumb))
                    }
                    Spacer(Modifier.width(20.dp));SamsungText("A",size=32f,bold=true)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
