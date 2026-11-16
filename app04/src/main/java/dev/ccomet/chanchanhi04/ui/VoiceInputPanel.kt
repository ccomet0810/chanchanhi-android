package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.animation.ValueAnimator
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.voice.VoicePhase
import dev.ccomet.chanchanhi04.voice.VoiceState

enum class VoiceSecondary(val label:String,val glyph:Glyph){
    STOP("듣기 중지",Glyph.PAUSE),RETRY("다시 말하기",Glyph.MIC),ALLOW_MICROPHONE("마이크 허용하기",Glyph.MIC)
}

/** Presentation only. Recognition, editing and submitting remain owned by VoiceInputActivity. */
data class VoicePanelPresentation(val title:String,val active:Boolean,val secondary:VoiceSecondary?){
    companion object{
        fun forState(phase:VoicePhase,answering:Boolean=false,message:String="",microphoneAllowed:Boolean=true,stale:Boolean=false):VoicePanelPresentation{
            val title=when(phase){
                VoicePhase.STARTING->"마이크를 켜고 있어요"
                VoicePhase.LISTENING->"듣고 있어요"
                VoicePhase.PROCESSING->"입력을 마무리해요"
                VoicePhase.REVIEW->"내용을 확인해주세요"
                VoicePhase.ERROR->message
                VoicePhase.TEXT->if(answering)"답변을 적어주세요"else"하고 싶은 일을 적어주세요"
                VoicePhase.CLOSED->"음성 입력을 마쳤어요"
            }
            val secondary=when{
                stale->null
                phase in setOf(VoicePhase.STARTING,VoicePhase.LISTENING)->VoiceSecondary.STOP
                phase in setOf(VoicePhase.PROCESSING,VoicePhase.REVIEW,VoicePhase.TEXT,VoicePhase.ERROR)->if(microphoneAllowed)VoiceSecondary.RETRY else VoiceSecondary.ALLOW_MICROPHONE
                else->null
            }
            return VoicePanelPresentation(title,phase in setOf(VoicePhase.STARTING,VoicePhase.LISTENING,VoicePhase.PROCESSING),secondary)
        }
    }
}

/** One layout for live speech and the offline introduction, including review and retry controls. */
@Composable fun VoiceInputPanel(
    state:VoiceState,canSubmit:Boolean,onClose:()->Unit,onSubmit:()->Unit,onSecondary:(VoiceSecondary)->Unit,
    modifier:Modifier=Modifier,answering:Boolean=false,keyboardVisible:Boolean=false,microphoneAllowed:Boolean=true,
    stale:Boolean=false,smoothWaveform:Boolean=true,waveformGain:Float?=null,sendModifier:Modifier=Modifier,
    keyboardProgress:(()->Float)?=null,editor:@Composable ()->Unit
){
    val presentation=VoicePanelPresentation.forState(state.phase,answering,state.message,microphoneAllowed,stale)
    // Live input supplies deferred native inset progress. Only previews may use a local clock.
    val collapse=keyboardProgress?:run{
        val fallback=animateFloatAsState(if(keyboardVisible)1f else 0f,
            tween(if(ValueAnimator.areAnimatorsEnabled())AssistantMotion.KeyboardFoldMs else 0,easing=FastOutSlowInEasing),label="voice keyboard fold")
        remember(fallback){{fallback.value}}
    }
    AssistantSurface(modifier){
        Column(Modifier.padding(Foundation.PanelPaddingDp.dp)){
            VoiceKeyboardFold(collapse,Alignment.Bottom){
                Column{
                    Row(verticalAlignment=Alignment.CenterVertically){
                        AppGlyph(Glyph.MIC);Spacer(Modifier.width(Foundation.GapDp.dp))
                        Text(if(answering)"답변하기"else"찬찬히",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClose,modifier=Modifier.heightIn(min=Foundation.TouchDp.dp)){AppGlyph(Glyph.CLOSE);Spacer(Modifier.width(6.dp));Text("닫기")}
                    }
                    Spacer(Modifier.height(Foundation.GapDp.dp))
                }
            }
            // Keep the title outside the editor's scroll area. At extreme font/viewport sizes
            // its own text can scroll, reserving space for editing and the send action.
            Text(presentation.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.layout{measurable,constraints->
                val limit=if(constraints.hasBoundedHeight)(constraints.maxHeight*Foundation.VoiceTitleMaxHeightFraction).toInt()else constraints.maxHeight
                val text=measurable.measure(constraints.copy(minHeight=0,maxHeight=limit))
                layout(text.width,text.height){text.placeRelative(0,0)}
            }.verticalScroll(rememberScrollState()).semantics{liveRegion=LiveRegionMode.Polite})
            Spacer(Modifier.height(Foundation.GapDp.dp))
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())){
                // Recognition can end as editing starts. Only this region changes height; the
                // editor stays at the same composition position with its focus and selection.
                AnimatedVisibility(presentation.active||state.phase==VoicePhase.REVIEW,
                    enter=expandVertically(tween(AssistantMotion.KeyboardFoldMs),expandFrom=Alignment.Bottom),
                    exit=shrinkVertically(tween(AssistantMotion.KeyboardFoldMs),shrinkTowards=Alignment.Bottom)){
                    Column{
                        val recording=state.phase in setOf(VoicePhase.STARTING,VoicePhase.LISTENING)
                        val envelope=remember{Animatable(0f)}
                        LaunchedEffect(recording){envelope.animateTo(if(recording)1f else 0f,tween(if(recording)AssistantMotion.AttackMs else AssistantMotion.QuietMs,easing=FastOutSlowInEasing))}
                        val gain=waveformGain?:envelope.value
                        VoiceWaveform(state.levels,smoothing=smoothWaveform,gain=gain,description=if(recording)"음성 크기 파형"else"입력한 음성 파형")
                        Spacer(Modifier.height(Foundation.GapDp.dp))
                    }
                }
                editor()
            }
            Spacer(Modifier.height(Foundation.GapDp.dp))
            LargeAction("전송",Glyph.SEND,onSubmit,modifier=sendModifier,enabled=canSubmit)
            presentation.secondary?.let{action->
                VoiceKeyboardFold(collapse,Alignment.Top){
                    Column{
                        Spacer(Modifier.height(Foundation.GapDp.dp))
                        LargeAction(action.label,action.glyph,{onSecondary(action)},secondary=true)
                    }
                }
            }
        }
    }
}

@Composable fun VoiceDraftEditor(
    value:TextFieldValue,onValueChange:(TextFieldValue)->Unit,modifier:Modifier=Modifier,answering:Boolean=false,
    enabled:Boolean=true,readOnly:Boolean=false,maxLines:Int=4,interactionSource:MutableInteractionSource?=null
){
    OutlinedTextField(value,onValueChange,
        placeholder={Text(if(answering)"답변을 말씀하거나 입력해주세요"else"말씀하거나 입력해주세요")},
        modifier=modifier.fillMaxWidth(),interactionSource=interactionSource,enabled=enabled,readOnly=readOnly,shape=MaterialTheme.shapes.medium,
        minLines=minOf(Foundation.VoicePreviewLines,maxLines),maxLines=maxLines,
        textStyle=MaterialTheme.typography.bodyLarge.copy(platformStyle=PlatformTextStyle(includeFontPadding=false),
            lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)))
}
