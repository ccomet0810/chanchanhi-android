package com.example.aplassistmvp0.v3;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ScrollView;

/** Test APK only. No alarms, messages, payments, network or private data. */
public class V3FormFixtureActivity extends Activity {
    private TextView transitionStatus;
    private TextView deferredCommitResult;
    private TextView scopedCardTitle;
    private Button scopedCardButton;
    private Button scopedCardPeerButton;
    private LinearLayout scopedCardList;
    private LinearLayout scopedCardRoot;
    private TextView scopedArrivalTitle;
    private TextView scopedArrivalDetail;
    private LinearLayout scopedArrivalPane;
    private Button scopedReturnButton;
    private TextView scopedSearchTitle;
    private Button scopedSearchPeer;
    private TextView scopedFirstResultDetail;
    private int scopedAccessibilityOffset;
    @Override protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);
        if(transitionStatus!=null && intent.getBooleanExtra("finishTransition",false))transitionStatus.setText("검색 결과 없음");
        if (transitionStatus != null && intent.hasExtra("reportMissingChild")) {
            transitionStatus.setAccessibilityDelegate(intent.getBooleanExtra("reportMissingChild", false)
                ? missingChildDelegate() : null);
            transitionStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        if(transitionStatus!=null && intent.getBooleanExtra("statusAtBottom",false)){
            LinearLayout parent=(LinearLayout)transitionStatus.getParent();
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)transitionStatus.getLayoutParams();
            params.topMargin=Math.max(0,parent.getHeight()-parent.getPaddingTop()-parent.getPaddingBottom()
                -parent.getChildAt(0).getHeight()-transitionStatus.getHeight()-48);
            transitionStatus.setLayoutParams(params);
        }
        if(deferredCommitResult!=null && intent.getBooleanExtra("firstResultReady",false))deferredCommitResult.setText("첫 항목 생성됨");
        if(deferredCommitResult!=null && intent.getBooleanExtra("secondResultReady",false))deferredCommitResult.setText("이번에 새 항목 생성됨");
        if(scopedCardTitle!=null && intent.getBooleanExtra("renameScopedCard",false))scopedCardTitle.setText("새 자료");
        if(scopedCardButton!=null && intent.getBooleanExtra("enableScopedCard",false))scopedCardButton.setEnabled(true);
        if (scopedCardButton != null && intent.getBooleanExtra("changeScopedPreparationNotice", false)
            && android.os.Build.VERSION.SDK_INT >= 30) {
            scopedCardButton.setStateDescription("검토가 끝날 때까지 기다려주세요.");
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (scopedCardButton != null && intent.getBooleanExtra("changeScopedRepairNotice", false))
                scopedCardButton.setStateDescription("현재 자료를 열 수 있어요.");
            if (scopedCardPeerButton != null && intent.getBooleanExtra("changePeerRepairNotice", false))
                scopedCardPeerButton.setStateDescription("사진을 불러왔어요.");
        }
        if (scopedCardTitle != null && scopedCardList != null && intent.getBooleanExtra("moveScopedTitleOutsideCard", false)) {
            ((LinearLayout) scopedCardTitle.getParent()).removeView(scopedCardTitle);
            scopedCardList.addView(scopedCardTitle, 0, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        if (scopedCardRoot != null && intent.getBooleanExtra("revealScopedTitle", false)) {
            scopedAccessibilityOffset = 0;
            scopedCardRoot.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        if (scopedArrivalTitle != null && intent.hasExtra("scopedArrivalHeading")) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                if (scopedArrivalPane != null)
                    scopedArrivalPane.setAccessibilityPaneTitle(intent.getStringExtra("scopedArrivalPaneTitle"));
                scopedArrivalTitle.setAccessibilityHeading(intent.getBooleanExtra("scopedArrivalHasHeading", true));
            }
            scopedArrivalTitle.setText(intent.getStringExtra("scopedArrivalHeading"));
            if (scopedArrivalPane != null && intent.hasExtra("scopedArrivalDetail")) {
                if (scopedArrivalDetail == null) {
                    scopedArrivalDetail = new TextView(this);
                    scopedArrivalPane.addView(scopedArrivalDetail, new LinearLayout.LayoutParams(-1, dp(48)));
                }
                scopedArrivalDetail.setText(intent.getStringExtra("scopedArrivalDetail"));
            }
            if (scopedArrivalPane != null && scopedReturnButton == null && getIntent().getBooleanExtra("scopedReturnArrival", false)
                    && !intent.getBooleanExtra("hideScopedReturn", false)) {
                scopedReturnButton = new Button(this);
                scopedReturnButton.setText("자료 목록으로 돌아가기");
                scopedReturnButton.setOnClickListener(view -> {
                    if (!getIntent().getBooleanExtra("scopedReturnDoesNotNavigate", false)) {
                        scopedCardRoot.postDelayed(this::restoreScopedCardList, 200);
                    }
                });
                scopedArrivalPane.addView(scopedReturnButton, new LinearLayout.LayoutParams(-1, dp(64)));
            }
        }
        if (scopedArrivalPane != null && intent.getBooleanExtra("showScopedArrivalEditor", false)) {
            EditText editor = new EditText(this);
            editor.setHint("공개 작성 시험");
            editor.setText("공개 초안");
            scopedCardRoot.addView(editor, new LinearLayout.LayoutParams(-1, dp(64)));
        }
        if (scopedArrivalPane != null && intent.getBooleanExtra("restoreScopedCardList", false)) restoreScopedCardList();
        if (scopedCardRoot != null && intent.hasExtra("scopedSearchResult")) showScopedSearchResult(intent.getStringExtra("scopedSearchResult"));
        if (scopedCardRoot != null && intent.hasExtra("scopedSearchPeer")) showScopedNamedRows(intent.getStringExtra("scopedSearchPeer"));
        if (scopedCardRoot != null && intent.hasExtra("scopedSearchFirstDetail")) showScopedQualifiedRows(intent.getStringExtra("scopedSearchFirstDetail"));
    }

    private void showScopedSearchResult(String title) {
        if (scopedSearchTitle == null) {
            scopedCardRoot.removeAllViews();
            LinearLayout results = accessibleGroup();
            if (android.os.Build.VERSION.SDK_INT >= 28) results.setAccessibilityPaneTitle("검색 결과");
            scopedCardRoot.addView(results, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout card = accessibleGroup();
            results.addView(card, new LinearLayout.LayoutParams(-1, -2));
            scopedSearchTitle = new TextView(this);
            card.addView(scopedSearchTitle, new LinearLayout.LayoutParams(-1, dp(48)));
            Button open = new Button(this);
            open.setText("열기");
            open.setOnClickListener(view -> queueScopedArrival());
            card.addView(open, new LinearLayout.LayoutParams(-1, dp(64)));
        }
        scopedSearchTitle.setText(title);
    }

    private void showScopedNamedRows(String peerTitle) {
        if (scopedSearchPeer == null) {
            scopedCardRoot.removeAllViews();
            LinearLayout results = accessibleGroup();
            if (android.os.Build.VERSION.SDK_INT >= 28) results.setAccessibilityPaneTitle("검색 결과");
            scopedCardRoot.addView(results, new LinearLayout.LayoutParams(-1, -2));
            Button named = new Button(this);
            named.setText("회의 자료");
            named.setOnClickListener(view -> queueScopedArrival());
            results.addView(named, new LinearLayout.LayoutParams(-1, dp(76)));
            scopedSearchPeer = new Button(this);
            scopedSearchPeer.setOnClickListener(view -> queueScopedArrival());
            results.addView(scopedSearchPeer, new LinearLayout.LayoutParams(-1, dp(76)));
        }
        scopedSearchPeer.setText(peerTitle);
    }

    private void showScopedQualifiedRows(String firstDetail) {
        if (scopedFirstResultDetail == null) {
            scopedCardRoot.removeAllViews();
            LinearLayout results = accessibleGroup();
            if (android.os.Build.VERSION.SDK_INT >= 28) results.setAccessibilityPaneTitle("검색 결과");
            scopedCardRoot.addView(results, new LinearLayout.LayoutParams(-1, -2));
            scopedFirstResultDetail = addScopedQualifiedRow(results, firstDetail);
            addScopedQualifiedRow(results, "2026년");
        }
        scopedFirstResultDetail.setText(firstDetail);
    }

    private TextView addScopedQualifiedRow(LinearLayout results, String detailText) {
        LinearLayout row = accessibleGroup();
        row.setContentDescription("회의 자료");
        row.setOnClickListener(view -> queueScopedArrival());
        results.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText("회의 자료");
        row.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView detail = new TextView(this);
        detail.setText(detailText);
        row.addView(detail, new LinearLayout.LayoutParams(-1, dp(48)));
        return detail;
    }

    private void restoreScopedCardList() {
        scopedCardRoot.removeAllViews();
        scopedCardRoot.addView(scopedCardList, new LinearLayout.LayoutParams(-1, -2));
    }

    private void queueScopedArrival() {
        scopedCardRoot.postDelayed(() -> {
            scopedCardRoot.removeAllViews();
            scopedReturnButton = null;
            scopedArrivalDetail = null;
            scopedArrivalTitle = new TextView(this);
            scopedArrivalTitle.setText("자료를 열고 있어요");
            if (getIntent().getBooleanExtra("scopedPaneArrival", false)) {
                scopedArrivalPane = accessibleGroup();
                scopedCardRoot.addView(scopedArrivalPane, new LinearLayout.LayoutParams(-1, -2));
                scopedArrivalPane.addView(scopedArrivalTitle, new LinearLayout.LayoutParams(-1, dp(64)));
            } else {
                scopedCardRoot.addView(scopedArrivalTitle, new LinearLayout.LayoutParams(-1, dp(64)));
            }
        }, 250);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,64,24,24);
        // This Activity runs in the standalone test APK process; use platform APIs only.
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(safe.left+24,safe.top+24,safe.right+24,safe.bottom+24);
            }else{
                view.setPadding(insets.getSystemWindowInsetLeft()+24,insets.getSystemWindowInsetTop()+24,
                    insets.getSystemWindowInsetRight()+24,insets.getSystemWindowInsetBottom()+24);
            }
            return insets;
        });
        TextView title=new TextView(this);title.setText("공개 양식 시험");title.setTextSize(24);
        root.addView(title);
        if (getIntent().getBooleanExtra("scopedCardProbe", false)) {
            scopedCardRoot = root;
            boolean nested = getIntent().getBooleanExtra("nestedScopedCards", false);
            scopedCardList = nested ? accessibleGroup() : root;
            if (getIntent().getBooleanExtra("scopedReturnArrival", false) && android.os.Build.VERSION.SDK_INT >= 28) {
                scopedCardList.setAccessibilityPaneTitle("자료 목록");
            }
            if (getIntent().getBooleanExtra("scopedSystemBack", false) && android.os.Build.VERSION.SDK_INT >= 33) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::restoreScopedCardList);
            }
            if (nested) root.addView(scopedCardList, new LinearLayout.LayoutParams(-1, -2));
            scopedCardTitle = addScopedCard(scopedCardList, "회의 자료", nested, true);
            addScopedCard(scopedCardList, "여행 사진", nested, false);
            if (getIntent().getBooleanExtra("repeatScopedWrongRoute", false)) {
                Button search = new Button(this);
                search.setText("검색");
                if (getIntent().getBooleanExtra("scopedSearchContinuation", false)) search.setOnClickListener(view ->
                    scopedCardRoot.postDelayed(() -> {
                        scopedCardRoot.removeAllViews();
                        scopedSearchTitle = null;
                        scopedSearchPeer = null;
                        scopedFirstResultDetail = null;
                        TextView loading = new TextView(this);
                        loading.setText("검색 중");
                        scopedCardRoot.addView(loading, new LinearLayout.LayoutParams(-1, dp(64)));
                    }, 250));
                scopedCardList.addView(search, new LinearLayout.LayoutParams(-1, dp(64)));
            }
            if (getIntent().getBooleanExtra("startClippedScope", false)) {
                scopedAccessibilityOffset = dp(24);
                publishOffsetBounds(root);
            }
            setContentView(root);
            root.requestApplyInsets();
            return;
        }
        if (getIntent().getBooleanExtra("ocrDuplicateProbe", false)) {
            addOcrCard(root, "열기", 0xffcbeada).setAccessibilityDelegate(null);
            addOcrCard(root, "열기", 0xffc6e3f4).setAccessibilityDelegate(null);
            addOcrIcon(root, true);
            addOcrIcon(root, false);
            setContentView(root);
            root.requestApplyInsets();
            return;
        }
        if (getIntent().getBooleanExtra("ocrRegionProbe", false)) {
            ScrollView scroll = new ScrollView(this);
            scroll.setContentDescription("공개 조작 영역 목록");
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addOcrCard(rows, "타이머", 0xffcbeada);
            rows.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(240)));
            addOcrCard(rows, "스톱워치", 0xffc6e3f4);
            TextView flatText = ocrRow("도움말", null);
            flatText.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            rows.addView(flatText, new LinearLayout.LayoutParams(-1, dp(48)));
            scroll.addView(rows);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(240)));
            setContentView(root);
            root.requestApplyInsets();
            return;
        }
        if (getIntent().getBooleanExtra("ocrScrollProbe", false)) {
            title.setAccessibilityDelegate(missingChildDelegate());
            ScrollView scroll = new ScrollView(this);
            scroll.setContentDescription("공개 세로 목록");
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.addView(ocrRow("타이머", "공개 첫 행"), new LinearLayout.LayoutParams(-1, dp(64)));
            rows.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(190)));
            rows.addView(ocrRow("스톱워치", "공개 다음 행"), new LinearLayout.LayoutParams(-1, dp(76)));
            scroll.addView(rows);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(240)));
            setContentView(root);
            root.requestApplyInsets();
            return;
        }
        if(getIntent().getBooleanExtra("transitionProbe",false)){
            transitionStatus=new TextView(this);transitionStatus.setId(android.R.id.text1);transitionStatus.setText("검색 중");transitionStatus.setTextSize(24);
            root.addView(transitionStatus,new LinearLayout.LayoutParams(-1,120));
            setContentView(root);root.requestApplyInsets();return;
        }
        if(getIntent().getBooleanExtra("inputMode",false)){
            EditText editor=new EditText(this);editor.setId(android.R.id.edit);editor.setHint("공개 입력");
            editor.setText("synthetic first draft");root.addView(editor,new LinearLayout.LayoutParams(-1,120));
            if(getIntent().getBooleanExtra("imeProbe",false)){
                editor.setSingleLine(true);
                editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                TextView entered=new TextView(this);entered.setText("입력 동작 대기");root.addView(entered);
                editor.setOnEditorActionListener((view,action,event)->{
                    entered.setText("입력 동작 관찰됨");return true;
                });
            }
        }
        if(getIntent().getBooleanExtra("preparationMode",false)){
            CheckBox first=new CheckBox(this);first.setText("준비 A");first.setChecked(true);root.addView(first);
            CheckBox second=new CheckBox(this);second.setText("준비 B");root.addView(second);
        }
        Button commit=new Button(this);commit.setText("확정");commit.setMinHeight(64);
        root.addView(commit,new LinearLayout.LayoutParams(-1,120));
        Button update=new Button(this);update.setText("결과 갱신");
        root.addView(update,new LinearLayout.LayoutParams(-1,120));
        TextView result=new TextView(this);result.setTextSize(24);result.setText("이전에 만든 항목");
        root.addView(result,new LinearLayout.LayoutParams(-1,120));
        final boolean twoOperations=getIntent().getBooleanExtra("twoOperations",false);
        final boolean deferredFirst=twoOperations && getIntent().getBooleanExtra("deferFirstResult",false);
        if(deferredFirst)deferredCommitResult=result;
        commit.setOnClickListener(v->result.setText(twoOperations && !deferredFirst ? "첫 항목 생성됨" : "처리 중"));
        update.setOnClickListener(v->result.setText(deferredFirst ? "둘째 항목 처리 중" : "이번에 새 항목 생성됨"));
        if(getIntent().getBooleanExtra("inputRecheckProbe",false)){
            Button item=new Button(this);item.setId(android.R.id.button3);item.setVisibility(android.view.View.GONE);
            root.addView(item,new LinearLayout.LayoutParams(-1,120));
            final int[] resultGeneration={0};
            update.setOnClickListener(v->{
                resultGeneration[0]++;item.setText("공개 결과 항목 "+resultGeneration[0]);item.setVisibility(android.view.View.VISIBLE);
                result.setText("공개 결과가 갱신됨");
            });
            item.setOnClickListener(v->result.setText("공개 결과 선택됨"));
        }
        setContentView(root);
        root.requestApplyInsets();
    }

    private LinearLayout accessibleGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return group;
    }

    /** Reproduces the unclamped screen bounds Android reported during an activity transition. */
    private void publishOffsetBounds(View view) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (scopedAccessibilityOffset == 0) return;
                Rect bounds = new Rect();
                info.getBoundsInScreen(bounds);
                bounds.offset(scopedAccessibilityOffset, 0);
                info.setBoundsInScreen(bounds);
            }
        });
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) publishOffsetBounds(group.getChildAt(i));
        }
    }

    private TextView addScopedCard(LinearLayout root, String title, boolean nested, boolean first) {
        LinearLayout card = accessibleGroup();
        LinearLayout header = nested ? accessibleGroup() : card;
        LinearLayout footer = nested ? accessibleGroup() : card;
        if (nested) {
            card.addView(header, new LinearLayout.LayoutParams(-1, -2));
            card.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(18);
        header.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));
        Button button = new Button(this);
        button.setText("열기");
        if (first) {
            scopedCardButton = button;
            if (getIntent().getBooleanExtra("scopedArrival", false)) {
                button.setOnClickListener(view -> queueScopedArrival());
            }
            boolean prepare = getIntent().getBooleanExtra("prepareScopedCard", false);
            button.setEnabled(!getIntent().getBooleanExtra("disableScopedCard", false) && !prepare);
            if (prepare) {
                boolean multiple = getIntent().getBooleanExtra("multipleScopedInputs", false);
                boolean sequence = getIntent().getBooleanExtra("sequenceScopedInputs", false);
                String bothRequired = "문서 이름과 분류 코드를 모두 입력해야 열 수 있어요.";
                EditText editor = new EditText(this);
                editor.setHint("문서 이름");
                footer.addView(editor, new LinearLayout.LayoutParams(-1, dp(56)));
                if (multiple) {
                    if (!sequence) editor.setText("공개 시험 제목");
                    EditText code = new EditText(this);
                    code.setHint("분류 코드");
                    footer.addView(code, new LinearLayout.LayoutParams(-1, dp(56)));
                    if (sequence) {
                        TextWatcher readiness = new TextWatcher() {
                            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
                            @Override public void afterTextChanged(Editable text) {
                                boolean ready = !editor.getText().toString().trim().isEmpty() && !code.getText().toString().trim().isEmpty();
                                button.setEnabled(ready);
                                if (android.os.Build.VERSION.SDK_INT >= 30) button.setStateDescription(ready ? null : bothRequired);
                            }
                        };
                        editor.addTextChangedListener(readiness);
                        code.addTextChangedListener(readiness);
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    button.setStateDescription(multiple ? bothRequired
                        : "문서 이름을 입력해야 열 수 있어요.");
                }
            }
        } else {
            scopedCardPeerButton = button;
        }
        footer.addView(button, new LinearLayout.LayoutParams(-1, dp(64)));
        if (getIntent().getBooleanExtra("alternateScopedWrongRoutes", false)) {
            Button viewButton = new Button(this);
            viewButton.setText("보기");
            if (first) viewButton.setOnClickListener(view -> queueScopedArrival());
            footer.addView(viewButton, new LinearLayout.LayoutParams(-1, dp(64)));
        }
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return heading;
    }

    private TextView addOcrCard(LinearLayout rows, String label, int color) {
        TextView card = ocrRow(label, null);
        card.setBackgroundColor(color);
        card.setClickable(true);
        addOcrPanel(rows, card);
        return card;
    }

    private void addOcrIcon(LinearLayout rows, boolean action) {
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_gallery);
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        icon.setBackgroundColor(0xffe1e5f4);
        if (action) {
            icon.setContentDescription("사진 목록");
            icon.setClickable(true);
        } else {
            icon.setContentDescription("장식 그림");
        }
        addOcrPanel(rows, icon);
    }

    private void addOcrPanel(LinearLayout rows, View panel) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(64));
        layout.setMargins(dp(16), dp(8), dp(16), dp(8));
        rows.addView(panel, layout);
    }

    private TextView ocrRow(String label, String accessibilityMarker) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(14);
        row.setTextColor(android.graphics.Color.BLACK);
        row.setBackgroundColor(android.graphics.Color.WHITE);
        row.setGravity(Gravity.CENTER);
        row.setContentDescription(accessibilityMarker);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setText(null);
            }
        });
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static View.AccessibilityDelegate missingChildDelegate() {
        return new View.AccessibilityDelegate() {
            @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
                return new AccessibilityNodeProvider() {
                    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                        if (id != HOST_VIEW_ID) return null;
                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
                        host.onInitializeAccessibilityNodeInfo(info);
                        // The listed child cannot be resolved by the service.
                        info.addChild(host, 1);
                        return info;
                    }
                };
            }
        };
    }
}
