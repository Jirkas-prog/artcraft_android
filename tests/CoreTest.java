package cz.jirkas.crafthub;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real catalog parser and downloader, including hostile response paths. */
public final class CoreTest {
    private static byte[] response = "verified apk".getBytes(StandardCharsets.UTF_8);
    private static long declared = -1;
    private static int status = 200;
    private static String redirect;
    private static boolean ranges, badRange, blockRead;
    private static int failAfter = -1, requests, bytesRead;
    private static String lastRange;
    private static CountDownLatch reading, disconnected;
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
        Response(URL url) { super(url); requests++; }
        public void disconnect() { if (disconnected != null) disconnected.countDown(); }
        public boolean usingProxy() { return false; }
        public void connect() {}
        private int offset() {
            lastRange = getRequestProperty("Range");
            return ranges && lastRange != null ? Integer.parseInt(lastRange.substring(6, lastRange.length() - 1)) : 0;
        }
        public int getResponseCode() { return status == 200 && offset() > 0 ? 206 : status; }
        public String getHeaderField(String name) {
            if ("Location".equals(name)) return redirect;
            if ("Content-Range".equals(name)) return badRange ? "bytes 0-0/1" : "bytes " + offset() + "-" + (response.length - 1) + "/" + response.length;
            return null;
        }
        public long getContentLengthLong() { return declared; }
        public InputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(response, offset(), response.length - offset());
            return new InputStream() {
                int delivered;
                public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
                public int read(byte[] b, int off, int len) throws IOException {
                    if (blockRead) {
                        reading.countDown();
                        try { if (!disconnected.await(3, TimeUnit.SECONDS)) throw new IOException("disconnect did not release the request"); }
                        catch (InterruptedException ex) { throw new InterruptedIOException(); }
                        throw new IOException("socket closed");
                    }
                    if (failAfter >= 0 && delivered >= failAfter) throw new SocketTimeoutException("test network timeout");
                    int n = bytes.read(b, off, failAfter < 0 ? len : Math.min(len, failAfter - delivered));
                    if (n > 0) { delivered += n; bytesRead += n; }
                    return n;
                }
            };
        }
    }

    static File download(Catalog.Entry entry, File dir, Net.Progress progress) throws IOException {
        return new Net().download(Catalog.DEFAULT_REPO, entry, dir, progress);
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
            byte[] original = response.clone();
            File apk = download(e, dir, (n, total) -> {});
            check(Arrays.equals(response, Files.readAllBytes(apk.toPath()))); check(apk.delete());
            response[0] ^= 1;
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(Objects.requireNonNull(dir.list()).length == 0);
            response[0] ^= 1;
            response = Arrays.copyOf(response, response.length + 1);
            rejects(() -> download(e, dir, (n, total) -> {}));
            response = Arrays.copyOf(response, response.length - 2);
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(Objects.requireNonNull(dir.listFiles())[0].length() == original.length - 1);
            response = original.clone(); ranges = true; bytesRead = 0;
            apk = download(e, dir, (n, total) -> {});
            check(lastRange.equals("bytes=" + (original.length - 1) + "-"));
            check(bytesRead == 1 && Arrays.equals(original, Files.readAllBytes(apk.toPath())));
            int previousRequests = requests;
            check(download(e, dir, (n, total) -> {}).equals(apk) && requests == previousRequests);
            check(apk.delete());

            failAfter = 5;
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(Objects.requireNonNull(dir.listFiles())[0].length() == 5);
            failAfter = -1; bytesRead = 0;
            apk = download(e, dir, (n, total) -> {});
            check(lastRange.equals("bytes=5-") && bytesRead == original.length - 5);
            check(Arrays.equals(original, Files.readAllBytes(apk.toPath())) && apk.delete());

            // A server ignoring Range must replace the prefix, not append to it.
            failAfter = 4;
            rejects(() -> download(e, dir, (n, total) -> {}));
            failAfter = -1; ranges = false; bytesRead = 0;
            apk = download(e, dir, (n, total) -> {});
            check(lastRange.equals("bytes=4-") && bytesRead == original.length);
            check(Arrays.equals(original, Files.readAllBytes(apk.toPath())) && apk.delete());

            failAfter = 4;
            rejects(() -> download(e, dir, (n, total) -> {}));
            failAfter = -1; ranges = true; badRange = true;
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(Objects.requireNonNull(dir.list()).length == 0);
            badRange = false;
            // The saved prefix is part of the final hash, not implicitly trusted.
            failAfter = 4;
            rejects(() -> download(e, dir, (n, total) -> {}));
            File prefix = Objects.requireNonNull(dir.listFiles())[0];
            byte[] damaged = Files.readAllBytes(prefix.toPath()); damaged[0] ^= 1;
            Files.write(prefix.toPath(), damaged); failAfter = -1;
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(!prefix.exists());
            apk = download(e, dir, (n, total) -> {});
            damaged = Files.readAllBytes(apk.toPath()); damaged[0] ^= 1;
            Files.write(apk.toPath(), damaged);
            rejects(() -> download(e, dir, (n, total) -> {}));
            check(!apk.exists());
            declared = 999;
            rejects(() -> download(e, dir, (n, total) -> {}));
            declared = -1;
            status = 302; redirect = "https://evil.test/a.apk";
            rejects(() -> download(e, dir, (n, total) -> {}));
            status = 200;
            rejects(() -> download(e, dir, (n, total) -> { throw new InterruptedIOException("cancel"); }));
            check(Objects.requireNonNull(dir.list()).length == 0);

            // Cancel a genuinely blocked read and immediately begin a replacement download.
            Net blocked = new Net(); blockRead = true;
            reading = new CountDownLatch(1); disconnected = new CountDownLatch(1);
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread old = new Thread(() -> {
                try { blocked.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}); }
                catch (Exception ex) { failure.set(ex); }
            });
            old.start(); check(reading.await(2, TimeUnit.SECONDS));
            blockRead = false;
            AtomicReference<File> replacement = new AtomicReference<>();
            AtomicReference<Exception> replacementFailure = new AtomicReference<>();
            CountDownLatch replacing = new CountDownLatch(1);
            Thread next = new Thread(() -> {
                replacing.countDown();
                try { replacement.set(download(e, dir, (n, total) -> {})); }
                catch (Exception ex) { replacementFailure.set(ex); }
            });
            next.start(); check(replacing.await(2, TimeUnit.SECONDS));
            blocked.cancel(); blocked.cancel();
            old.join(2000); next.join(2000);
            check(!old.isAlive() && failure.get() instanceof IOException);
            check(!next.isAlive() && replacementFailure.get() == null);
            disconnected = null;
            apk = replacement.get();
            check(Arrays.equals(original, Files.readAllBytes(apk.toPath())) && apk.delete());
            rejects(() -> blocked.download(Catalog.DEFAULT_REPO, e, dir, (n, total) -> {}));

            File unrelated = new File(dir, "user-document.txt"); Files.write(unrelated.toPath(), original);
            Files.write(new File(dir, "craft-obsolete.apk").toPath(), original);
            apk = download(e, dir, (n, total) -> {});
            check(Objects.requireNonNull(dir.list()).length == 2 && unrelated.isFile());
            Net.clearDownloads(dir);
            check(!apk.exists() && unrelated.isFile());
            check(unrelated.delete());
        } finally { dir.delete(); }
        System.out.println(checks + " checks passed: catalog, signer, versions, range resume, restart fallback, corruption, cancellation and bounded cache.");
    }
}
