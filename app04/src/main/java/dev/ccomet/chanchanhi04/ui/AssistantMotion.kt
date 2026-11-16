package dev.ccomet.chanchanhi04.ui

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/** Appearance is independent of transcript, levels and recognition state. */
object AssistantMotion{
    const val EnterMs=420
    const val QuietMs=380
    const val AttackMs=260
    const val LevelMs=85
    const val DockMs=650
    // Only for non-IME previews. Live keyboard folds use the platform's inset progress.
    const val KeyboardFoldMs=Foundation.MotionMs
    const val ScrimAlpha=.18f
    fun reveal(ms:Long)=FastOutSlowInEasing.transform((ms.toFloat()/EnterMs).coerceIn(0f,1f))
    fun quietGain(ms:Long)=1f-FastOutSlowInEasing.transform((ms.toFloat()/QuietMs).coerceIn(0f,1f))
    fun attackGain(ms:Long)=FastOutSlowInEasing.transform((ms.toFloat()/AttackMs).coerceIn(0f,1f))
    fun dock(ms:Long)=FastOutSlowInEasing.transform((ms.toFloat()/DockMs).coerceIn(0f,1f))
}

/** Runs once for a new sheet, never again when a word or RMS sample arrives. */
@Composable fun rememberAssistantReveal():Float{
    val context=LocalContext.current
    val reduced=!ValueAnimator.areAnimatorsEnabled()||context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    var revealed by rememberSaveable{mutableStateOf(false)}
    val elapsed=remember{Animatable(if(revealed||reduced)1f else 0f)}
    LaunchedEffect(Unit){
        if(reduced)elapsed.snapTo(1f)else elapsed.animateTo(1f,tween(AssistantMotion.EnterMs,easing=LinearEasing))
        revealed=true
    }
    return AssistantMotion.reveal((elapsed.value*AssistantMotion.EnterMs).toLong())
}

/** Translation changes drawing only, so speech updates cannot start a new measurement animation. */
fun Modifier.assistantReveal(progress:Float)=graphicsLayer{
    val p=progress.coerceIn(0f,1f)
    translationY=(size.height+Foundation.SheetMarginDp*density)*(1f-p)
    alpha=p
}

@Composable fun AssistantScrim(progress:Float,modifier:Modifier){
    Box(modifier.background(Color.Black.copy(alpha=AssistantMotion.ScrimAlpha*progress.coerceIn(0f,1f))))
}
