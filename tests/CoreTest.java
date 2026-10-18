package cz.jirkas.crafthub;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs the real catalog parser and downloader, including hostile response paths. */
public final class CoreTest {
    private static byte[] response = "verified apk".getBytes(StandardCharsets.UTF_8);
    private static long declared = -1;
    private static int status = 200;
    private static String redirect;
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks + " failed"); }
    static void rejects(Checked action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (JSONException | IOException | IllegalArgumentException expected) { rejected = true; }
        check(rejected);
    }
    static JSONObject entry(String id, long version) throws Exception {
        return new JSONObject().put("packageId", id).put("name", "Test").put("description", "Test application")
            .put("versionCode", version).put("versionName", "1.0.0").put("size", response.length).put("minSdk", 29)
            .put("apk", "apk/" + id.replace('.', '-') + "-" + version + ".apk")
            .put("sha256", Net.hex(Net.digest().digest(response))).put("signerSha256", Catalog.SIGNER).put("abi", "any");
    }
    static Catalog catalog(JSONObject... entries) throws Exception {
        JSONArray apps = new JSONArray(); for (JSONObject e : entries) apps.put(e);
        return Catalog.parse(new JSONObject().put("schemaVersion", 1).put("apps", apps).toString());
    }
    static final class Response extends HttpURLConnection {
        Response(URL url) { super(url); }
        public void disconnect() {}
        public boolean usingProxy() { return false; }
        public void connect() {}
        public int getResponseCode() { return status; }
        public String getHeaderField(String name) { return "Location".equals(name) ? redirect : null; }
        public long getContentLengthLong() { return declared; }
        public InputStream getInputStream() { return new ByteArrayInputStream(response); }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Response(url); }
        } : null);
        check(Catalog.repository("https://github.com/Jirkas-prog/artcraft_android.git").equals(Catalog.DEFAULT_REPO));
        check(Catalog.repository("Jirkas-prog/artcraft_android/").equals(Catalog.DEFAULT_REPO));
        for (String repo : new String[]{"http://github.com/a/b", "https://github.com.evil.test/a/b", "a/../b", "a/b?token=secret", "a/b/main", "a/b#fragment", "a\\b"})
            rejects(() -> Catalog.repository(repo));
        check(Catalog.allowedDownload(URI.create(Catalog.url(Catalog.DEFAULT_REPO, "catalog.json"))));
        for (String address : new String[]{"http://raw.githubusercontent.com/a/b", "https://evil.test/a.apk", "https://user@raw.githubusercontent.com/a/b", "https://raw.githubusercontent.com:444/a/b", "file:///tmp/a.apk"})
            check(!Catalog.allowedDownload(URI.create(address)));
        for (String path : new String[]{"apk/../secret.apk", "apk/%2e%2e.apk", "https://evil.test/a.apk", "apk/a.apk?url=x", "apk/a.apk#fragment"})
            rejects(() -> Catalog.url(Catalog.DEFAULT_REPO, path));
        Catalog c = catalog(entry("com.artcraft.probe", 5), entry(Catalog.SELF, 10));
        check(c.apps.get(0).needsInstall(0)); check(c.apps.get(0).needsInstall(4));
        check(!c.apps.get(0).needsInstall(5)); check(!c.apps.get(0).needsInstall(6));
        List<Catalog.Entry> selected = c.select(Arrays.asList(Catalog.SELF, "com.artcraft.probe", Catalog.SELF));
        check(selected.size() == 2 && selected.get(1).packageId.equals(Catalog.SELF));
        rejects(() -> c.select(Collections.singletonList("com.artcraft.unknown")));
        rejects(() -> catalog(entry("com.artcraft.probe", 5), entry("com.artcraft.probe", 6)));
        rejects(() -> catalog(entry("com.attacker.app", 5)));
        rejects(() -> catalog(entry("com.artcraft.probe", 5).put("signerSha256", String.join("", Collections.nCopies(64, "0")))));
        rejects(() -> catalog(entry("com.artcraft.probe", 5).put("versionCode", 5.5)));
        rejects(() -> catalog(entry("com.artcraft.probe", 5).put("versionCode", "5")));
        rejects(() -> catalog(entry("com.artcraft.probe", 1L << 40)));
        rejects(() -> catalog(entry("com.artcraft.probe", 5).put("size", Catalog.MAX_APK + 1)));
        rejects(() -> catalog(entry("com.artcraft.probe", 5).put("name", "Fake\nName")));
        rejects(() -> Catalog.parse("{\"schemaVersion\":2,\"apps\":[]}"));
        rejects(() -> Catalog.parse("{\"unused\":" + String.join("", Collections.nCopies(1000, "[")) + "0"));
        check(catalog(entry("com.artcraft.probe", 5).put("description", "Literal brackets [{ and an escaped quote: \" are text.")).apps.size() == 1);
        rejects(() -> Catalog.parse("{\"unused\":'" + String.join("", Collections.nCopies(15000, "}")) + "',\"deep\":"
            + String.join("", Collections.nCopies(10000, "[")) + "0" + String.join("", Collections.nCopies(10000, "]")) + "}"));
        Catalog real = Catalog.parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        check(real.apps.size() >= 12);
        File dir = Files.createTempDirectory("craft-download-test-").toFile();
        try {
            Catalog.Entry e = c.apps.get(0);
            File apk = Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {});
            check(Arrays.equals(response, Files.readAllBytes(apk.toPath()))); check(apk.delete());
            response[0] ^= 1;
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));
            response[0] ^= 1;
            response = Arrays.copyOf(response, response.length + 1);
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));
            response = Arrays.copyOf(response, response.length - 2);
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));
            declared = 999;
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));
            declared = -1;
            status = 302; redirect = "https://evil.test/a.apk";
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));
            status = 200;
            rejects(() -> Net.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> { throw new InterruptedIOException("cancel"); }));
            check(Objects.requireNonNull(dir.list()).length == 0);
        } finally { dir.delete(); }
        System.out.println(checks + " checks passed: catalog, URL bounds, signer pinning, versions, queue order, download corruption and cancellation.");
    }
}
