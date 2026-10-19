package cz.jirkas.crafthub;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Net {
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    public interface Progress { void update(long done, long total) throws IOException; }

    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        HttpURLConnection connection = active;
        // disconnect may wait on the socket implementation; never do that on Android's UI thread.
        if (connection != null) new Thread(connection::disconnect, "craft-cancel-http").start();
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stažení přerušeno.");
    }

    private HttpURLConnection connect(String address, long offset) throws IOException {
        URI uri = URI.create(address);
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkCancelled();
            if (!Catalog.allowedDownload(uri)) throw new IOException("Stažení mimo zabezpečený GitHub zdroj bylo odmítnuto.");
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            active = c;
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Craft-Hub/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            int status;
            try { checkCancelled(); status = c.getResponseCode(); checkCancelled(); }
            catch (IOException ex) { c.disconnect(); throw ex; }
            if (status == 200 || (offset > 0 && status == 206)) return c;
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (status >= 300 && status <= 308 && location != null) {
                try { uri = uri.resolve(location); } catch (IllegalArgumentException ex) { throw new IOException("Neplatné přesměrování.", ex); }
            } else throw new IOException("GitHub odpověděl HTTP " + status + ". Zkontrolujte spojení a veřejnou větev main.");
        }
        throw new IOException("Příliš mnoho přesměrování.");
    }

    public String catalog(String repo) throws IOException {
        HttpURLConnection c = connect(Catalog.url(repo, "catalog.json") + "?t=" + System.currentTimeMillis(), 0);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                checkCancelled();
                if (out.size() + n > Catalog.MAX_JSON) throw new IOException("Katalog překročil povolenou velikost.");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); active = null; }
    }

    public File download(String repo, Catalog.Entry entry, File directory, Progress progress) throws IOException {
        // A cancelled service can still be unwinding. Never let it share a partial file with its successor.
        synchronized (Net.class) { return downloadLocked(repo, entry, directory, progress); }
    }

    static void clearDownloads(File directory) {
        synchronized (Net.class) {
            File[] files = directory.listFiles((dir, name) -> name.startsWith("craft-") && name.endsWith(".apk"));
            if (files != null) for (File file : files) file.delete();
        }
    }

    private File downloadLocked(String repo, Catalog.Entry entry, File directory, Progress progress) throws IOException {
        checkCancelled();
        File file = new File(directory, "craft-" + entry.sha256 + ".apk");
        File[] stale = directory.listFiles((dir, name) -> name.startsWith("craft-") && name.endsWith(".apk") && !name.equals(file.getName()));
        if (stale != null) for (File old : stale) old.delete();
        if (file.length() > entry.size && !file.delete()) throw new IOException("Nelze odstranit poškozenou mezipaměť APK.");
        long offset = file.length();
        if (directory.getUsableSpace() < entry.size * 3 - offset + 64L * 1024 * 1024) throw new IOException("Nedostatek volného místa pro stažení a instalaci.");
        HttpURLConnection c = null;
        boolean invalid = false;
        try {
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            if (offset > 0) {
                try (InputStream prefix = new FileInputStream(file)) {
                    int n;
                    while ((n = prefix.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, n); }
                }
            }
            progress.update(offset, entry.size);
            checkCancelled();
            if (offset == entry.size) {
                if (!hex(digest.digest()).equals(entry.sha256)) { invalid = true; throw new IOException("Poškozené APK v mezipaměti bylo odstraněno. Zkuste stažení znovu."); }
                return file;
            }
            c = connect(Catalog.url(repo, entry.apk) + "?sha=" + entry.sha256, offset);
            if (c.getResponseCode() == 206) {
                String expected = "bytes " + offset + "-" + (entry.size - 1) + "/" + entry.size;
                if (!expected.equals(c.getHeaderField("Content-Range"))) { invalid = true; throw new IOException("Server vrátil nesprávný rozsah APK."); }
            } else {
                // Servers may ignore Range. Replace the prefix rather than appending a second APK.
                offset = 0;
                digest.reset();
            }
            long declared = c.getContentLengthLong();
            String encoding = c.getHeaderField("Content-Encoding");
            if ((declared != -1 && declared != entry.size - offset) || (encoding != null && !encoding.equalsIgnoreCase("identity"))) {
                invalid = true; throw new IOException("Velikost nebo kódování APK nesouhlasí s katalogem. Obnovte katalog.");
            }
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file, offset > 0)) {
                long done = offset;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    checkCancelled();
                    progress.update(done, entry.size);
                    if (n > entry.size - done) { invalid = true; throw new IOException("APK překročilo uvedenou velikost."); }
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                }
                checkCancelled();
                if (done != entry.size) throw new EOFException("Přenos skončil předčasně. Tlačítkem Pokračovat navážete na staženou část.");
                if (!hex(digest.digest()).equals(entry.sha256)) { invalid = true; throw new IOException("Kontrolní součet APK nesouhlasí. Instalace byla zastavena."); }
                out.getFD().sync();
                progress.update(done, entry.size);
                checkCancelled();
            }
            return file;
        } catch (InterruptedIOException ex) {
            // SocketTimeoutException is a recoverable network failure, not a user cancellation.
            if (!(ex instanceof SocketTimeoutException)) invalid = true;
            throw ex;
        } finally {
            if (c != null) c.disconnect();
            active = null;
            if (invalid || cancelled || Thread.currentThread().isInterrupted()) file.delete();
        }
    }

    public static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 není dostupné.", ex); }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
