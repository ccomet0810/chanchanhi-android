package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.provider.Settings
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.R
import dev.ccomet.chanchanhi04.platform.AppRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only navigation and temporary inert labels, launched as the ordinary app UID. */
@RunWith(AndroidJUnit4::class)
class SettingsEntryAuditTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun nodes():List<AccessibilityNodeInfo>{if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){if(n==null)return;result+=n;repeat(n.childCount){visit(n.getChild(it))}};visit(automation.rootInActiveWindow);return result}
    private fun find(label:String)=nodes().firstOrNull{it.isVisibleToUser&&it.text?.toString()==label}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(150)};error(message)}
    private fun click(label:String){Thread.sleep(500);var n=find(label)?:error(label);while(!n.isClickable)n=n.parent?:error("No clickable ancestor");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(700)}
    private fun open(intent:Intent){
        var failure:Exception?=null
        i.runOnMainSync{try{context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))}catch(e:Exception){failure=e}}
        failure?.let{throw it};Thread.sleep(800)
    }
    private fun installedHeader()=nodes().any{n->val r=Rect();n.getBoundsInScreen(r);n.isVisibleToUser&&n.text?.toString()=="설치된 앱"&&r.top<350}
    private fun scrollList(forward:Boolean){
        val bounds=Rect();automation.rootInActiveWindow.getBoundsInScreen(bounds)
        val x=bounds.centerX();val high=(bounds.top+bounds.height()*.32).toInt();val low=(bounds.top+bounds.height()*.80).toInt()
        val command=if(forward)"input swipe $x $low $x $high 300"else"input swipe $x $high $x $low 300"
        automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}
        Thread.sleep(400)
    }
    @Test fun inspectInstalledListEntryAndActualSymbolOrdering(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runSettingsAudit")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        i.runOnMainSync{AppRuntime.initialize(context);AppRuntime.setup.cancel()}
        val out=File(context.getExternalFilesDir(null),"settings-entry-audit").apply{mkdirs()}
        val fragment="com.samsung.android.settings.accessibility.install.InstalledAppsFragment"
        val probes=listOf("accessibility" to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            "accessibility-with-fragment" to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:show_fragment",fragment),
            "root-with-fragment" to Intent(Settings.ACTION_SETTINGS).putExtra(":settings:show_fragment",fragment),
            "private-list-component" to Intent().setComponent(ComponentName("com.android.settings","com.android.settings.SubSettings")).putExtra(":settings:show_fragment",fragment))
        val results=JSONArray()
        for((name,intent) in probes){
            open(Intent(Settings.ACTION_SETTINGS))
            var failure:String?=null
            try{open(intent)}catch(e:Exception){failure=e.javaClass.simpleName}
            results.put(JSONObject().put("entry",name).put("installedListOpened",installedHeader()).put("failure",failure))
        }
        File(out,"entry-results.json").writeText(results.toString(2))
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));await("Installed apps entry missing"){find("설치된 앱")!=null};click("설치된 앱")
        await("Installed list header missing"){installedHeader()}
        val targetLabels=setOf("! 찬찬히 정렬 시험","1 찬찬히 정렬 시험","★ 찬찬히 정렬 시험","😀 찬찬히 정렬 시험",context.getString(R.string.accessibility_name),"AhnLab V3 Mobile Plus")
        val observed=linkedSetOf<String>()
        repeat(18){
            nodes().filter{it.isVisibleToUser&&it.text?.toString() in targetLabels}.sortedBy{n->Rect().also{n.getBoundsInScreen(it)}.top}.forEach{observed+=it.text.toString()}
            if(observed.containsAll(targetLabels))return@repeat
            scrollList(true)
        }
        File(out,"sorting-results.json").writeText(JSONObject().put("observedOrder",JSONArray(observed.toList())).put("allCandidatesObserved",observed.containsAll(targetLabels)).toString(2))
        assertTrue("All temporary labels must be observed in the real system list",observed.containsAll(targetLabels))
        // Return to the top for a public screenshot of the ordering. No service is enabled.
        repeat(4){scrollList(false)}
        automation.takeScreenshot()?.let{b->File(out,"sorting-top.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        open(Intent(context,MainActivity::class.java))
    }
}
