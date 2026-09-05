package jp.chisana.foxkifuscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;

public final class MainActivity extends Activity {
    public static final String ACTION_STATUS = "jp.chisana.foxkifuscanner.STATUS";
    public static final String EXTRA_STATUS = "status";
    private static final int REQUEST_CAPTURE = 501;
    private TextView status;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (status != null) status.setText(intent.getStringExtra(EXTRA_STATUS));
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
    }

    private ScrollView buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(pad, pad, pad, pad);
        TextView title = text("野狐棋譜スキャナー", 25); title.setTextColor(0xFF0B6172); content.addView(title);
        TextView intro = text("野狐囲碁の棋譜画面を一手ずつ操作し、盤面と対局情報からSGFを作成します。画像は端末内だけで処理し、通信内容やログイン情報にはアクセスしません。", 15);
        intro.setPadding(0, dp(8), 0, dp(14)); content.addView(intro);

        Button capture = button("1. 画面読取を許可");
        capture.setOnClickListener(v -> requestCapture()); content.addView(capture);
        Button accessibility = button("2. ユーザー補助を有効化");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        content.addView(accessibility);
        Button overlay = button("3. 画面上に操作パネルを表示");
        overlay.setOnClickListener(v -> {
            if (!ReaderAccessibilityService.showOverlayIfConnected()) {
                status.setText("ユーザー補助の「野狐棋譜スキャナー」を有効にしてください");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else if (!CaptureService.isReady()) status.setText("操作パネルを表示しました。先に画面読取も許可してください");
            else status.setText("操作パネルを表示しました。野狐の棋譜画面で「開始」を押してください");
        });
        content.addView(overlay);

        status = text("準備してください", 15); status.setTextColor(0xFF17364A);
        status.setBackgroundColor(0xFFE7F1F4); status.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(15), 0, dp(15)); content.addView(status, statusLp);

        TextView steps = text("使い方\n\n① 画面読取を許可します。\n② ユーザー補助設定で本アプリを有効にします。既に有効なら切り替え直す必要はありません。\n③ 操作パネルを表示し、野狐囲碁で対象の棋譜画面を開きます。\n④ パネルの「開始」を押します。パネルは盤面直下へ自動配置され、読取中も表示されます。\n⑤ 初期局面への巻き戻し後、一手ずつ自動読取します。\n⑥ 完了後、Downloadフォルダーへ［黒番］_vs_［白番］_yyyymmdd.sgfとして保存されます。\n\n「停止」は未完了のSGFを保存せず安全に中止します。終局メッセージを確認できない場合も保存しません。", 14);
        content.addView(steps);
        Button exit = button("終了");
        exit.setOnClickListener(v -> { ReaderAccessibilityService.exitIfConnected(); CaptureService.shutdown(this); finishAndRemoveTask(); });
        content.addView(exit);

        ScrollView root = new ScrollView(this); root.addView(content); return root;
    }

    private void requestCapture() {
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != Activity.RESULT_OK || data == null) { status.setText("画面読取が許可されませんでした"); return; }
        Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        status.setText("画面読取を許可しました");
    }

    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(15);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return b;
    }
    private TextView text(String value, float size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setLineSpacing(0, 1.15f); return t;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public static void publishStatus(Context context, String text) {
        Intent i = new Intent(ACTION_STATUS).setPackage(context.getPackageName()).putExtra(EXTRA_STATUS, text);
        context.sendBroadcast(i);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ReaderAccessibilityService.isConnected() && status != null) status.setText("ユーザー補助：有効");
    }
    @Override protected void onDestroy() { try { unregisterReceiver(receiver); } catch (Exception ignored) {} super.onDestroy(); }
}
