package dev.ccomet.chanchanhi04.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import dev.ccomet.chanchanhi04.platform.AssistantService
import dev.ccomet.chanchanhi04.platform.ScreenService

object SetupPermissions{
    fun read(context:Context)=SetupSnapshot(
        Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).contains(ComponentName(context,ScreenService::class.java)),
        ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,
        VoiceInteractionService.isActiveService(context,ComponentName(context,AssistantService::class.java)))
    /** Public accessibility landing page only. A remembered service-details destination must
     * never skip the first page or rely on an installer-only Settings route. */
    @Suppress("UNUSED_PARAMETER") fun opensServiceDetails(context:Context)=false
    fun intents(context:Context,requirement:SetupRequirement):List<Intent> = when(requirement){
        // Private fragment arguments can reopen a previously visited child page on Samsung.
        // The public fallback requests the accessibility landing page without that routing hint.
        SetupRequirement.SCREEN->listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        // Native RECORD_AUDIO onboarding owns this capability. No Settings mission route.
        SetupRequirement.MICROPHONE->emptyList()
        SetupRequirement.ASSISTANT->listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
    /** Resolve the platform destination rather than assuming a manufacturer's package or buttons. */
    fun settingsPackages(context:Context):Set<String> = (SetupRequirement.entries.flatMap{intents(context,it)}+
        listOf(Intent(Settings.ACTION_SETTINGS),Intent("android.settings.APP_SEARCH_SETTINGS"),Intent("android.intent.action.MANAGE_APP_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME,context.packageName)))
        .mapNotNull{context.packageManager.resolveActivity(it,PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo?.packageName}.toSet()
    /** Informational system package resolution only; it does not select a navigation action. */
    fun settingsCompanions(context:Context):Set<String>{
        val pm=context.packageManager;val owners=settingsPackages(context)
        val searches=pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH),PackageManager.MATCH_SYSTEM_ONLY)
        return searches.filter{info->owners.any{pm.checkSignatures(it,info.activityInfo.packageName)==PackageManager.SIGNATURE_MATCH}}
            .map{it.activityInfo.packageName}.toSet()
    }
    fun open(context:Context,requirement:SetupRequirement):Boolean{
        // Re-enter the requested destination instead of resurfacing an old nested settings page.
        for(intent in intents(context,requirement))try{context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP));return true}catch(_:android.content.ActivityNotFoundException){}catch(_:SecurityException){}
        return false
    }
    /** A new mission starts at the Settings home, with no old detail pages to back through. */
    fun openHome(context:Context):Boolean{
        val intent=Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val info=context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo?:return false
        intent.component=ComponentName(info.packageName,info.name)
        return try{context.startActivity(intent);true}catch(_:android.content.ActivityNotFoundException){false}catch(_:SecurityException){false}
    }
}
