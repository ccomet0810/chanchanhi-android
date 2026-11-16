package dev.ccomet.chanchanhi04.qa;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Standalone public test APK: Java deliberately avoids the target APK's deduplicated Kotlin runtime. */
public class PublicFixtureActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean ticking=true;
    private EditText editor;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,70,24,24);
        TextView banner=new TextView(this);banner.setText("공개 시험 배너");banner.setTextSize(18f);
        editor=new EditText(this);editor.setHint("시험 입력란");editor.setTextSize(22f);editor.setSingleLine(false);
        TextView result=new TextView(this);result.setText("입력 대기");result.setTextSize(22f);
        Button button=new Button(this);button.setText("다음\n단계");button.setTextSize(22f);
        button.setOnClickListener(v->{editor.setText("");result.setText("시험 결과 화면");hideKeyboard();});
        root.addView(banner);root.addView(editor);root.addView(button);root.addView(result);
        if(getIntent().getBooleanExtra("replayFixture",false)){
            LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);
            group.setContentDescription("발신번호 표시제한 번호의 전화 차단, 사용 안 함");group.setClickable(true);
            TextView title=new TextView(this);title.setText("발신번호 표시제한 번호의 전화 차단");title.setTextSize(22f);
            TextView detail=new TextView(this);detail.setText("두 줄 이상인 글자도 자기 높이 안에서 표시하는 공개 시험입니다.");detail.setTextSize(18f);
            group.addView(title);group.addView(detail);root.addView(group);
        }
        ScrollView scroll=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        for(int index=1;index<=35;index++) {final int number=index;Button row=new Button(this);row.setText("공개 목록 "+number);row.setMinHeight(100);
            row.setOnClickListener(v->result.setText("항목 "+number+" 선택됨"));rows.addView(row);}
        scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);
        if(getIntent().getBooleanExtra("dynamic",true))handler.postDelayed(new Runnable(){int count=0;@Override public void run(){if(ticking){banner.setText("공개 시험 배너 "+(++count));handler.postDelayed(this,60);}}},60);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(intent.getBooleanExtra("showKeyboard",false))editor.postDelayed(()->{editor.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);},300);
    }
    private void hideKeyboard(){getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    @Override public void onDestroy(){ticking=false;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
