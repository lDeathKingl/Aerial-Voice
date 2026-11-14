package app.orchard.tv.aerialvoice;

import android.app.Activity;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen view of a passthrough input; Back returns to Aerial Voice. */
public final class TvInputActivity extends Activity {
    public static final String EXTRA_INPUT_ID = "app.orchard.tv.aerialvoice.TV_INPUT_ID";
    public static final String EXTRA_LABEL = "app.orchard.tv.aerialvoice.TV_INPUT_LABEL";
    private TvView tvView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String inputId = getIntent().getStringExtra(EXTRA_INPUT_ID);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (inputId == null || inputId.trim().isEmpty()) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        tvView = new TvView(this);
        root.addView(tvView, new FrameLayout.LayoutParams(-1, -1));
        TextView caption = new TextView(this);
        caption.setText((label == null ? "Источник ТВ" : label) + " · BACK — вернуться");
        caption.setTextColor(0xffffffff);
        caption.setTextSize(16);
        caption.setPadding(24, 14, 24, 14);
        caption.setBackgroundColor(0xaa07111f);
        FrameLayout.LayoutParams captionLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(caption, captionLp);
        caption.setOnClickListener(v -> finish());
        setContentView(root);

        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onConnectionFailed(String failedInputId) {
                runOnUiThread(() -> {
                    caption.setText("Не удалось открыть источник · BACK — вернуться");
                });
            }
            @Override public void onDisconnected(String disconnectedInputId) {
                runOnUiThread(() -> caption.setText("Источник отключён · BACK — вернуться"));
            }
        });
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            tvView.tune(inputId, uri);
            tvView.requestFocus();
        } catch (Exception e) {
            caption.setText("Не удалось переключить источник: " + e.getClass().getSimpleName());
        }
    }

    @Override protected void onDestroy() {
        if (tvView != null) {
            try { tvView.reset(); } catch (Exception ignored) { }
            tvView = null;
        }
        super.onDestroy();
    }
}
