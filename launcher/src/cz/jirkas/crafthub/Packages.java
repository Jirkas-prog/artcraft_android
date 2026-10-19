package cz.jirkas.crafthub;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

final class Packages {
    private Packages() {}
    static PackageInfo installed(Context context, String id) {
        try { return context.getPackageManager().getPackageInfo(id, PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException ex) { return null; }
    }
    static long version(Context c, String id) {
        try { return c.getPackageManager().getPackageInfo(id, 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException ex) { return 0; }
    }
    static boolean trusted(PackageInfo info) {
        if (info == null || info.signingInfo == null) return false;
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures.length != 1) return false;
        try { return Catalog.SIGNER.equals(Net.hex(Net.digest().digest(signatures[0].toByteArray()))); }
        catch (IOException ex) { return false; }
    }
    static String unavailable(Context c, Catalog.Entry e) {
        return unavailable(e, installed(c, e.packageId));
    }
    static String unavailable(Catalog.Entry e, PackageInfo installed) {
        if (e.minSdk > Build.VERSION.SDK_INT) return "Vyžaduje novější Android";
        if (e.abi.equals("arm64-v8a") && !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) return "Vyžaduje tablet ARM64";
        if (installed != null && !trusted(installed)) return "Nainstalovaná aplikace má jiný podpis";
        return null;
    }
    static void verify(Context c, Catalog.Entry e, File apk) throws IOException {
        PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !e.packageId.equals(info.packageName) || info.getLongVersionCode() != e.versionCode || !trusted(info))
            throw new IOException("APK má jiné jméno, verzi nebo podpis. Instalace byla odmítnuta.");
        String problem = unavailable(c, e);
        if (problem != null) throw new IOException(problem);
        if (info.applicationInfo == null || info.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("APK není kompatibilní s tímto Androidem.");
    }
}
