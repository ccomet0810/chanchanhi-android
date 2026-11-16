package dev.ccomet.chanchanhi04.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The detailed information remains in the scrollable body's link, outside fixed navigation. */
@Composable fun SetupAccessActions(next:String,onNext:()->Unit,onPrevious:(()->Unit)?=null)=JourneyActions(next,onNext,onPrevious=onPrevious)

/** The details link belongs to the final item of scrollable content, above fixed navigation. */
@Composable fun AccessInformationLink(onDetails:()->Unit){
    TextButton(onDetails,modifier=Modifier.fillMaxWidth().heightIn(min=Foundation.TouchDp.dp)){Text("화면 정보 처리 안내 보기")}
}

/** Read-only page, available offline and before accessibility or disclosure consent. */
@Composable fun AccessDetailsScreen(onBack:()->Unit){
    val context=LocalContext.current
    val policy=remember{context.assets.open("privacy-policy.txt").bufferedReader().use{it.readText()}}
    AppPage("화면 정보 처리 안내",onBack){
        LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(bottom=20.dp)){
            item{Text("찬찬히는 안내 중 다른 앱의 화면 글자, 버튼 정보와 위치, 현재 앱과 필요한 앱 목록을 접근성 기능으로 읽고 AI 연결 서버(ccomet.dev 및 OpenAI)에 보내 다음 동작을 안내해요. 다른 앱에 표시된 개인 내용이 포함될 수 있어요. 화면 이미지는 별도 동의하고 켠 경우에만 사용해요. 연구 참여에 동의한 기록은 운영자의 연구 서버에도 보관해요.")}
            item{Text("찬찬히가 대신 누르거나 입력하지 않으며 사용자가 직접 조작해요. 언제든 안내를 종료하거나 Android 설정에서 화면 안내를 끌 수 있어요.")}
            item{Text("개인정보처리방침",style=MaterialTheme.typography.titleLarge)}
            item{Text(policy)}
        }
    }
}
