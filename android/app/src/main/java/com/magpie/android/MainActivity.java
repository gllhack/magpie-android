package com.magpie.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * magpie's own page in a WebView, with the gateway it needs running behind it:
 * the menu starts and stops it, changes the ports, or shows the log.
 *
 * <p>The chrome around the page is the app's own; the page's own look comes
 * from {@code assets/magpie-mobile.css}, painted into it on every load.
 * Everything that used to drop down — the menu, the settings, the log — comes
 * up from the floor as a sheet, where a thumb reaches it.
 */
public final class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int REQUEST_LOCAL_NETWORK = 2;
    private static final String PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    private static final long POLL_MS = 700;
    /** Roughly a minute of waiting before the overlay offers the log. */
    private static final int POLLS_BEFORE_GIVING_UP = 90;

    private static final String SKIN_ID = "magpie-mobile-css";
    private static final String SKIN_ASSET = "magpie-mobile.css";
    private static final String SCRIPT_ASSET = "magpie-mobile.js";

    private WebView web;
    private View root;
    private View appBar;
    private View webHost;
    private View overlay;
    private View overlayCard;
    private View overlayProgress;
    private View overlayMark;
    private ImageView logoBadge;
    private ImageButton menuButton;
    private TextView appTitle;
    private View statusDot;
    private TextView statusText;
    private TextView addressText;
    private TextView overlayText;
    private Button overlayButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The port is probed here: a socket on the main thread is a StrictMode death. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean pageLoaded;
    private int waits;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            io.execute(() -> {
                boolean up = MagpieGateway.portOpen("127.0.0.1", MagpiePrefs.webPort(MainActivity.this), 400);
                handler.post(() -> polled(up));
            });
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void polled(boolean up) {
        if (isDestroyed()) {
            return;
        }
        status(up);
        if (up) {
            waits = 0;
            if (!pageLoaded) {
                pageLoaded = true;
                web.loadUrl(MagpieGateway.pageUrl(this));
            }
        } else if (++waits == POLLS_BEFORE_GIVING_UP) {
            overlay(getString(R.string.overlay_failed), getString(R.string.overlay_hint), true);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        root = findViewById(R.id.root);
        appBar = findViewById(R.id.appBar);
        webHost = findViewById(R.id.webHost);
        overlay = findViewById(R.id.overlay);
        overlayCard = findViewById(R.id.overlayCard);
        overlayProgress = findViewById(R.id.overlayProgress);
        overlayMark = findViewById(R.id.overlayMark);
        logoBadge = findViewById(R.id.logoBadge);
        menuButton = findViewById(R.id.menuButton);
        appTitle = findViewById(R.id.appTitle);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        overlayText = findViewById(R.id.overlayText);
        overlayButton = findViewById(R.id.overlayButton);

        findViewById(R.id.root).setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            // the keyboard is over the page, not over the window: taking its
            // height as padding is what puts a sheet the page anchors to the
            // floor above the keys rather than under them
            Insets keys = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keys.bottom));
            return WindowInsets.CONSUMED;
        });

        overlayButton.setOnClickListener(v -> {
            waits = 0;
            pageLoaded = false;
            overlay(getString(R.string.status_starting), null, false);
        });

        menuButton.setOnClickListener(v -> showMenu());
        addressText.setOnClickListener(v -> copyGateway());

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // the page opens its sign-ins and its downloads in a new window
        // (window.open): Android has no window to put them in, and without
        // these two that request is dropped on the floor and the button looks
        // dead — so it is handed to the browser the phone already has
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        web.setBackgroundColor(getColor(R.color.app_bg));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebChromeClient(new WebChromeClient() {
            /**
             * A window the page asked for by itself. There is nowhere to show
             * it, so the URL is read off a WebView that never paints — one
             * window's worth of transport, thrown away after — and given to
             * the browser. magpie's own page stays where it was, which is the
             * point: a sign-in that replaced it would have no way back.
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message result) {
                WebView probe = new WebView(MainActivity.this);
                probe.getSettings().setJavaScriptEnabled(false);
                final boolean[] spent = {false};
                Runnable swept = () -> {
                    if (!spent[0]) {
                        spent[0] = true;
                        probe.destroy();
                    }
                };
                probe.setWebViewClient(new WebViewClient() {
                    private boolean handOff(WebView inner, String url) {
                        if (!spent[0]) {
                            spent[0] = true;
                            openExternal(url);
                            handler.post(inner::destroy);
                        }
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView inner, WebResourceRequest request) {
                        return handOff(inner, request.getUrl().toString());
                    }
                });
                // a window that never named a URL (about:blank and then
                // written into) would otherwise sit there for good
                handler.postDelayed(swept, 8000);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
                transport.setWebView(probe);
                result.sendToTarget();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                // before the first paint, so the page never flashes its own look
                paintSkin();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                paintSkin();
                hideOverlay();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageLoaded = false;
                    overlay(getString(R.string.overlay_failed),
                            error == null ? null : String.valueOf(error.getDescription()), true);
                }
            }

            /**
             * The page in this window is magpie's own. A link that leaves it —
             * a vendor's site, a release to download — goes to the browser
             * instead: there is no back button here to come home with.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                String url = request.getUrl().toString();
                if (isMagpiePage(url)) {
                    return false;
                }
                openExternal(url);
                return true;
            }
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (web.canGoBack()) {
                        web.goBack();
                    } else {
                        finish();
                    }
                });

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }

        showAddress();
        requestLocalNetworkPermissionIfNeeded();
        MagpieService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showAddress();
        handler.postDelayed(poll, 100);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * A day/night switch arrives here rather than as a relaunch, which keeps
     * the page — and whatever is typed into it — where it was. The price is
     * that this layout is not inflated again: the chrome is repainted and the
     * bar icons are told which way round they now go.
     */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        boolean night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(night ? 0 : light, light);
        }
        paintChrome();
        status(MagpieGateway.isRunning());
        showAddress();
    }

    private void showAddress() {
        addressText.setText("127.0.0.1:" + MagpiePrefs.gatewayPort(this));
    }

    private void status(boolean up) {
        int color = getColor(up ? R.color.status_ok : R.color.status_off);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        statusText.setText(up ? R.string.status_running : R.string.status_starting);
    }

    private void overlay(String text, String detail, boolean retry) {
        overlay.setVisibility(View.VISIBLE);
        overlayProgress.setVisibility(retry ? View.GONE : View.VISIBLE);
        overlayMark.setVisibility(retry ? View.VISIBLE : View.GONE);
        overlayText.setText(detail == null ? text : text + "\n" + detail);
        overlayButton.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    // ---- the chrome's own colours ------------------------------------------

    /**
     * Every drawable and colour of {@code activity_main.xml}, set again. A
     * view keeps what it was inflated with, and a drawable keeps the colours
     * it resolved with, so a switch between day and night has to say all of
     * this a second time.
     *
     * <p>The drawables are handed over as values rather than as resource ids:
     * {@code setBackgroundResource} has nothing to do when the id it is given
     * is the one the view already carries, which is exactly the case here and
     * would leave the old colours on screen.
     */
    private void paintChrome() {
        root.setBackground(getDrawable(R.drawable.bg_app_root));
        appBar.setBackground(getDrawable(R.drawable.bg_app_bar));
        logoBadge.setBackground(getDrawable(R.drawable.bg_logo_badge));
        appTitle.setTextColor(getColor(R.color.text_primary));
        statusText.setTextColor(getColor(R.color.text_secondary));
        statusDot.setBackground(getDrawable(R.drawable.status_dot));
        addressText.setTextColor(getColor(R.color.accent));
        addressText.setBackground(getDrawable(R.drawable.bg_address_chip));
        menuButton.setBackground(getDrawable(R.drawable.bg_icon_button));
        menuButton.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_primary)));

        webHost.setBackground(getDrawable(R.drawable.bg_web_sheet));
        web.setBackgroundColor(getColor(R.color.app_bg));

        overlay.setBackgroundColor(getColor(R.color.app_bg));
        overlayCard.setBackground(getDrawable(R.drawable.bg_overlay_card));
        ((ProgressBar) overlayProgress)
                .setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        overlayText.setTextColor(getColor(R.color.text_primary));
        overlayButton.setBackground(getDrawable(R.drawable.bg_button_primary));
        overlayButton.setTextColor(getColor(R.color.text_on_accent));
    }

    // ---- the app's own look, painted into magpie's page ---------------------

    /**
     * magpie serves its own page and this is not it: the skin is one style
     * element the page keeps, rewritten on every load. A page that already
     * carries it — the same document loaded again — is repainted rather than
     * given a second one.
     */
    private void paintSkin() {
        String css;
        String script;
        try {
            css = readAsset(SKIN_ASSET);
            script = readAsset(SCRIPT_ASSET);
        } catch (IOException e) {
            return; // magpie's own page, then: it just goes without the skin
        }
        String js = "(function(){var id='" + SKIN_ID + "',el=document.getElementById(id);"
                + "if(!el){el=document.createElement('style');el.id=id;"
                + "(document.head||document.documentElement).appendChild(el);}"
                + "el.textContent=" + JSONObject.quote(css) + ";})();";
        web.evaluateJavascript(js, null);
        // Whether external plugins can run here, told before the script below
        // reads it: that script leaves the plugin page's install buttons alone
        // when the APK carries Bun.
        web.evaluateJavascript("window.magpieBun = "
                + (MagpieGateway.hasBundledBun(this) ? "true" : "false") + ";", null);
        // and that this is the Android shell, not magpie on a computer: the
        // page keeps the few things only a desktop can do to itself
        web.evaluateJavascript("window.magpieAndroid = true;", null);
        web.evaluateJavascript(script, null);
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ---- the sheets ---------------------------------------------------------

    /** A dialog that comes up from the floor: the menu, the settings, the log. */
    private Dialog sheet(int layoutRes, int rootId) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(this).inflate(layoutRes, null);
        dialog.setContentView(content);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.BottomSheetAnimation);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        View root = content.findViewById(rootId);
        if (root == null) {
            root = content;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // the sheet's own background stops above the navigation bar
            Insets bars = insets.getInsets(WindowInsets.Type.navigationBars());
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), bars.bottom);
            return WindowInsets.CONSUMED;
        });
        return dialog;
    }

    /**
     * One line of a sheet. A sheet that closes does it in its own action, so
     * the log's own lines can leave it open.
     */
    private void addSheetItem(LinearLayout items, int iconRes, int titleRes,
                              CharSequence hint, Runnable action) {
        View item = LayoutInflater.from(this).inflate(R.layout.sheet_item_action, items, false);
        ((ImageView) item.findViewById(R.id.sheetItemIcon)).setImageResource(iconRes);
        ((TextView) item.findViewById(R.id.sheetItemTitle)).setText(titleRes);
        TextView sub = item.findViewById(R.id.sheetItemSubtitle);
        if (hint == null || hint.length() == 0) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(hint);
            sub.setVisibility(View.VISIBLE);
        }
        item.setOnClickListener(v -> action.run());
        items.addView(item);
    }

    private void showMenu() {
        Dialog sheet = sheet(R.layout.dialog_sheet, R.id.sheetRoot);
        ((TextView) sheet.findViewById(R.id.sheetTitle)).setText(R.string.menu);
        ((TextView) sheet.findViewById(R.id.sheetSubtitle)).setText(getString(R.string.menu_subtitle,
                addressText.getText(), "127.0.0.1:" + MagpiePrefs.webPort(this)));

        boolean running = MagpieGateway.isRunning();
        LinearLayout items = sheet.findViewById(R.id.sheetItems);
        addSheetItem(items, R.drawable.ic_refresh, R.string.menu_reload,
                getString(R.string.menu_reload_hint), () -> {
                    sheet.dismiss();
                    reload();
                });
        addSheetItem(items, R.drawable.ic_open_external, R.string.menu_open_browser,
                getString(R.string.menu_open_browser_hint), () -> {
                    sheet.dismiss();
                    openInBrowser();
                });
        addSheetItem(items, R.drawable.ic_copy, R.string.menu_copy_gateway,
                getString(R.string.menu_copy_gateway_hint, String.valueOf(MagpiePrefs.gatewayPort(this))), () -> {
                    sheet.dismiss();
                    copyGateway();
                });
        addSheetItem(items, R.drawable.ic_restart, R.string.menu_restart,
                getString(R.string.menu_restart_hint), () -> {
                    sheet.dismiss();
                    restart();
                });
        addSheetItem(items, R.drawable.ic_power,
                running ? R.string.menu_stop : R.string.menu_start,
                getString(running ? R.string.menu_stop_hint : R.string.menu_start_hint,
                        addressText.getText()), () -> {
                    sheet.dismiss();
                    toggleGateway();
                });
        addSheetItem(items, R.drawable.ic_terminal, R.string.menu_log,
                getString(R.string.menu_log_hint), () -> {
                    sheet.dismiss();
                    showLog();
                });
        addSheetItem(items, R.drawable.ic_settings_sliders, R.string.menu_settings,
                getString(R.string.menu_settings_hint), () -> {
                    sheet.dismiss();
                    showSettings();
                });
        addSheetItem(items, R.drawable.ic_battery, R.string.menu_battery,
                getString(R.string.menu_battery_hint), () -> {
                    sheet.dismiss();
                    askForBatteryExemption();
                });
        sheet.show();
    }

    private void reload() {
        pageLoaded = false;
        waits = 0;
        overlay(getString(R.string.status_starting), null, false);
    }

    private void toggleGateway() {
        if (MagpieGateway.isRunning()) {
            MagpieService.stop(this);
            toast(getString(R.string.status_stopped));
        } else {
            MagpieService.start(this);
            reload();
        }
    }

    private void restart() {
        MagpieGateway.stop();
        MagpieService.start(this);
        waits = 0;
        pageLoaded = false;
        web.loadUrl("about:blank");
        overlay(getString(R.string.status_starting), null, false);
    }

    private void openInBrowser() {
        openExternal(MagpieGateway.pageUrl(this));
    }

    /**
     * A URL the page asked for — a sign-in, a download, a link out — in
     * whichever browser the phone has. Whatever the browser is asked next is
     * its own business; magpie's page is left loaded behind it.
     */
    private void openExternal(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_browser));
        }
    }

    /** magpie's own page: the one address this window is meant to show. */
    private boolean isMagpiePage(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || "about".equals(scheme) || "data".equals(scheme)
                || "blob".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme)
                && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == MagpiePrefs.webPort(this);
    }

    private void copyGateway() {
        String url = MagpieGateway.gatewayUrl(this) + "/v1";
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("magpie", url));
        toast(getString(R.string.copied, url));
    }

    private void showLog() {
        Dialog sheet = sheet(R.layout.dialog_sheet, R.id.sheetRoot);
        ((TextView) sheet.findViewById(R.id.sheetTitle)).setText(R.string.log_title);
        ((TextView) sheet.findViewById(R.id.sheetSubtitle)).setText(R.string.log_subtitle);

        LinearLayout items = sheet.findViewById(R.id.sheetItems);
        TextView log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(getColor(R.color.text_secondary));
        log.setTextIsSelectable(true);

        ScrollView box = new ScrollView(this);
        box.setBackgroundResource(R.drawable.bg_log_box);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        box.setOverScrollMode(View.OVER_SCROLL_NEVER);
        box.addView(log);
        LinearLayout.LayoutParams boxParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        boxParams.topMargin = dp(4);
        items.addView(box, boxParams);

        readLogInto(log);
        addSheetItem(items, R.drawable.ic_refresh, R.string.refresh, null, () -> readLogInto(log));
        addSheetItem(items, R.drawable.ic_close, R.string.close, null, sheet::dismiss);
        sheet.show();
    }

    private void readLogInto(TextView view) {
        String log = readLog();
        view.setText(log.isEmpty() ? getString(R.string.log_empty) : log);
    }

    /** The last lines of the log, which is what a look at it needs. */
    private String readLog() {
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(MagpieGateway.logFile(this)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > 300) {
                    lines.removeFirst();
                }
            }
        } catch (IOException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void showSettings() {
        Dialog sheet = sheet(R.layout.dialog_settings, R.id.settingsRoot);
        EditText gatewayPort = sheet.findViewById(R.id.settingsGatewayPort);
        EditText webPort = sheet.findViewById(R.id.settingsWebPort);
        Switch lan = sheet.findViewById(R.id.settingsLan);
        Switch autostart = sheet.findViewById(R.id.settingsAutostart);
        gatewayPort.setText(String.valueOf(MagpiePrefs.gatewayPort(this)));
        webPort.setText(String.valueOf(MagpiePrefs.webPort(this)));
        lan.setChecked(MagpiePrefs.lan(this));
        autostart.setChecked(MagpiePrefs.autostart(this));

        sheet.findViewById(R.id.settingsCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.findViewById(R.id.settingsSave).setOnClickListener(v -> {
            int gateway = parsePort(gatewayPort);
            int page = parsePort(webPort);
            if (!MagpiePrefs.validPort(gateway) || !MagpiePrefs.validPort(page)) {
                toast(getString(R.string.settings_invalid_port));
                return;
            }
            if (gateway == page) {
                toast(getString(R.string.settings_same_port));
                return;
            }
            boolean wantLan = lan.isChecked();
            boolean wantAutostart = autostart.isChecked();
            boolean changed = gateway != MagpiePrefs.gatewayPort(this)
                    || page != MagpiePrefs.webPort(this)
                    || wantLan != MagpiePrefs.lan(this);
            MagpiePrefs.save(this, gateway, page, wantLan, wantAutostart);
            sheet.dismiss();
            showAddress();
            if (wantLan) {
                requestLocalNetworkPermissionIfNeeded();
            }
            if (changed) {
                restart();
            }
        });
        sheet.show();
    }

    private int parsePort(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Android 16's local network protection is off by default and Android 17
     * makes it a permission of its own: ask for it where it exists, so binding
     * 0.0.0.0 keeps working after an update.
     */
    private void requestLocalNetworkPermissionIfNeeded() {
        if (!MagpiePrefs.lan(this)) {
            return;
        }
        if (checkSelfPermission(PERMISSION_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            // Only Android versions that define the permission are asked: it
            // arrives with local network protection.
            getPackageManager().getPermissionInfo(PERMISSION_LOCAL_NETWORK, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return;
        }
        toast(getString(R.string.lan_permission_rationale));
        requestPermissions(new String[]{PERMISSION_LOCAL_NETWORK}, REQUEST_LOCAL_NETWORK);
    }

    private void askForBatteryExemption() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
