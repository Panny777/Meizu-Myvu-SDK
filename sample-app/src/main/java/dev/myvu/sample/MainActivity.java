package dev.myvu.sample;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import dev.myvu.sample.ai.ClaudeLanguageModel;
import dev.myvu.sample.ai.GroqSpeechToText;
import dev.myvu.sdk.ConnectionState;
import dev.myvu.sdk.MyvuClient;
import dev.myvu.sdk.ai.AiSession;
import dev.myvu.sdk.util.SdkLog;

import java.util.List;

/**
 * A minimal single-screen control panel demonstrating the MYVU SDK.
 *
 * Everything is built in code to keep the sample self-contained. It starts a
 * {@link MyvuService} that owns the {@link MyvuClient}, then drives the common
 * features (teleprompter, settings, trackpad) and shows the SDK's live log.
 */
public class MainActivity extends AppCompatActivity implements LogRingBuffer.Listener {

    private static final LogRingBuffer LOG = new LogRingBuffer();

    static {
        // Route the SDK's diagnostics into our on-screen pane.
        SdkLog.setLogger(LOG);
    }

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private AiSession ai;

    private final MyvuClient.Listener stateListener = new MyvuClient.Listener() {
        @Override
        public void onConnectionStateChanged(ConnectionState state) {
            runOnUiThread(() -> statusView.setText("Status: " + state));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        LOG.addListener(this);
        for (String line : LOG.history()) appendLine(line);
        MyvuClient client = MyvuService.client();
        if (client != null) client.addListener(stateListener);
    }

    @Override
    protected void onStop() {
        LOG.removeListener(this);
        MyvuClient client = MyvuService.client();
        if (client != null) client.removeListener(stateListener);
        super.onStop();
    }

    // --------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setText("Status: IDLE");
        statusView.setTextSize(18);
        root.addView(statusView);

        root.addView(button("Connect (auto-search)", v -> {
            appendLine(">> starting connection");
            MyvuService.start(this, null);
            MyvuClient client = MyvuService.client();
            if (client != null) client.addListener(stateListener);
        }));
        root.addView(button("Disconnect", v -> MyvuService.stop(this)));

        root.addView(button("Teleprompter", v -> withClient(c ->
                c.openTeleprompter(
                        "Welcome to the MYVU SDK.\nThis text is on your glasses.\n"
                                + "Tap highlight to advance.", "Demo"))));
        root.addView(button("Highlight line 2", v -> withClient(c ->
                c.teleprompterHighlight(1, "Demo"))));
        root.addView(button("Notification", v -> withClient(c ->
                c.showNotification("MYVU SDK", "Hello from your phone"))));
        root.addView(button("Brightness max", v -> withClient(c -> c.setBrightness(10))));
        root.addView(button("Brightness low", v -> withClient(c -> c.setBrightness(2))));
        root.addView(button("Trackpad click", v -> withClient(c -> c.trackpadClick())));
        root.addView(button("Sync time", v -> withClient(c -> c.syncTime())));
        root.addView(button("Enable AI assistant", v -> enableAi()));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        root.addView(logScroll, lp);

        return root;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    // ------------------------------------------------------------ actions

    private void withClient(ClientAction action) {
        MyvuClient client = MyvuService.client();
        if (client == null || client.state() != ConnectionState.READY) {
            Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show();
            return;
        }
        action.run(client);
    }

    private interface ClientAction {
        void run(MyvuClient client);
    }

    private void enableAi() {
        MyvuClient client = MyvuService.client();
        if (client == null) {
            Toast.makeText(this, "Connect first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ai != null) {
            Toast.makeText(this, "AI already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        GroqSpeechToText stt = new GroqSpeechToText(BuildConfig.GROQ_API_KEY);
        ClaudeLanguageModel llm = new ClaudeLanguageModel(BuildConfig.CLAUDE_API_KEY, null);
        if (!stt.isReady() || !llm.isReady()) {
            appendLine("!! set claudeApiKey/groqApiKey in gradle.properties to use AI");
        }
        ai = new AiSession(this, client, stt, llm, null);
        ai.attach();
        appendLine(">> AI assistant attached (press the glasses' AI button)");
    }

    // --------------------------------------------------------- permissions

    private void requestPermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
            addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
        }
    }

    private void addIfMissing(List<String> out, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            out.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permissions are required",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    // --------------------------------------------------------------- log

    @Override
    public void onLine(String line) {
        appendLine(line);
    }

    private void appendLine(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
