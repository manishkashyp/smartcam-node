package in.luxture.smartcam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.File;

public class MainActivity extends Activity {

    private WebView webView;
    private View blackOverlay;
    private boolean isBlackScreen = false;
    private String lastLat = "26.4499";
    private String lastLng = "74.6399";
    private PowerManager.WakeLock cpuWakeLock;

    private static final String NODE_URL = "https://vastram.luxture.in/cctv/node.php";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep Window active & show over lock screen so Camera works 24x7 after reboot
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCam::CpuWakeLock");
            cpuWakeLock.acquire();
        }

        requestAllPermissions();
        startKeepAliveService();
        setupLocationTracking();

        // Build UI Layout programmatically (WebView + Black OLED Dimmer Overlay)
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        setupWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Top Floating Bar for Zero-Heat Black Screen Mode
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        Button dimBtn = new Button(this);
        dimBtn.setText("OLED Black Screen (Save Battery/Heat)");
        dimBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableBlackScreen(true);
            }
        });
        topBar.addView(dimBtn);
        root.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        // Pitch-Black Overlay that drops screen brightness to 0.01f
        blackOverlay = new View(this);
        blackOverlay.setBackgroundColor(0xFF000000);
        blackOverlay.setVisibility(View.GONE);
        blackOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableBlackScreen(false);
            }
        });
        root.addView(blackOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        webView.loadUrl(NODE_URL);
    }

    private void enableBlackScreen(boolean enable) {
        isBlackScreen = enable;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (enable) {
            blackOverlay.setVisibility(View.VISIBLE);
            params.screenBrightness = 0.01f; // Minimum backlight (Zero heat)
        } else {
            blackOverlay.setVisibility(View.GONE);
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(params);
    }

    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        });
    }

    public class NativeBridge {
        @JavascriptInterface
        public float getBatteryTemp() {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320);
                return temp / 10.0f;
            }
            return 32.0f;
        }

        @JavascriptInterface
        public float getSsdFreeGb() {
            try {
                File path = Environment.getExternalStorageDirectory();
                StatFs stat = new StatFs(path.getPath());
                long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
                return Math.round((bytesAvailable / (1024.0f * 1024.0f * 1024.0f)) * 10.0f) / 10.0f;
            } catch (Exception e) {
                return 0.0f;
            }
        }

        @JavascriptInterface
        public String getLat() {
            return lastLat;
        }

        @JavascriptInterface
        public String getLng() {
            return lastLng;
        }
    }

    private void setupLocationTracking() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location loc) {
                        if (loc != null) {
                            lastLat = String.valueOf(loc.getLatitude());
                            lastLng = String.valueOf(loc.getLongitude());
                        }
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 5, listener);
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 5, listener);
            }
        } catch (Exception ignored) {}
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
            requestPermissions(perms, 101);

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void startKeepAliveService() {
        Intent serviceIntent = new Intent(this, CameraNodeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onDestroy() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        super.onDestroy();
    }
}
