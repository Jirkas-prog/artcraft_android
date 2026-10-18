package cz.jirkas.crafthub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The public feed is data, never a source of arbitrary URLs or signing trust. */
public final class Catalog {
    public static final String SELF = "cz.jirkas.crafthub";
    public static final String DEFAULT_REPO = "Jirkas-prog/artcraft_android";
    public static final String SIGNER = "f28bb27cd24137bb5f89af5dc377c6f67dbf214e9b9f5125b7fb14af1bce536e";
    public static final int MAX_JSON = 512 * 1024;
    public static final long MAX_APK = 100L * 1024 * 1024;
    public final List<Entry> apps;

    private Catalog(List<Entry> apps) { this.apps = Collections.unmodifiableList(apps); }

    public static String repository(String input) {
        String repo = input.trim();
        if (repo.startsWith("https://github.com/")) repo = repo.substring(19);
        if (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
        if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        if (repo.length() > 180 || !repo.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}") || repo.contains(".."))
            throw new IllegalArgumentException("Zadejte veřejný GitHub repozitář ve tvaru vlastník/repozitář.");
        return repo;
    }

    public static String url(String repo, String path) {
        if (!path.equals("catalog.json") && (!path.matches("apk/[A-Za-z0-9][A-Za-z0-9_.-]*\\.apk") || path.contains("..")))
            throw new IllegalArgumentException("Neplatná cesta APK v katalogu.");
        return "https://raw.githubusercontent.com/" + repository(repo) + "/main/" + path;
    }

    public static boolean allowedDownload(URI uri) {
        return "https".equals(uri.getScheme()) && "raw.githubusercontent.com".equals(uri.getHost())
            && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null;
    }

    public static Catalog parse(String json) throws JSONException {
        if (json.length() > MAX_JSON) throw new JSONException("Katalog je příliš velký.");
        // Android's recursive JSON parser has no depth limit of its own.
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') { if (++depth > 12) throw new JSONException("Katalog je příliš hluboce vnořený."); }
            else if (ch == '}' || ch == ']') depth--;
        }
        JSONObject root;
        try { root = new JSONObject(json); }
        catch (StackOverflowError ex) {
            // Android also accepts nonstandard quoted literals; contain recursion even for those.
            throw new JSONException("Katalog překročil bezpečnou hloubku JSON.");
        }
        if (integer(root, "schemaVersion", 1) != 1) throw new JSONException("Nepodporovaný formát katalogu.");
        JSONArray rows = root.getJSONArray("apps");
        if (rows.length() < 1 || rows.length() > 64) throw new JSONException("Neplatný počet aplikací.");
        List<Entry> apps = new ArrayList<>();
        Set<String> ids = new HashSet<>(), paths = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Entry entry = new Entry(rows.getJSONObject(i));
            if (!ids.add(entry.packageId) || !paths.add(entry.apk)) throw new JSONException("Duplicitní aplikace nebo APK.");
            apps.add(entry);
        }
        return new Catalog(apps);
    }

    public Entry find(String id) {
        for (Entry entry : apps) if (entry.packageId.equals(id)) return entry;
        return null;
    }

    /** Self-update goes last: Android terminates the old process when replacing it. */
    public List<Entry> select(List<String> ids) {
        List<Entry> selected = new ArrayList<>();
        Entry self = null;
        for (String id : new java.util.LinkedHashSet<>(ids)) {
            Entry entry = find(id);
            if (entry == null) throw new IllegalArgumentException("Aplikace již není v katalogu: " + id);
            if (SELF.equals(id)) self = entry; else selected.add(entry);
        }
        if (self != null) selected.add(self);
        return selected;
    }

    private static String string(JSONObject o, String key, int max) throws JSONException {
        Object raw = o.get(key);
        if (!(raw instanceof String)) throw new JSONException("Neplatné pole " + key);
        String value = (String) raw;
        if (value.isEmpty() || value.length() > max || value.matches("(?s).*\\p{Cntrl}.*")) throw new JSONException("Neplatné pole " + key);
        return value;
    }

    private static long integer(JSONObject o, String key, long max) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new JSONException("Neplatné číslo " + key);
        long number = ((Number) value).longValue();
        if (number < 1 || number > max) throw new JSONException("Číslo mimo rozsah: " + key);
        return number;
    }

    public static final class Entry {
        public final String packageId, name, description, versionName, apk, sha256, signerSha256, abi;
        public final long versionCode, size;
        public final int minSdk;
        private Entry(JSONObject o) throws JSONException {
            packageId = string(o, "packageId", 120);
            if (!packageId.equals(SELF) && !packageId.matches("com\\.artcraft\\.[a-z][a-z0-9_]{0,60}")) throw new JSONException("Cizí identifikátor aplikace.");
            name = string(o, "name", 60);
            description = string(o, "description", 200);
            versionName = string(o, "versionName", 50);
            versionCode = integer(o, "versionCode", Integer.MAX_VALUE);
            size = integer(o, "size", MAX_APK);
            minSdk = (int) integer(o, "minSdk", 100);
            apk = string(o, "apk", 180);
            try { url(DEFAULT_REPO, apk); } catch (IllegalArgumentException ex) { throw new JSONException(ex.getMessage()); }
            sha256 = string(o, "sha256", 64);
            signerSha256 = string(o, "signerSha256", 64);
            if (!sha256.matches("[a-f0-9]{64}") || !SIGNER.equals(signerSha256)) throw new JSONException("Neznámý podpis nebo neplatný kontrolní součet.");
            abi = string(o, "abi", 20);
            if (!abi.equals("arm64-v8a") && !abi.equals("any")) throw new JSONException("Nepodporovaná architektura.");
        }
        public boolean needsInstall(long installedVersion) { return versionCode > installedVersion; }
    }
}
