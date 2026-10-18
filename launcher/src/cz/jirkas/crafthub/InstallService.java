package cz.jirkas.crafthub;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One user-started queue, one APK/session at a time. The remaining IDs survive process death. */
public final class InstallService extends Service {
    public static final String START = "cz.jirkas.crafthub.START", CANCEL = "cz.jirkas.crafthub.CANCEL";
    public static final String RESULT = "cz.jirkas.crafthub.INSTALL_RESULT";
    static final int NOTICE = 7;
    static volatile InstallService live;
    static Runnable listener;
    volatile String message = "Připravuji instalaci…";
    volatile int progress = -1;
    Intent confirmation;
    boolean confirmationShown;
    private boolean busy;
    private volatile boolean cancelled;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Catalog.Entry> queue = new ArrayList<>();
    private String repository;
    private int total;
    private long lastProgress;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) {
            cancelled = true;
            abandon();
            State.pending(this, Collections.emptyList());
            finish("Fronta byla zrušena.", false);
            return START_NOT_STICKY;
        }
        if (busy || !START.equals(intent.getAction())) return START_NOT_STICKY;
        ArrayList<String> ids = intent.getStringArrayListExtra("ids");
        if (ids == null || ids.isEmpty() || ids.size() > 64) { stopSelf(); return START_NOT_STICKY; }
        busy = true;
        live = this;
        channels(this);
        startForeground(NOTICE, notification(this, message, -1, null, true));
        if (!State.pending(this, ids)) { finish("Nelze uložit frontu. Zkontrolujte volné místo.", true); return START_NOT_STICKY; }
        repository = State.repo(this);
        worker.execute(() -> {
            try {
                // Only sessions owned by this app are abandoned, when the user resumes/retries a lost queue.
                for (PackageInstaller.SessionInfo old : getPackageManager().getPackageInstaller().getMySessions()) {
                    try { getPackageManager().getPackageInstaller().abandonSession(old.getSessionId()); }
                    catch (RuntimeException ignored) { /* The OS may already have completed it. */ }
                }
                State.clearSession(this);
                File[] leftovers = getCacheDir().listFiles((dir, name) -> name.startsWith("craft-") && name.endsWith(".apk"));
                if (leftovers != null) for (File file : leftovers) file.delete();
                String json = Net.catalog(repository);
                Catalog catalog = Catalog.parse(json);
                queue = catalog.select(ids);
                total = queue.size();
                State.prefs(this).edit().putString("catalog", json).putString("catalogRepo", repository).apply();
                saveQueue();
                installNext();
            } catch (Exception ex) { failed(ex); }
        });
        changed();
        return START_NOT_STICKY;
    }

    private void saveQueue() throws IOException {
        checkCancelled();
        List<String> ids = new ArrayList<>();
        for (Catalog.Entry e : queue) ids.add(e.packageId);
        if (!State.pending(this, ids)) throw new IOException("Nelze uložit průběh instalace.");
    }

    private void installNext() {
        try {
            checkCancelled();
            while (!queue.isEmpty() && !queue.get(0).needsInstall(Packages.version(this, queue.get(0).packageId))) {
                queue.remove(0);
                saveQueue();
            }
            if (queue.isEmpty()) { main.post(() -> finish("Všechny vybrané aplikace jsou aktuální.", false)); return; }
            Catalog.Entry entry = queue.get(0);
            String problem = Packages.unavailable(this, entry);
            if (problem != null) throw new IOException(entry.name + ": " + problem);
            if (!getPackageManager().canRequestPackageInstalls()) throw new IOException("Povolte aplikaci Craft Hub instalaci neznámých aplikací.");
            String prefix = (total - queue.size() + 1) + "/" + total + " · " + entry.name;
            report(prefix + " – stahování…", 0);
            File apk = Net.download(repository, entry, getCacheDir(), (done, size) -> {
                checkCancelled();
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress > 350 || done == size) {
                    lastProgress = now;
                    int percent = (int) (done * 100 / size);
                    report(prefix + " – stahování " + percent + " %", percent);
                }
            });
            try {
                checkCancelled();
                report(prefix + " – ověřování podpisu…", -1);
                Packages.verify(this, entry, apk);
                install(entry, apk);
            } finally { apk.delete(); }
        } catch (Exception ex) { failed(ex); }
    }

    private void install(Catalog.Entry entry, File apk) throws IOException {
        checkCancelled();
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(entry.packageId);
        params.setSize(entry.size);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(Packages.version(this, entry.packageId) > 0
                ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, entry.size)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) { checkCancelled(); out.write(buffer, 0, n); }
                session.fsync(out);
            }
            checkCancelled();
            if (!State.prefs(this).edit().putInt("session", id).putString("activePackage", entry.packageId)
                .putLong("activeVersion", entry.versionCode).commit()) throw new IOException("Nelze uložit instalační relaci.");
            Intent result = new Intent(this, InstallReceiver.class).setAction(RESULT);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent callback = PendingIntent.getBroadcast(this, id, result, piFlags);
            report(entry.name + " – instalace…", -1);
            session.commit(callback.getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
                State.clearSession(this);
            }
        }
    }

    void result(Intent result) {
        if (cancelled) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            confirmationShown = false;
            if (confirmation == null) { failed(new IOException("Android neposkytl potvrzovací dialog.")); return; }
            message = "Potvrďte instalaci v dialogu Androidu.";
            progress = -1;
            getSystemService(NotificationManager.class).notify(NOTICE, notification(this, message, -1, confirmation, true));
            changed();
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            confirmation = null;
            confirmationShown = false;
            State.completed(this);
            if (!queue.isEmpty()) queue.remove(0);
            changed();
            worker.execute(this::installNext);
        } else {
            confirmation = null;
            String detail = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (detail == null) detail = "kód " + status;
            if (detail.length() > 250) detail = detail.substring(0, 250);
            State.clearSession(this);
            finish(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Instalace byla zrušena. Zbytek fronty můžete dokončit později."
                : "Android instalaci odmítl: " + detail, true);
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted() || live != this) throw new InterruptedIOException("Přerušeno.");
    }
    private void failed(Exception ex) {
        main.post(() -> {
            if (!cancelled && live == this) {
                abandon();
                String detail = ex.getMessage();
                finish("Instalace přerušena: " + (detail == null ? ex.getClass().getSimpleName() : detail), true);
            }
        });
    }
    private void abandon() {
        int session = State.prefs(this).getInt("session", -1);
        if (session != -1) try { getPackageManager().getPackageInstaller().abandonSession(session); } catch (RuntimeException ignored) { }
        State.clearSession(this);
    }
    private void report(String text, int value) {
        main.post(() -> {
            if (live != this || cancelled) return;
            message = text;
            progress = value;
            getSystemService(NotificationManager.class).notify(NOTICE, notification(this, text, value, null, true));
            changed();
        });
    }
    private void finish(String text, boolean error) {
        if (live != null && live != this) return;
        message = text;
        State.prefs(this).edit().putString("message", text).commit();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (error) getSystemService(NotificationManager.class).notify(NOTICE, notification(this, text, -1, null, false));
        stopSelf();
    }
    static void changed() { if (listener != null) listener.run(); }
    static void channels(Context c) {
        c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("installs", "Instalace a aktualizace", NotificationManager.IMPORTANCE_LOW));
    }
    static Notification notification(Context c, String text, int percent, Intent confirm, boolean ongoing) {
        channels(c);
        Intent open = confirm == null ? new Intent(c, MainActivity.class) : new Intent(confirm);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent action = PendingIntent.getActivity(c, confirm == null ? 0 : 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, "installs").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Craft Hub").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(action).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing);
        if (ongoing && confirm == null) b.setProgress(100, Math.max(percent, 0), percent < 0);
        if (ongoing) {
            PendingIntent cancel = PendingIntent.getService(c, 2, new Intent(c, InstallService.class).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Zrušit", cancel).build());
        }
        return b.build();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        cancelled = true;
        abandon();
        finish("Android ukončil dlouhou instalaci. Zbytek fronty lze znovu spustit.", true);
    }
    @Override public void onDestroy() {
        cancelled = true;
        worker.shutdownNow();
        if (live == this) { live = null; changed(); }
        super.onDestroy();
    }
}
