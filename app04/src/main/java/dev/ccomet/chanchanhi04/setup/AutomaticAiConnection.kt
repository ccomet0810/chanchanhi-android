package dev.ccomet.chanchanhi04.setup

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.example.aplassistmvp0.guide.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/** Only the public origin is in the APK. Each installation obtains an independent AI-only token. */
internal object AutomaticAiConnection {
    private val mutex=Mutex()
    suspend fun ensure(context:Context):BridgeConnectionConfig=withContext(Dispatchers.IO){mutex.withLock{
        val app=context.applicationContext
        val store=BridgeConnectionStore(app)
        val current=store.load()
        if(current.pairingToken.isNotEmpty())return@withLock current
        check(current.mode==BridgeConnectionMode.HTTPS&&current.endpoint==BridgeConnectionPolicy.PUBLIC_ENDPOINT){"AI 연결을 확인해주세요."}
        val seedFile=AtomicFile(java.io.File(app.filesDir,"app-access-seed"))
        val seed=if(seedFile.baseFile.exists())seedFile.openRead().use{it.readBytes().toString(Charsets.US_ASCII)} else {
            val value=Base64.encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)},Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val out=seedFile.startWrite()
            try{out.write(value.toByteArray(Charsets.US_ASCII));seedFile.finishWrite(out)}catch(error:Exception){seedFile.failWrite(out);throw error}
            value
        }
        check(seed.matches(Regex("[A-Za-z0-9_-]{43}"))){"기기 연결 정보를 확인하지 못했어요."}
        val connection=URL(BridgeConnectionPolicy.PUBLIC_ENDPOINT+"/chanchanhi-admin/api/app-connect").openConnection() as HttpsURLConnection
        try{
            connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=20000;connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("User-Agent","Chanchanhi/${dev.ccomet.chanchanhi04.BuildConfig.VERSION_NAME} (Android)")
            connection.outputStream.use{it.write(JSONObject().put("protocol","chanchanhi/0.4").put("installationKey",seed).toString().toByteArray(Charsets.UTF_8))}
            check(connection.responseCode==200){"AI 연결을 준비하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해주세요."}
            val bytes=connection.inputStream.use{input->
                val buffer=ByteArray(4097);var length=0
                while(length<buffer.size){val n=input.read(buffer,length,buffer.size-length);if(n<0)break;length+=n}
                buffer.copyOf(length)
            }
            check(bytes.size<=4096){"AI 연결 응답을 확인하지 못했어요."}
            val response=JSONObject(bytes.toString(Charsets.UTF_8));check(response.optInt("version")==1)
            val ready=BridgeConnectionPolicy.normalize(BridgeConnectionMode.HTTPS,BridgeConnectionPolicy.PUBLIC_ENDPOINT,response.getString("pairingToken"),null)
            val latest=store.load();if(latest!=current)return@withLock latest
            store.save(ready);ready
        }finally{connection.disconnect()}
    }}
}
