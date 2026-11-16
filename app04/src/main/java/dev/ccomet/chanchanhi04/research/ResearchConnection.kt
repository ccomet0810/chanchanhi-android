package dev.ccomet.chanchanhi04.research

import android.content.Context
import android.os.Build
import androidx.work.*
import dev.ccomet.chanchanhi04.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class ResearchConnection(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("research-1",Context.MODE_PRIVATE)
    val enrolled get()=!prefs.getString("token",null).isNullOrEmpty()
    val enabled get()=enrolled && prefs.getBoolean("enabled",false)
    val imagesEnabled get()=enabled && prefs.getBoolean("screenImages",false)
    private val frames get()=File(app.filesDir,"v04-research-frames")
    fun imageEligible(sessionId:String)=imagesEnabled && sessionId in prefs.getStringSet("imageEligible",emptySet()).orEmpty()
    fun setImagesEnabled(value:Boolean)=synchronized(imageLock) {
        check(!value||enabled)
        prefs.edit().putBoolean("screenImages",value).remove("imageEligible").commit()
        // Opting back in only applies to sessions begun afterwards. Never backfill private intervals.
        if(!value)frames.deleteRecursively()
    }
    val label get()=prefs.getString("label","").orEmpty()
    val status get()=prefs.getString("status","아직 기록을 보내지 않았어요.").orEmpty()
    val origin get()=prefs.getString("origin",DEFAULT_ORIGIN) ?: DEFAULT_ORIGIN
    @Synchronized private fun installationId():String=prefs.getString("installation",null) ?: UUID.randomUUID().toString().also{prefs.edit().putString("installation",it).commit()}
    fun metadata():JSONObject {
        val dm=app.resources.displayMetrics;val cfg=app.resources.configuration
        return JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("androidVersion",Build.VERSION.RELEASE)
            .put("sdk",Build.VERSION.SDK_INT).put("appVersion",BuildConfig.VERSION_NAME).put("appVersionCode",BuildConfig.VERSION_CODE)
            .put("width",dm.widthPixels).put("height",dm.heightPixels).put("density",dm.density).put("fontScale",cfg.fontScale)
            .put("locale",cfg.locales[0].toLanguageTag()).put("consentVersion","research-1")
    }
    internal fun prepareAutomatically(){
        if(enrolled)return
        val key=prefs.getString("automatic-installation-key",null)?:android.util.Base64.encodeToString(ByteArray(32).also{java.security.SecureRandom().nextBytes(it)},android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).also{check(prefs.edit().putString("automatic-installation-key",it).commit())}
        val response=request(DEFAULT_ORIGIN,"study-connect",JSONObject().put("installationKey",key).put("installationId",installationId()).put("metadata",metadata()).put("consentVersion","research-1"))
        check(response.optInt("version")==1&&response.optString("deviceId")==installationId()&&response.optString("token").matches(Regex("[A-Za-z0-9_-]{43}"))){"기기 연결을 다시 확인해주세요."}
        check(prefs.edit().putString("origin",DEFAULT_ORIGIN).putString("token",response.getString("token")).putString("label",response.getString("label")).putBoolean("enabled",true).putString("status","실험 연결을 준비했어요.").commit())
        // Uploaders still require a participant's consent journal and an explicitly linked session.
        retry()
    }
    /** Admin supplies a per-device enrollment code; it never changes the AI subscription connection. */
    fun enroll(server:String,code:String) {
        val normalized=server.trim().trimEnd('/')
        val url=URL(normalized)
        require(url.protocol=="https"&&url.host.isNotBlank()&&url.userInfo==null&&url.port in listOf(-1,443)&&url.path.isEmpty()&&url.query==null&&url.ref==null){"https 서버 주소를 확인해주세요."}
        require(code.trim().matches(Regex("[A-Za-z0-9_-]{8,80}"))){"참여코드를 확인해주세요."}
        val response=request(normalized,"enroll",JSONObject().put("code",code.trim()).put("installationId",installationId()).put("metadata",metadata()).put("consentVersion","research-1"))
        check(response.optString("token").matches(Regex("[A-Za-z0-9_-]{40,80}"))){"서버 연결을 확인해주세요."}
        prefs.edit().putString("origin",normalized).putString("token",response.getString("token")).putString("label",response.getString("label"))
            .putBoolean("enabled",false).putString("status","등록했어요. 참여자가 공유를 켜면 새 안내부터 전송해요.").commit()
    }
    fun setEnabled(value:Boolean) {
        check(!value||enrolled)
        val edit=prefs.edit().putBoolean("enabled",value).putString("status",if(value)"새 안내부터 기록을 공유해요." else "기록 공유를 멈췄어요.")
        if(!value){setImagesEnabled(false);edit.remove("eligible")} // Re-enabling never backfills the interval in which sharing was off.
        edit.commit()
        if(value){schedule();schedulePeriodic()}else WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
    }
    /** Called at begin, not while scanning files. Pre-existing private sessions cannot become eligible. */
    @Synchronized fun begin(sessionId:String) {
        if(!enabled)return
        val consent=dev.ccomet.chanchanhi04.study.StudyRepository.binding(app,sessionId)?:return
        if(!consent.optBoolean("records"))return
        val ids=prefs.getStringSet("eligible",emptySet()).orEmpty().toMutableSet();ids.add(sessionId)
        prefs.edit().putStringSet("eligible",ids).commit()
        synchronized(imageLock){if(imagesEnabled&&consent.optBoolean("captures")){val imageIds=prefs.getStringSet("imageEligible",emptySet()).orEmpty().toMutableSet();imageIds.add(sessionId);prefs.edit().putStringSet("imageEligible",imageIds).commit()}}
    }
    /** Called off-main-thread. At most 120 pending images/session and 40 MiB total; no eviction of logs. */
    fun queueFrame(sessionId:String,seq:Long,metadata:JSONObject,bytes:ByteArray):Boolean=synchronized(imageLock){
        if(!imageEligible(sessionId))return false
        require(sessionId.matches(Regex("[A-Za-z0-9-]{8,80}"))&&seq>0&&bytes.size in 1..122880)
        val directory=File(frames,sessionId)
        if((directory.listFiles()?.count{it.extension=="jpg"}?:0)>=120 || (frames.walkTopDown().filter{it.isFile}.sumOf{it.length()}+bytes.size)>40*1048576L)return false
        directory.mkdirs()
        val image=File(directory,"$seq.jpg");val manifest=File(directory,"$seq.json")
        try {
            image.writeBytes(bytes)
            val data=JSONObject().put("sessionId",sessionId).put("eventSeq",seq).put("metadata",metadata)
            val temporary=File(directory,"$seq.tmp");temporary.writeText(data.toString(),Charsets.UTF_8)
            check(temporary.renameTo(manifest));true
        }catch(e:Exception){image.delete();manifest.delete();throw e}
    }
    private fun uploadFrames(sessionId:String){
        val directory=File(frames,sessionId)
        for(manifest in directory.listFiles().orEmpty().filter{it.extension=="json"}.sortedBy{it.nameWithoutExtension.toLongOrNull()?:Long.MAX_VALUE}){
            if(!imageEligible(sessionId))return
            val value=JSONObject(manifest.readText(Charsets.UTF_8));val seq=value.getLong("eventSeq")
            if(seq>prefs.getLong("ack-$sessionId",0))continue
            val image=File(directory,"$seq.jpg");if(!image.exists())continue
            val bytes=image.readBytes()
            check(frameHash(bytes)==value.getJSONObject("metadata").getString("sha256"))
            if(!imageEligible(sessionId))return
            val body=JSONObject().put("sessionId",sessionId).put("eventSeq",seq).put("jpegBase64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
            request(origin,"frame",body,prefs.getString("token",null))
            image.delete();manifest.delete()
        }
        directory.delete() // Only removes an empty directory.
    }
    fun stored(){if(enabled)schedule()}
    private fun schedule(){
        val request=OneTimeWorkRequestBuilder<ResearchUploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(app).enqueueUniqueWork(TAG,ExistingWorkPolicy.KEEP,request)
    }
    private fun schedulePeriodic(){WorkManager.getInstance(app).enqueueUniquePeriodicWork("$TAG-periodic",ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ResearchUploadWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag(TAG).build())}
    fun retry(){if(enabled){schedule();schedulePeriodic()}}
    /** Pure file queue; intentionally does not initialize the agent, overlay, or speech engine. */
    fun upload():Boolean=synchronized(uploadLock){uploadLocked()}
    private fun uploadStudies():Boolean {
        var batches=0
        for(file in dev.ccomet.chanchanhi04.study.StudyRepository.directory(app).listFiles().orEmpty().filter{it.extension=="jsonl"}.sortedBy{it.name}){
            val run=file.nameWithoutExtension
            if(!run.matches(Regex("[A-Za-z0-9-]{8,80}")))continue
            while(enabled){
                val ack=prefs.getLong("study-ack-$run",0)
                val events=dev.ccomet.chanchanhi04.study.StudyRepository.read(file).filter{it.getLong("seq")>ack}.take(40)
                if(events.isEmpty())break
                if(!enabled)return true
                val response=request(origin,"study/ingest",JSONObject().put("runId",run).put("metadata",metadata()).put("events",JSONArray(events)),prefs.getString("token",null))
                val accepted=response.getJSONArray("accepted");val expected=events.map{it.getLong("seq")}.toSet()
                check((0 until accepted.length()).map{accepted.getLong(it)}.toSet()==expected)
                prefs.edit().putLong("study-ack-$run",expected.max()).putString("status","설문과 실험 기록을 전송했어요.").commit()
                if(++batches>=40)return false
            }
        };return true
    }
    private fun uploadLocked():Boolean {
        if(!enabled)return true
        val ids=prefs.getStringSet("eligible",emptySet()).orEmpty().toList()
        var batches=0
        try {
            // Consent/profile/mission links must arrive before the corresponding full guidance trace.
            if(!uploadStudies())return false
            for(id in ids){
                if(!id.matches(Regex("[A-Za-z0-9-]{8,80}")))continue
                val file=File(app.filesDir,"v04-sessions/$id.jsonl");if(!file.exists())continue
                val sessionMetadata=file.bufferedReader().use{it.readLine()}?.let{runCatching{JSONObject(it).optJSONObject("data")?.optJSONObject("device")}.getOrNull()} ?: metadata()
                while(enabled){
                    val ack=prefs.getLong("ack-$id",0)
                    val events=readBatch(file,ack)
                    if(events.isEmpty())break
                    // Consent can change while a file is being read. Recheck before starting network I/O.
                    if(!enabled)return true
                    val result=request(origin,"ingest",JSONObject().put("sessionId",id).put("metadata",metadata()).put("sessionMetadata",sessionMetadata).put("events",JSONArray(events)),prefs.getString("token",null))
                    val accepted=result.getJSONArray("accepted");val expected=events.map{it.getLong("seq")}.toSet()
                    check((0 until accepted.length()).map{accepted.getLong(it)}.toSet()==expected){"수신 확인이 맞지 않아요."}
                    prefs.edit().putLong("ack-$id",expected.max()).putString("status","최근 수신 확인: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}").commit()
                    if(++batches>=40)return false
                }
                uploadFrames(id)
            }
            return true
        }catch(e:UploadFailure){
            if(e.code in setOf(401,403)){prefs.edit().putBoolean("enabled",false).putString("status","서버 연결이 해제됐어요. 관리자에게 알려주세요.").commit();return true}
            prefs.edit().putString("status",if(e.code==409)"기록 순번 충돌이 있어요. 원본을 보존했어요. 관리자에게 알려주세요." else "서버에 연결하지 못했어요. 기록을 보관하고 다시 시도해요.").apply()
            return false
        }catch(_:Exception){prefs.edit().putString("status","기록을 보관하고 다시 전송을 시도해요.").apply();return false}
    }
    companion object {
        private val uploadLock=Any()
        private val imageLock=Any()
        fun frameHash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        const val DEFAULT_ORIGIN="https://ccomet.dev"
        private const val TAG="chanchanhi-research-upload"
        /** Reads complete JSONL rows only. A writer may be appending the final line concurrently. */
        internal fun readBatch(file:File,after:Long):List<JSONObject> {
            val rows=mutableListOf<JSONObject>();var bytes=0
            file.bufferedReader().use {input->while(rows.size<40){val line=input.readLine()?:break
                val original=runCatching{JSONObject(line)}.getOrNull() ?: break
                if(original.getLong("seq")<=after)continue
                val row=ResearchPrivacy.event(original);val size=row.toString().toByteArray(Charsets.UTF_8).size
                if(rows.isNotEmpty()&&bytes+size>2000000)break
                check(size<=1048576){"event_too_large"};rows.add(row);bytes+=size
            }};return rows
        }
        private fun request(origin:String,path:String,body:JSONObject,token:String?=null):JSONObject {
            val connection=URL("$origin/chanchanhi-admin/api/$path").openConnection() as HttpURLConnection
            try{
                connection.requestMethod="POST";connection.connectTimeout=12000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("User-Agent","Chanchanhi-Research/${BuildConfig.VERSION_NAME}");token?.let{connection.setRequestProperty("Authorization","Bearer $it")}
                connection.doOutput=true;val bytes=body.toString().toByteArray(Charsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use{it.write(bytes)}
                val code=connection.responseCode;if(code !in 200..299)throw UploadFailure(code)
                val response=connection.inputStream.bufferedReader().use{it.readText()};check(response.length<100000){"response_too_large"};return JSONObject(response)
            }finally{connection.disconnect()}
        }
    }
    private class UploadFailure(val code:Int):Exception()
}
class ResearchUploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){if(ResearchConnection(applicationContext).upload())Result.success()else Result.retry()}
}
