package com.northmendo.Appzuku;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;

import com.northmendo.Appzuku.databinding.ActivityHelpBinding;

public class HelpActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelpBinding binding = ActivityHelpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.toolbar.setTitle("Appzuku Help");

        String helpText = "<h2>Getting Started</h2>" +
                "<p>Appzuku helps you save battery and reduce RAM usage by stopping background apps.</p>" +

                "<h3>1. Permissions</h3>" +
                "<p>You need either <b>Root</b> access or <b>Shizuku</b>. If you don't have Root, install Shizuku from the Play Store and follow its instructions.</p>"
                +

                "<h3>2. Background Service</h3>" +
                "<p>The <b>Background Service</b> toggle must be ON for any automation features to work. Once enabled, you can use any combination of the following:</p>"
                +

                "<h3>3. Automation Options (Use Separately or Together)</h3>" +
                "<p>Each of these features works <b>independently</b>. Enable only what you need:</p>" +

                "<p><b>Periodic Auto-Kill</b><br/>" +
                "Automatically kills apps on a timer (Check Frequency). Use this for continuous background cleanup.</p>"
                +

                "<p><b>Kill on Screen Lock</b><br/>" +
                "Kills apps when you lock your device. Great for cleaning up when you're done using your phone.</p>" +

                "<p><b>Smart RAM Threshold</b><br/>" +
                "Only kills apps if RAM usage exceeds your threshold (75%-100%). Applies to both Periodic Auto-Kill and Kill on Screen Lock. Prevents unnecessary kills when your device has plenty of free memory.</p>"
                +

                "<h3>4. Example Configurations</h3>" +
                "<p><b>Screen Lock Only:</b> Enable Background Service + Kill on Screen Lock. Leave Periodic Auto-Kill OFF.</p>"
                +
                "<p><b>Timer Only:</b> Enable Background Service + Periodic Auto-Kill. Set your preferred frequency.</p>"
                +
                "<p><b>Smart Kill:</b> Enable Background Service + Kill on Screen Lock + Smart RAM Threshold. Apps only killed when RAM is high.</p>"
                +

                "<h3>5. Manual Kill (No Service Needed)</h3>" +
                "<p>These features work without the background service:</p>" +
                "<ul>" +
                "<li><b>Main Screen:</b> Select apps and tap the kill button</li>" +
                "<li><b>Quick Tile - Kill App:</b> Tap to kill the current foreground app</li>" +
                "<li><b>Quick Tile - Kill Background Apps:</b> Tap to run your configured whitelist/blacklist background kill</li>" +
                "<li><b>Widget:</b> One-tap kill from your home screen</li>" +
                "</ul>" +

                "<h3>6. Kill Modes</h3>" +
                "<p><b>Whitelist Mode (Default):</b> Kills all apps EXCEPT those you whitelist.</p>" +
                "<p><b>Blacklist Mode:</b> Kills ONLY the apps you select in the Blacklist.</p>" +

                "<h3>7. Kill History</h3>" +
                "<p>View statistics on which apps were killed and how often they relaunch. Apps that frequently restart ('greedy apps') can be added to Background Restriction so Android keeps them asleep as aggressively as possible.</p>"
                +

                "<h3>\u26a0\ufe0f 8. System Apps Safety Warning</h3>" +
                "<p><b>IMPORTANT:</b> System apps are critical for device stability and functionality. Examples include:</p>"
                +
                "<ul>" +
                "<li>Security apps (e.g., 'Security' on Xiaomi/MIUI devices)</li>" +
                "<li>Android System UI (com.android.systemui)</li>" +
                "<li>Phone services and system frameworks</li>" +
                "</ul>" +
                "<p><b>Risks of Blocking System Apps:</b><br/>Blocking or killing system apps may cause crashes, boot loops, loss of functionality, broken widgets, delayed notifications, or device malfunction. Only modify system apps if you fully understand the consequences.</p>"
                +
                "<p>The app will warn you when selecting system apps in filters or Background Restriction.</p>";

        binding.helpContent.setText(Html.fromHtml(helpText, Html.FROM_HTML_MODE_COMPACT));
        binding.helpContent.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
