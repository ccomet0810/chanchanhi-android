package dev.ccomet.chanchanhi04.research

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ResearchConsent(onAccept:()->Unit,onDismiss:()->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text("테스트 기록을 공유할까요?")},
        text={Text("공유를 켠 뒤 시작한 안내의 요청, 화면 글자와 접근성 트리, 안내 중 조회한 앱 목록, AI 판단 요약, 행동 순서, 안내창·키보드 위치, 기종과 앱 버전을 테스트 관리자 서버에 보내요.\n\n입력창의 값·전화번호·이메일·인증정보는 가려요. 다른 화면 글자나 안내 문장에는 개인 내용이 남을 수 있어요. 음성 원음은 보내지 않아요. 화면 캡처는 별도 공유 설정을 켠 경우에만 보내요.\n\n언제든 설정에서 끌 수 있어요. 이미 전송한 기록은 서버에 남으며 삭제는 관리자에게 요청해주세요.",modifier=Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick=onAccept){Text("공유 켜기")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}

@Composable fun ResearchImageConsent(onAccept:()->Unit,onDismiss:()->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text("화면 캡처도 공유할까요?")},
        text={Text("다음 안내부터 실제 화면과 안내창을 테스트 관리자에게 보내요. 화면에 보이는 대화, 이름, 사진, 입력 내용도 이미지에 그대로 포함될 수 있어요. 이미지의 개인 내용은 자동으로 가려지지 않아요.\n\n기록을 공유하는 동안에만 작동해요. 끄면 아직 보내지 않은 이미지를 지워요. 이미 전송한 이미지의 삭제는 관리자에게 요청해주세요.",modifier=Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick=onAccept){Text("캡처 공유 켜기")}},dismissButton={TextButton(onClick=onDismiss){Text("취소")}})
}
