package dev.ccomet.chanchanhi04.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import dev.ccomet.chanchanhi04.setup.SetupSnapshot

/** OS grants are live state; screen-service binding remains a separate runtime signal. */
@Composable internal fun rememberSetupSnapshot(refresh:Int=0):SetupSnapshot{
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var snapshot by remember(context){mutableStateOf(SetupPermissions.read(context))}
    LaunchedEffect(refresh){snapshot=SetupPermissions.read(context)}
    DisposableEffect(context,owner){
        fun update(){snapshot=SetupPermissions.read(context)}
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){update()}}
        val lifecycle=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)update()}
        context.contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI,true,observer)
        owner.lifecycle.addObserver(lifecycle)
        onDispose{context.contentResolver.unregisterContentObserver(observer);owner.lifecycle.removeObserver(lifecycle)}
    }
    return snapshot
}
