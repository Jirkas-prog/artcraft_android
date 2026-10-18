package cz.jirkas.crafthub;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Net {
    private Net() {}
    public interface Progress { void update(long done, long total) throws IOException; }

    private static HttpURLConnection connect(String address) throws IOException {
        URI uri = URI.create(address);
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!Catalog.allowedDownload(uri)) throw new IOException("Stažení mimo zabezpečený GitHub zdroj bylo odmítnuto.");
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Craft-Hub/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            int status;
            try { status = c.getResponseCode(); } catch (IOException ex) { c.disconnect(); throw ex; }
            if (status == 200) return c;
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (status >= 300 && status <= 308 && location != null) {
                try { uri = uri.resolve(location); } catch (IllegalArgumentException ex) { throw new IOException("Neplatné přesměrování.", ex); }
            } else throw new IOException("GitHub odpověděl HTTP " + status + ". Zkontrolujte spojení a veřejnou větev main.");
        }
        throw new IOException("Příliš mnoho přesměrování.");
    }

    public static String catalog(String repo) throws IOException {
        HttpURLConnection c = connect(Catalog.url(repo, "catalog.json") + "?t=" + System.currentTimeMillis());
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stažení přerušeno.");
                if (out.size() + n > Catalog.MAX_JSON) throw new IOException("Katalog překročil povolenou velikost.");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    public static File download(String repo, Catalog.Entry entry, File directory, Progress progress) throws IOException {
        if (directory.getUsableSpace() < entry.size * 3 + 64L * 1024 * 1024) throw new IOException("Nedostatek volného místa pro stažení a instalaci.");
        File file = File.createTempFile("craft-", ".apk", directory);
        HttpURLConnection c = null;
        boolean complete = false;
        try {
            c = connect(Catalog.url(repo, entry.apk) + "?sha=" + entry.sha256);
            long declared = c.getContentLengthLong();
            if (declared != -1 && declared != entry.size) throw new IOException("Velikost APK nesouhlasí s katalogem. Obnovte katalog.");
            MessageDigest digest = digest();
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    progress.update(done, entry.size);
                    if (n > entry.size - done) throw new IOException("APK překročilo uvedenou velikost.");
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                }
                if (done != entry.size || !hex(digest.digest()).equals(entry.sha256)) throw new IOException("Kontrolní součet APK nesouhlasí. Instalace byla zastavena.");
                out.getFD().sync();
                progress.update(done, entry.size);
            }
            complete = true;
            return file;
        } finally {
            if (c != null) c.disconnect();
            if (!complete) file.delete();
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
