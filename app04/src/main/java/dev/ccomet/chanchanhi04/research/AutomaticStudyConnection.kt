package dev.ccomet.chanchanhi04.research

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Registration only; this never accepts consent or fills a participant answer. */
object AutomaticStudyConnection{
    private val mutex=Mutex()
    private val mutable=MutableStateFlow("")
    val status=mutable.asStateFlow()
    suspend fun ensure(context:Context)=withContext(Dispatchers.IO){mutex.withLock{
        if(!dev.ccomet.chanchanhi04.BuildConfig.RESEARCH_BUILD)return@withLock
        val connection=ResearchConnection(context)
        if(connection.enrolled){mutable.value="실험 연결을 준비했어요.";return@withLock}
        mutable.value="실험 연결을 준비하고 있어요."
        runCatching{connection.prepareAutomatically()}.onSuccess{mutable.value="실험 연결을 준비했어요."}
            .onFailure{mutable.value="실험 서버에 연결하지 못했어요. 인터넷 연결을 확인해주세요."}
    }}
}
