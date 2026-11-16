package dev.ccomet.chanchanhi04

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aplassistmvp0.guide.*
import dev.ccomet.chanchanhi04.admin.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** App-private administrator surface. No credentials or records are loaded before authentication. */
class AdminActivity:ComponentActivity() {
    private enum class Page { HOME, LOGS, CONNECTION, RESEARCH }
    private lateinit var access:AdminAccess
    private var reviewAuthenticated=false
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var page by mutableStateOf(Page.HOME)
    private var gateVersion by mutableIntStateOf(0)
    private var notice by mutableStateOf("")
    // Opening the document picker locks this activity. This one-use grant authorizes only the selected export.
    private data class ExportPermit(val name:String,val expires:Long)
    private var exportPermit:ExportPermit?=null
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")){uri->
        val permit=exportPermit;exportPermit=null
        if(uri!=null && permit!=null && android.os.SystemClock.elapsedRealtime()<=permit.expires) {
            val file=AppRuntime.log.files().singleOrNull{it.name==permit.name}
            if(file!=null)work.launch {
                val result=withContext(Dispatchers.IO){runCatching {
                    AppRuntime.log.awaitWrites()
                    contentResolver.openOutputStream(uri)?.use {out->file.inputStream().use{it.copyTo(out)}} ?: error("export unavailable")
                }}
                notice=if(result.isSuccess)"전체 기록을 저장했어요." else "파일을 저장하지 못했어요."
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT));AppRuntime.initialize(this)
        access=AdminAccess(AdminAttemptPreferences(this),verifier={pin->
            val owner=AdminPinVerifier.matches(pin)
            val reviewer=!owner&&reviewPinAllowed()&&ReviewPinVerifier.matches(pin)
            reviewAuthenticated=reviewer
            owner||reviewer
        })
        setContent {ChanchanhiTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){AdminApp()}}}
    }
    override fun onStart(){super.onStart();AppRuntime.enterApp(this)}
    // onStop may be skipped when the user returns during the Home transition. Lock as soon as focus leaves.
    override fun onPause(){access.lock();gateVersion++;page=Page.HOME;super.onPause()}
    override fun onStop(){AppRuntime.leaveApp(this);super.onStop()}
    override fun onDestroy(){work.cancel();super.onDestroy()}
    private fun reviewPinAllowed():Boolean {
        val research=dev.ccomet.chanchanhi04.research.ResearchConnection(this)
        val participant=AppRuntime.study.state.value
        val unknownBridge=listOf("bridge-connection.json.bak","bridge-connection.json.new","bridge-pairing.txt").any{File(filesDir,it).exists()}
        val saved=unknownBridge||File(filesDir,"bridge-connection.json").exists()
        val marker=if(unknownBridge)null else getSharedPreferences(ReviewAdminPolicy.PREFERENCES,MODE_PRIVATE).getString(ReviewAdminPolicy.HASH_KEY,null)
        return ReviewAdminPolicy.allows(research.enrolled,research.label,saved,marker,
            participant.phase!=dev.ccomet.chanchanhi04.study.StudyPhase.INTRO||participant.profile!=null||participant.profileAnswers!=null,
            AppRuntime.log.files().isNotEmpty())
    }
    private fun enforceReviewScope(){
        if(reviewAuthenticated&&!reviewPinAllowed()){access.lock();gateVersion++;page=Page.HOME;notice="저장했어요. 관리자 비밀번호로 다시 열어주세요."}
    }
    @Composable private fun AdminApp() {
        val status by access.state.collectAsState()
        BackHandler {if(!status.unlocked || page==Page.HOME)finish()else page=Page.HOME}
        if(!status.unlocked){key(gateVersion){PinGate()};return}
        AppPage(when(page){Page.HOME->"관리자 모드";Page.LOGS->"안내 기록";Page.CONNECTION->"AI 연결 설정";Page.RESEARCH->"테스트 기록 연결"},
            onBack={if(page==Page.HOME){access.lock();finish()}else page=Page.HOME}){
            when(page){Page.HOME->AdminHome();Page.LOGS->Logs();Page.CONNECTION->Connection();Page.RESEARCH->Research()}
        }
    }
    @Composable private fun PinGate() {
        var pin by remember {mutableStateOf("")}
        var busy by remember {mutableStateOf(false)}
        var failed by remember {mutableStateOf(false)}
        var remaining by remember {mutableLongStateOf(access.remainingSeconds())}
        LaunchedEffect(Unit){while(true){remaining=access.remainingSeconds();delay(500)}}
        AppPage("관리자 모드",onBack={access.lock();finish()},actions={
            LargeAction(if(busy)"확인 중" else "관리자 모드 열기",Glyph.LOCK,{
                val entered=pin.toCharArray();pin="";busy=true
                work.launch {
                    val accepted=withContext(Dispatchers.Default){runCatching{access.verify(entered)}.getOrDefault(false)}
                    failed=!accepted;busy=false;remaining=access.remainingSeconds()
                }
            },enabled=pin.isNotBlank() && !busy && remaining==0L)
        }){
            BoxWithConstraints(Modifier.fillMaxSize()){
                Column(Modifier.fillMaxWidth().heightIn(min=maxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically)){
                    Text("기록과 연결을 관리해요.",style=MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(pin,{pin=it.take(128);failed=false},label={Text("비밀번호")},
                        modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy&&remaining==0L,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
                    if(remaining>0)Text("${remaining}초 후 다시 입력해주세요.",color=MaterialTheme.colorScheme.error)
                    else if(failed)Text("비밀번호를 확인해주세요.",color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    @Composable private fun AdminHome() {
        val participant by AppRuntime.study.state.collectAsState()
        var useImages by remember{mutableStateOf(AppRuntime.preferences.getBoolean("images",false))}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(reviewAuthenticated)item{Text("공개 시험 전용 기기예요. 실제 참여자의 개인정보를 입력하지 마세요.")}
            item {LargeAction("안내 기록",Glyph.LOG,{page=Page.LOGS})}
            item {LargeAction("AI 연결 설정",Glyph.LINK,{page=Page.CONNECTION},secondary=true)}
            item {LargeAction("테스트 기록 연결",Glyph.LOG,{page=Page.RESEARCH},secondary=true)}
            item {SettingsToggle("화면 이미지 사용",null,useImages,enabled=participant.captureConsent||participant.phase==dev.ccomet.chanchanhi04.study.StudyPhase.COMPLETE){value->if(access.unlocked){useImages=value;AppRuntime.preferences.edit().putBoolean("images",value).putString("images-consent-run",if(value)participant.runId else null).apply()}}}
            if(notice.isNotBlank())item {Text(notice)}
        }
    }
    @Composable private fun Research() {
        val connection=remember{dev.ccomet.chanchanhi04.research.ResearchConnection(this)}
        var endpoint by remember{mutableStateOf(connection.origin)}
        var code by remember{mutableStateOf("")}
        var result by remember{mutableStateOf(connection.status)}
        var busy by remember{mutableStateOf(false)}
        var enrolled by remember{mutableStateOf(connection.enrolled)}
        var sharing by remember{mutableStateOf(connection.enabled)}
        var images by remember{mutableStateOf(connection.imagesEnabled)}
        var consent by remember{mutableStateOf(false)}
        var imageConsent by remember{mutableStateOf(false)}
        var nextParticipant by remember{mutableStateOf(false)}
        val participant by AppRuntime.study.state.collectAsState()
        if(consent)dev.ccomet.chanchanhi04.research.ResearchConsent({if(access.unlocked){connection.setEnabled(true);sharing=true};consent=false},{consent=false})
        if(imageConsent)dev.ccomet.chanchanhi04.research.ResearchImageConsent({if(access.unlocked){connection.setImagesEnabled(true);images=true};imageConsent=false},{imageConsent=false})
        if(nextParticipant)AlertDialog(onDismissRequest={nextParticipant=false},title={Text("다음 참여자를 시작할까요?")},text={DialogBody("현재 기록은 보존하고 새 참여자의 온보딩을 시작해요.")},
            confirmButton={TextButton(onClick={nextParticipant=false;if(access.unlocked){AppRuntime.loop.stop();AppRuntime.study.nextParticipant();notice="새 참여자를 준비했어요."}}){Text("새 참여자 시작")}},dismissButton={TextButton(onClick={nextParticipant=false}){Text("취소")}})
        LaunchedEffect(Unit){while(true){result=connection.status;sharing=connection.enabled;images=connection.imagesEnabled;delay(3000)}}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item{Text("관리 사이트에서 기기별 참여코드를 만든 뒤 등록해주세요. AI 연결 설정은 바뀌지 않아요.")}
            if(!enrolled){
                item{OutlinedTextField(endpoint,{endpoint=it},label={Text("테스트 서버 주소")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)}
                item{OutlinedTextField(code,{code=it},label={Text("기기 참여코드")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)}
                item{LargeAction(if(busy)"등록 중" else "기기 등록",Glyph.LINK,{
                    if(access.unlocked){busy=true;val server=endpoint;val entered=code
                        work.launch{val value=withContext(Dispatchers.IO){runCatching{connection.enroll(server,entered)}}
                            if(access.unlocked){busy=false;value.onSuccess{code="";enrolled=true;result="등록했어요. 아래에서 테스트 기록 공유를 켜주세요.";enforceReviewScope()}
                                .onFailure{result="등록하지 못했어요. 서버 주소와 참여코드의 유효기간을 확인해주세요."}}
                        }
                    }
                },enabled=!busy&&code.isNotBlank())}
            }else{
                item{Text("등록 기기: ${connection.label}")}
                item{AdminSharing("테스트 기록 공유",sharing){value->if(access.unlocked){if(value)consent=true else{connection.setEnabled(false);sharing=false;images=false}}}}
                if(sharing&&android.os.Build.VERSION.SDK_INT>=30)item{AdminSharing("테스트 화면 캡처 공유",images){value->if(access.unlocked){if(value)imageConsent=true else{connection.setImagesEnabled(false);images=false}}}}
                item{Text("참여자가 온보딩에서 동의한 실험만 전송해요. 캡처는 참여자의 별도 동의도 필요해요.")}
                item{Text("현재 참여: ${(participant.profile?:participant.profileAnswers)?.optString("name")?:"정보 입력 전"} / ${participant.phase.title}")}
                if(participant.phase==dev.ccomet.chanchanhi04.study.StudyPhase.MISSION_PRE)item{
                    key(participant.runId){
                        var missionTitle by remember{mutableStateOf(participant.mission?.optString("title").orEmpty())}
                        var missionBody by remember{mutableStateOf(participant.mission?.optString("description").orEmpty())}
                        var missionGoal by remember{mutableStateOf(participant.mission?.optString("goal").orEmpty())}
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                            Text("이 참여자의 미션",style=MaterialTheme.typography.titleLarge)
                            OutlinedTextField(missionTitle,{missionTitle=it.take(300)},label={Text("참여자에게 보여줄 미션")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(missionBody,{missionBody=it.take(1000)},label={Text("추가 설명 (선택)")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(missionGoal,{missionGoal=it.take(2000)},label={Text("찬찬히에 전달할 요청 (선택)")},supportingText={Text("비우면 미션 내용을 그대로 전달해요.")},modifier=Modifier.fillMaxWidth())
                            LargeAction("미션 지정",Glyph.CHECK,{
                                if(access.unlocked){val a=missionTitle;val b=missionBody;val c=missionGoal;work.launch{val saved=withContext(Dispatchers.IO){runCatching{AppRuntime.study.assignMission(a,b,c)}};if(access.unlocked)result=if(saved.isSuccess)"미션을 지정했어요." else "미션 내용을 확인해주세요."}}
                            },enabled=missionTitle.isNotBlank())
                        }
                    }
                }
                item{LargeAction("다음 참여자 시작",Glyph.NEXT,{nextParticipant=true},secondary=true)}
                item{LargeAction("전송 다시 시도",Glyph.REPEAT,{connection.retry();result=if(connection.enabled)"전송을 예약했어요." else "테스트 기록 공유를 먼저 켜주세요."},secondary=true)}
            }
            item{Text(result,style=MaterialTheme.typography.bodyMedium)}
        }
    }
    @Composable private fun AdminSharing(title:String,checked:Boolean,change:(Boolean)->Unit){SettingsToggle(title,null,checked,onChecked=change)}
    @Composable private fun Connection() {
        val store=remember{BridgeConnectionStore(this)}
        val loaded=remember{runCatching{store.load()}}
        var current by remember{mutableStateOf(loaded.getOrNull())}
        var mode by remember{mutableStateOf(current?.mode ?: BridgeConnectionMode.HTTPS)}
        var endpoint by remember{mutableStateOf(current?.endpoint ?: BridgeConnectionPolicy.PUBLIC_ENDPOINT)}
        var token by remember{mutableStateOf("")}
        var certificate by remember{mutableStateOf(current?.certificateSha256.orEmpty())}
        var result by remember{mutableStateOf(if(loaded.isFailure)"저장된 연결을 읽지 못했어요. 다시 설정해주세요." else "")}
        var checking by remember{mutableStateOf(false)}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item{Text("Terra 6.1 low 구독 연결",style=MaterialTheme.typography.bodyMedium)}
            item{LargeAction(if(mode==BridgeConnectionMode.HTTPS)"인터넷 연결 사용 중" else "USB 연결 사용 중",Glyph.LINK,{
                mode=if(mode==BridgeConnectionMode.HTTPS)BridgeConnectionMode.USB else BridgeConnectionMode.HTTPS
                endpoint=if(mode==BridgeConnectionMode.USB)BridgeConnectionPolicy.USB_ENDPOINT else BridgeConnectionPolicy.PUBLIC_ENDPOINT
                certificate="";token=""
            },secondary=true)}
            item{OutlinedTextField(endpoint,{endpoint=it},label={Text("서버 주소")},modifier=Modifier.fillMaxWidth(),enabled=mode==BridgeConnectionMode.HTTPS)}
            item{OutlinedTextField(token,{token=it},label={Text("개인 연결 키")},supportingText={Text("비워두면 같은 서버의 기존 키를 유지해요.")},
                modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))}
            if(mode==BridgeConnectionMode.HTTPS)item{OutlinedTextField(certificate,{certificate=it},label={Text("인증서 SHA-256 핀 (선택)")},modifier=Modifier.fillMaxWidth())}
            item{LargeAction("연결 설정 저장",Glyph.CHECK,{
                if(access.unlocked)runCatching {
                    val next=current?.let{BridgeConnectionPolicy.fromInput(it,mode,endpoint,token,certificate)}
                        ?: BridgeConnectionPolicy.normalize(mode,endpoint,token,certificate)
                    // Clear the scope marker first, so a crash during a connection change fails closed.
                    val scope=getSharedPreferences(ReviewAdminPolicy.PREFERENCES,MODE_PRIVATE)
                    check(scope.edit().remove(ReviewAdminPolicy.HASH_KEY).commit())
                    store.save(next)
                    check(scope.edit().putString(ReviewAdminPolicy.HASH_KEY,ReviewAdminPolicy.hashBridgeToken(next.pairingToken)).commit())
                    current=next;token="";result="저장했어요.";enforceReviewScope()
                }.onFailure{result=it.message ?: "연결 설정을 확인해주세요."}
            })}
            item{LargeAction(if(checking)"연결 확인 중" else "저장된 연결 확인",Glyph.LINK,{
                if(access.unlocked) {checking=true;work.launch {
                    val value=runCatching{AppRuntime.model.check()}
                    if(access.unlocked){result=value.getOrElse{it.message ?: "연결하지 못했어요."};checking=false}
                }}
            },secondary=true,enabled=!checking)}
            if(result.isNotBlank())item{Text(result)}
        }
    }
    @Composable private fun Logs() {
        val files=remember{AppRuntime.log.files()}
        var selected by remember{mutableStateOf<File?>(null)}
        var rows by remember{mutableStateOf<List<String>>(emptyList())}
        var count by remember{mutableIntStateOf(0)}
        var error by remember{mutableStateOf("")}
        LaunchedEffect(selected) {
            val file=selected ?: return@LaunchedEffect
            val result=withContext(Dispatchers.IO){runCatching {
                AppRuntime.log.awaitWrites()
                val tail=ArrayDeque<String>();var total=0
                file.forEachLine {line->total++;tail.addLast(line);if(tail.size>300)tail.removeFirst()}
                total to tail.toList()
            }}
            if(access.unlocked){result.onSuccess{count=it.first;rows=it.second}.onFailure{error="기록을 읽지 못했어요."}}
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item{Text("안내 기록",style=MaterialTheme.typography.titleLarge)}
            val file=selected
            if(file==null) {
                if(files.isEmpty())item{Text("저장된 안내가 없어요.")}
                items(files,key={it.name}){entry->LargeAction(entry.name.removeSuffix(".jsonl"),Glyph.LOG,{selected=entry},secondary=true)}
            } else {
                item{Text(file.name,style=MaterialTheme.typography.bodyMedium)}
                item{LargeAction("전체 기록 파일 저장",Glyph.LOG,{
                    if(access.unlocked){exportPermit=ExportPermit(file.name,android.os.SystemClock.elapsedRealtime()+300000);export.launch(file.name)}
                })}
                item{Text("전체 ${count}개 중 최근 ${rows.size}개를 표시해요. 저장 파일에는 전체 기록이 들어가요.",style=MaterialTheme.typography.bodySmall)}
                item{TextButton(onClick={selected=null;rows=emptyList()}){Text("다른 안내 선택")}}
                items(rows.indices.toList()){index->
                    var expanded by remember(file.name,index){mutableStateOf(false)}
                    val json=remember(rows[index]){runCatching{JSONObject(rows[index])}.getOrNull()}
                    OutlinedCard(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("${json?.optLong("seq") ?: index+1}  ${json?.optString("kind") ?: "기록"}",style=MaterialTheme.typography.bodyMedium)
                            if(expanded)Text(json?.toString(2) ?: rows[index],fontSize=14.sp,lineHeight=21.sp)
                        }
                    }
                }
            }
            if(error.isNotEmpty())item{Text(error)}
        }
    }
}
