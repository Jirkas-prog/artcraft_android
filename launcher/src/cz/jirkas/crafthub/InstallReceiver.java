package cz.jirkas.crafthub;

import android.app.NotificationManager;
import android.content.*;
import android.content.pm.PackageInstaller;

public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            if (Catalog.SELF.equals(State.prefs(context).getString("activePackage", ""))
                && Packages.version(context, Catalog.SELF) >= State.prefs(context).getLong("activeVersion", Long.MAX_VALUE)) {
                State.completed(context);
                State.prefs(context).edit().putString("message", "Craft Hub byl úspěšně aktualizován.").commit();
                context.getSystemService(NotificationManager.class).cancel(InstallService.NOTICE);
            }
            return;
        }
        if (!InstallService.RESULT.equals(intent.getAction())) return;
        int expected = State.prefs(context).getInt("session", -1);
        if (expected < 0 || expected != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2)) return;
        InstallService service = InstallService.live;
        if (service != null) { service.result(intent); return; }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) context.getSystemService(NotificationManager.class).notify(InstallService.NOTICE,
                InstallService.notification(context, "Potvrďte instalaci v Androidu.", -1, confirmation, false));
        } else {
            if (status == PackageInstaller.STATUS_SUCCESS) State.completed(context); else State.clearSession(context);
            State.prefs(context).edit().putString("message", status == PackageInstaller.STATUS_SUCCESS
                ? "Instalace dokončena. Zbývající aplikace lze dokončit tlačítkem Pokračovat."
                : "Instalace nedokončena. Zkuste znovu pokračovat ve frontě.").commit();
            InstallService.changed();
        }
    }
}
