package cz.jirkas.crafthub;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = 0xff121316, INK = 0xfff2f1ee, MUTED = 0xffa0a0a4, LINE = 0xff38393d, BLUE = 0xff74aaff;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Net net = new Net();
    private Catalog catalog;
    private Typeface body, heading, mono;
    private TextView connection, summary, state;
    private ProgressBar progress;
    private Button installAll, updateAll, refresh, source, resume, cancel, confirm;
    private GridLayout grid;
    private boolean refreshing, resumed;
    private String jobMarker = "";
    private ArrayList<String> permissionQueue;
    private final Runnable listener = () -> runOnUiThread(this::jobChanged);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        body = font("Inter-Variable.ttf", Typeface.SANS_SERIF);
        heading = font("Archivo-Variable.ttf", Typeface.DEFAULT_BOLD);
        mono = font("GeistMono-Variable.ttf", Typeface.MONOSPACE);
        if (saved != null) permissionQueue = saved.getStringArrayList("permissionQueue");
        buildUi();
        loadCached();
        renderCards();
        fetch();
    }

    private Typeface font(String name, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + name); }
        catch (RuntimeException ignored) { return fallback; }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, int size, int color, Typeface face) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setTypeface(face); return v;
    }
    private Button button(String label, boolean filled, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTypeface(mono); b.setTextSize(13); b.setMinHeight(dp(48));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setTextColor(filled ? BG : INK);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(filled ? BLUE : BG); shape.setStroke(dp(1), filled ? BLUE : LINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3374aaff), shape, null));
        b.setOnClickListener(action);
        return b;
    }
    private void enable(Button b, boolean yes) { b.setEnabled(yes); b.setAlpha(yes ? 1f : .45f); }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(3), dp(3), dp(3), dp(3)); return p; }

    private void buildUi() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = column(); top.setPadding(dp(20), dp(16), dp(20), dp(8));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.hub_icon); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("Craft Hub", 28, INK, heading); title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = button("Obnovit", false, v -> fetch()); header.addView(refresh);
        source = button("Zdroj", false, v -> sourceDialog()); header.addView(source);
        top.addView(header);
        summary = text("Craft App Suite", 16, INK, body); summary.setPadding(0, dp(14), 0, dp(4)); top.addView(summary);
        connection = text("Načítám katalog…", 12, MUTED, body); top.addView(connection);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, dp(6));
        installAll = button("Nainstalovat vše", true, v -> bulk(false));
        updateAll = button("Aktualizovat vše", false, v -> bulk(true));
        actions.addView(installAll, weight()); actions.addView(updateAll, weight()); top.addView(actions);
        state = text("", 14, INK, body); state.setPadding(0, dp(4), 0, dp(6)); top.addView(state);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); top.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
        LinearLayout recovery = new LinearLayout(this);
        resume = button("Pokračovat", true, v -> request(new ArrayList<>(State.pending(this))));
        confirm = button("Potvrdit instalaci", true, v -> showConfirmation(true));
        cancel = button("Zrušit frontu", false, v -> cancelQueue());
        recovery.addView(resume, weight()); recovery.addView(confirm, weight()); recovery.addView(cancel, weight()); top.addView(recovery);
        root.addView(top);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setPadding(dp(14), dp(4), dp(14), dp(20));
        grid = new GridLayout(this); grid.setUseDefaultMargins(false);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        TextView note = text("Komunitní launcher pro Craft App Suite. APK aplikací jsou experimentální Android porty.\nCraft Hub " + BuildConfig.VERSION_NAME, 12, MUTED, body);
        note.setPadding(dp(6), dp(18), dp(6), 0); content.addView(note);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestApplyInsets();
    }

    private void loadCached() {
        try {
            if (State.repo(this).equals(State.prefs(this).getString("catalogRepo", ""))) {
                String cached = State.prefs(this).getString("catalog", "");
                if (!cached.isEmpty()) catalog = Catalog.parse(cached);
            }
            if (catalog == null && Catalog.DEFAULT_REPO.equals(State.repo(this))) {
                try (InputStream in = getAssets().open("offline-catalog.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) { if (out.size() + n > Catalog.MAX_JSON) throw new IOException("Katalog je příliš velký."); out.write(buffer, 0, n); }
                    catalog = Catalog.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) { /* A fresh network catalog can replace a corrupt cache. */ }
    }

    private void fetch() {
        if (refreshing || InstallService.live != null) return;
        refreshing = true; enable(refresh, false); connection.setText("Kontroluji " + State.repo(this) + "…");
        final String repo = State.repo(this);
        network.execute(() -> {
            try {
                String json = net.catalog(repo); Catalog found = Catalog.parse(json);
                runOnUiThread(() -> {
                    if (isDestroyed() || !repo.equals(State.repo(this))) return;
                    catalog = found; refreshing = false;
                    State.prefs(this).edit().putString("catalog", json).putString("catalogRepo", repo).apply();
                    connection.setText(repo + " · ověřeno " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
                    connection.setTextColor(MUTED); renderCards(); updateState();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !repo.equals(State.repo(this))) return;
                    refreshing = false;
                    connection.setText("Katalog nelze obnovit. " + (catalog != null ? "Zobrazuji uložené údaje. " : "") + error(ex));
                    connection.setTextColor(0xffffc14d); updateState();
                });
            }
        });
    }

    private void renderCards() {
        grid.removeAllViews();
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = width >= 1000 ? 3 : width >= 600 ? 2 : 1;
        grid.setColumnCount(columns);
        if (catalog == null) { grid.addView(text("Pro načtení aplikací připojte tablet k internetu a zvolte Obnovit.", 16, MUTED, body)); return; }
        int installedCount = 0, updates = 0, missing = 0, appCount = 0, position = 0;
        boolean busy = InstallService.live != null;
        for (Catalog.Entry entry : catalog.apps) {
            PackageInfo installed = Packages.installed(this, entry.packageId);
            long code = installed == null ? 0 : installed.getLongVersionCode();
            String unavailable = Packages.unavailable(entry, installed);
            if (!Catalog.SELF.equals(entry.packageId)) { appCount++; if (code > 0) installedCount++; else if (unavailable == null) missing++; }
            if (code > 0 && entry.needsInstall(code) && unavailable == null) updates++;
            LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(14));
            GradientDrawable panel = new GradientDrawable(); panel.setColor(BG); panel.setStroke(dp(1), LINE); card.setBackground(panel);
            LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            String iconName = Catalog.SELF.equals(entry.packageId) ? "hub_icon" : entry.packageId.substring(entry.packageId.lastIndexOf('.') + 1);
            int resource = getResources().getIdentifier(iconName, "drawable", getPackageName());
            icon.setImageResource(resource == 0 ? R.drawable.hub_icon : resource); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            title.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView name = text(entry.name, 22, INK, heading); name.setPadding(dp(12), 0, 0, 0); title.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(title);
            TextView description = text(entry.description, 14, MUTED, body); description.setMinLines(2); description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END); description.setPadding(0, dp(12), 0, dp(8)); card.addView(description);
            String version = code == 0 ? "K dispozici " + entry.versionName + " · " + Math.round(entry.size / 1048576d) + " MB"
                : "Nainstalováno " + installed.versionName + " · " + (entry.needsInstall(code) ? "nová " + entry.versionName : code > entry.versionCode ? "novější verze" : "aktuální");
            TextView detail = text(unavailable == null ? version : unavailable, 12, unavailable == null ? BLUE : 0xffffc14d, body); detail.setMinLines(2); card.addView(detail);
            LinearLayout controls = new LinearLayout(this);
            Button open = button(Catalog.SELF.equals(entry.packageId) ? "Spuštěno" : "Otevřít", false, v -> open(entry));
            enable(open, code > 0 && !Catalog.SELF.equals(entry.packageId)); controls.addView(open, weight());
            Button install = button(code == 0 ? "Nainstalovat" : entry.needsInstall(code) ? "Aktualizovat" : "Aktuální", code == 0, v -> request(new ArrayList<>(Collections.singletonList(entry.packageId))));
            enable(install, !busy && unavailable == null && entry.needsInstall(code)); controls.addView(install, weight()); card.addView(controls);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(position / columns), GridLayout.spec(position % columns, 1f));
            p.width = 0; p.height = -2; p.setMargins(dp(6), dp(6), dp(6), dp(6)); grid.addView(card, p); position++;
        }
        summary.setText(appCount + " aplikací · " + installedCount + " nainstalováno · " + updates + " aktualizací");
        installAll.setText("Nainstalovat vše (" + missing + ")"); updateAll.setText("Aktualizovat vše (" + updates + ")");
        enable(installAll, !busy && missing > 0); enable(updateAll, !busy && updates > 0);
    }

    private void bulk(boolean updatesOnly) {
        if (catalog == null) return;
        ArrayList<String> ids = new ArrayList<>();
        for (Catalog.Entry e : catalog.apps) {
            PackageInfo info = Packages.installed(this, e.packageId);
            long installed = info == null ? 0 : info.getLongVersionCode();
            if (Packages.unavailable(e, info) == null && e.needsInstall(installed)
                && (updatesOnly ? installed > 0 : installed == 0 && !Catalog.SELF.equals(e.packageId))) ids.add(e.packageId);
        }
        request(ids);
    }

    private void request(ArrayList<String> ids) {
        if (ids.isEmpty() || InstallService.live != null) return;
        permissionQueue = ids;
        if (!getPackageManager().canRequestPackageInstalls()) {
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), 50); }
            catch (RuntimeException ex) { alert("Povolte instalaci aplikací", "V nastavení Androidu povolte aplikaci Craft Hub instalaci neznámých aplikací."); permissionQueue = null; }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !State.prefs(this).getBoolean("notificationAsked", false)) {
            State.prefs(this).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51);
            return;
        }
        permissionQueue = null;
        try { startForegroundService(new Intent(this, InstallService.class).setAction(InstallService.START).putStringArrayListExtra("ids", ids)); }
        catch (RuntimeException ex) { alert("Instalaci nelze spustit", error(ex)); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 50 && permissionQueue != null) {
            ArrayList<String> ids = permissionQueue; permissionQueue = null;
            if (getPackageManager().canRequestPackageInstalls()) request(ids);
            else alert("Instalace není povolená", "Android vyžaduje povolit instalaci aplikací ze zdroje Craft Hub. Stažené aplikace bude potvrzovat systémový instalátor.");
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 51 && permissionQueue != null) { ArrayList<String> ids = permissionQueue; permissionQueue = null; request(ids); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putStringArrayList("permissionQueue", permissionQueue); }

    private void open(Catalog.Entry e) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(e.packageId);
        if (intent == null) { alert(e.name, "Aplikace nemá dostupnou spouštěcí aktivitu."); return; }
        try { startActivity(intent); } catch (RuntimeException ex) { alert(e.name, error(ex)); }
    }

    private void updateState() {
        InstallService service = InstallService.live;
        List<String> pending = State.pending(this);
        boolean busy = service != null;
        state.setText(busy ? service.message : State.prefs(this).getString("message", "Vyberte aplikaci nebo nainstalujte celou sadu."));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) { progress.setIndeterminate(service.progress < 0); if (service.progress >= 0) progress.setProgress(service.progress); }
        resume.setVisibility(!busy && !pending.isEmpty() ? View.VISIBLE : View.GONE);
        confirm.setVisibility(busy && service.confirmation != null ? View.VISIBLE : View.GONE);
        cancel.setVisibility(busy || !pending.isEmpty() ? View.VISIBLE : View.GONE);
        enable(refresh, !refreshing && !busy);
        enable(source, !busy && pending.isEmpty() && !refreshing);
        if (catalog == null) { enable(installAll, false); enable(updateAll, false); }
    }

    private void jobChanged() {
        if (isDestroyed()) return;
        String marker = (InstallService.live != null ? "busy" : "idle") + State.prefs(this).getInt("session", -1) + State.pending(this).toString();
        if (!marker.equals(jobMarker)) { jobMarker = marker; loadCached(); renderCards(); }
        updateState(); showConfirmation(false);
    }

    private void showConfirmation(boolean repeat) {
        InstallService service = InstallService.live;
        if (!resumed || service == null || service.confirmation == null || (!repeat && service.confirmationShown)) return;
        service.confirmationShown = true;
        try { startActivity(new Intent(service.confirmation)); }
        catch (RuntimeException ex) { alert("Potvrzení instalace", "Otevřete oznámení Craft Hub a potvrďte instalaci. " + error(ex)); }
    }

    private void cancelQueue() {
        startService(new Intent(this, InstallService.class).setAction(InstallService.CANCEL));
    }

    private void sourceDialog() {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(12), dp(24), 0);
        TextView help = text("Veřejný GitHub repozitář, větev main. Přijímány jsou pouze APK se stejným důvěryhodným podpisem jako tato sada.", 14, MUTED, body); panel.addView(help);
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(State.repo(this)); input.setSelectAllOnFocus(true); panel.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Zdroj aplikací").setView(panel).setNegativeButton("Zrušit", null)
            .setNeutralButton("Výchozí", (d, w) -> changeSource(Catalog.DEFAULT_REPO)).setPositiveButton("Uložit", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { changeSource(Catalog.repository(input.getText().toString())); dialog.dismiss(); }
            catch (IllegalArgumentException ex) { input.setError(ex.getMessage()); }
        }));
        dialog.show();
    }
    private void changeSource(String repo) {
        State.prefs(this).edit().putString("repository", repo).remove("catalog").remove("catalogRepo").apply();
        catalog = null; loadCached(); renderCards(); fetch();
    }
    private String error(Exception ex) { String s = ex.getMessage(); return s == null ? ex.getClass().getSimpleName() : s; }
    private void alert(String title, String message) { if (!isFinishing()) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }
    @Override protected void onResume() { super.onResume(); resumed = true; jobMarker = ""; InstallService.listener = listener; jobChanged(); }
    @Override protected void onPause() { resumed = false; if (InstallService.listener == listener) InstallService.listener = null; super.onPause(); }
    @Override protected void onDestroy() { net.cancel(); network.shutdownNow(); super.onDestroy(); }
}
