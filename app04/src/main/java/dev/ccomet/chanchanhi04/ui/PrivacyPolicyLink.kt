package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Available before enrollment, permission grants and research consent, including offline. */
@Composable fun PrivacyPolicyLink(label:String="수집 내용 자세히 보기") {
    val context=LocalContext.current
    var visible by remember { mutableStateOf(false) }
    val policy=remember { context.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() } }
    TextButton(onClick={visible=true},modifier=Modifier.heightIn(min=Foundation.TouchDp.dp)){Text(label)}
    if(visible)AlertDialog(onDismissRequest={visible=false},title={Text("개인정보처리방침")},
        text={Text(policy,modifier=Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={visible=false}){Text("닫기")}})
}
