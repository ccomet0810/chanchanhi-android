package dev.ccomet.chanchanhi04.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.ui.*

/** One full explanation used by onboarding and the participant's settings. */
@Composable fun StudyConsentDetails(onBack:()->Unit){
    AppPage("수집 내용",onBack){LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=12.dp)){
        item{Text("전송하는 내용",style=MaterialTheme.typography.titleMedium)}
        item{Text("이름, 나이, 성별, 직업, 설문 답변과 미션 수행 기록을 테스트 관리자에게 보내요. 수행 기록에는 요청, 화면 글자, 접근성 정보, AI 응답과 행동 순서, 기종이 포함돼요. 입력 값 일부는 가리지만 화면 글자에 개인 내용이 남을 수 있어요.")}
        item{Text("이미지 기록",style=MaterialTheme.typography.titleMedium)}
        item{Text("별도로 동의한 경우에만 화면 이미지를 AI의 화면 확인에 사용할 수 있어요. 관리자가 캡처 공유도 켠 경우 연구 서버에 보관해요. 대화, 이름, 사진, 입력 내용이 그대로 담길 수 있으며 이미지의 개인 내용은 자동으로 가려지지 않아요.")}
        item{Text("참여와 보관",style=MaterialTheme.typography.titleMedium)}
        item{Text("동의한 뒤 시작하며, 언제든 참여를 중단할 수 있어요. 이미 보낸 기록은 관리자에게 삭제를 요청할 수 있어요. 자료는 동의한 연구의 분석과 검증에 필요한 동안 보관하며, 목적 달성 또는 유효한 삭제 요청 시 파기해요.")}
        item{Text("운영자: ccomet0810\nccomet0810@gmail.com")}
        item{PrivacyPolicyLink("개인정보처리방침")}
    }}
}
