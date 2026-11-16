package dev.ccomet.chanchanhi04.qa

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.study.*
import dev.ccomet.chanchanhi04.research.ResearchConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StudyPersistenceTest {
    @Test fun crashRecoveryDraftsParticipantBoundaryAndConsentNeverLeakAcrossPeople(){
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString();val directory=File(base.cacheDir,"study-isolation-$suffix").apply{mkdirs()}
        val isolated=object:ContextWrapper(base){override fun getApplicationContext():Context=this;override fun getFilesDir()=directory;override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("isolated-$suffix-$name",mode)}
        isolated.getSharedPreferences("research-1",0).edit().putString("token","public-fixture-not-a-network-credential").putBoolean("enabled",true).putBoolean("screenImages",true).commit()
        try{
            val repo=StudyRepository(isolated,{});repo.testRun=true;repo.consent(false)
            repo.submit("profile_answered",JSONObject().put("field","name").put("value","공개 저장소 시험"))
            repo.saveDraft("profile",JSONObject().put("name","공개 저장소 시험").put("age","70").put("_index",1))
            val restored=StudyRepository(isolated,{})
            assertEquals(StudyPhase.PROFILE,restored.state.value.phase);assertEquals("70",restored.draft("profile").getString("age"));assertEquals(1,restored.draft("profile").getInt("_index"))
            val first=restored.state.value.runId!!;val file=File(StudyRepository.directory(isolated),"$first.jsonl");val original=file.readText()
            AtomicFile(file).startWrite().use{it.write("broken interrupted replacement".toByteArray())}
            val recovered=StudyRepository(isolated,{})
            assertEquals(StudyPhase.PROFILE,recovered.state.value.phase);assertEquals(original,file.readText())
            recovered.submit("profile_submitted",JSONObject().put("name","공개 저장소 시험").put("age",70).put("gender","응답하지 않음").put("occupation","은퇴"))
            val baseline=JSONObject();recovered.rules.questions("baseline").forEach{baseline.put(it.getString("id"),2)};recovered.submit("baseline_submitted",baseline)
            assertEquals(recovered.rules.defaultPreset()!!.getString("id"),recovered.state.value.mission!!.getString("id"))
            val journalSize=StudyRepository.read(file).size;recovered.ensureDefaultMission();assertEquals(journalSize,StudyRepository.read(file).size)
            recovered.assignMission("공개 미션","","공개 미션")
            val mission=recovered.state.value.mission!!.getString("id")
            recovered.submit("mission_pre_submitted",JSONObject().put("missionId",mission).put("confidence",2))
            val oldSession=UUID.randomUUID().toString();recovered.linkSession(oldSession,"공개 미션")
            val connection=ResearchConnection(isolated);connection.begin(oldSession);assertFalse(connection.imageEligible(oldSession))
            val oldEpoch=recovered.state.value.uiEpoch;recovered.nextParticipant();assertEquals(StudyPhase.INTRO,recovered.state.value.phase);assertTrue(file.exists());assertTrue(recovered.state.value.uiEpoch>oldEpoch);assertEquals(0,recovered.draft("intro").optInt("_index",0))
            recovered.testRun=true;recovered.consent(true);assertNotEquals(first,recovered.state.value.runId);assertNull(recovered.state.value.profile);assertNull(recovered.state.value.mission)
            assertFalse(StudyRepository.binding(isolated,oldSession)!!.getBoolean("captures"));assertTrue(recovered.state.value.captureConsent)
        }finally{directory.deleteRecursively()}
    }
}
