package com.gradwahl.rs254.update;

import com.gradwahl.rs254.Main;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClientUpdater {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/2004sp/Progressive-Java-Client/releases/latest";

    private ClientUpdater() {}

    public record UpdateInfo(String tagName, String publishedAt, String assetName,
                             String assetUrl, boolean updateAvailable) {}

    public static UpdateInfo checkLatest() throws Exception {
        String json = httpGet(LATEST_RELEASE_API);
        String tag = jsonString(json, "tag_name");
        String publishedAt = jsonString(json, "published_at");
        List<ReleaseAsset> assets = parseAssets(json);
        ReleaseAsset asset = selectAsset(assets);
        if (tag == null || publishedAt == null || asset == null) {
            throw new IllegalStateException("Latest release has no usable JAR/EXE asset");
        }
        return new UpdateInfo(tag, publishedAt, asset.name(), asset.url(),
                isNewerThanCurrent(tag, publishedAt));
    }

    public static void apply(UpdateInfo info) throws Exception {
        File current = currentBinary();
        if (current == null || !current.isFile()) {
            throw new IllegalStateException("Updater can only replace a packaged JAR or EXE");
        }

        Path dir = current.toPath().getParent();
        Path download = dir.resolve(current.getName() + ".download");
        Path script = dir.resolve(isWindows() ? "apply-update.bat" : "apply-update.sh");
        download(info.assetUrl(), download);
        writeApplyScript(script, download, current.toPath());

        ProcessBuilder pb = isWindows()
                ? new ProcessBuilder("cmd", "/c", "start", "\"\"", script.toString())
                : new ProcessBuilder("sh", script.toString());
        pb.directory(dir.toFile());
        pb.start();
        System.exit(0);
    }

    public static String currentVersionLabel() {
        BuildInfo info = currentBuildInfo();
        if (info.version() != null && !info.version().isBlank()) {
            return info.version();
        }
        return "dev";
    }

    private static boolean isNewerThanCurrent(String releaseTag, String publishedAt) {
        BuildInfo current = currentBuildInfo();
        if (current.version() != null) {
            int cmp = compareVersions(releaseTag, current.version());
            if (cmp != 0) return cmp > 0;
        }
        if (current.buildTime() != null) {
            try {
                return Instant.parse(publishedAt).isAfter(current.buildTime());
            } catch (Exception ignored) {
            }
        }
        return current.version() == null;
    }

    private static BuildInfo currentBuildInfo() {
        try {
            String version = Main.class.getPackage().getImplementationVersion();
            String buildTime = null;
            URL manifestUrl = Main.class.getResource("/META-INF/MANIFEST.MF");
            if (manifestUrl != null) {
                try (InputStream in = manifestUrl.openStream()) {
                    Attributes attrs = new Manifest(in).getMainAttributes();
                    buildTime = attrs.getValue("Build-Time");
                }
            }
            Instant instant = null;
            if (buildTime != null && !buildTime.isBlank()) {
                instant = Instant.parse(buildTime);
            }
            return new BuildInfo(version, instant);
        } catch (Exception ignored) {
            return new BuildInfo(null, null);
        }
    }

    private static File currentBinary() {
        try {
            File file = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String name = file.getName().toLowerCase(Locale.ROOT);
            return (file.isFile() && (name.endsWith(".jar") || name.endsWith(".exe"))) ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void download(String url, Path destination) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Progressive-Java-Client-Updater");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    private static void writeApplyScript(Path script, Path download, Path current) throws Exception {
        if (isWindows()) {
            String restart = current.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? "start \"\" javaw -Xmx1g -jar \"" + current + "\"\r\n"
                    : "start \"\" \"" + current + "\"\r\n";
            String body =
                    "@echo off\r\n" +
                    "timeout /t 2 /nobreak >nul\r\n" +
                    "move /y \"" + download + "\" \"" + current + "\"\r\n" +
                    restart +
                    "del \"%~f0\"\r\n";
            Files.writeString(script, body, StandardCharsets.UTF_8);
        } else {
            String restart = current.toString().endsWith(".jar")
                    ? "java -Xmx1g -jar \"" + current + "\" &\n"
                    : "\"" + current + "\" &\n";
            String body =
                    "#!/bin/sh\n" +
                    "sleep 2\n" +
                    "mv \"" + download + "\" \"" + current + "\"\n" +
                    restart +
                    "rm -- \"$0\"\n";
            Files.writeString(script, body, StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "Progressive-Java-Client-Updater");
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static ReleaseAsset selectAsset(List<ReleaseAsset> assets) {
        String wantedExt = ".jar";
        File current = currentBinary();
        if (current != null && current.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            wantedExt = ".exe";
        }
        String ext = wantedExt;
        return assets.stream()
                .filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(ext))
                .min(Comparator.comparing(ReleaseAsset::name))
                .orElseGet(() -> assets.stream()
                        .filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .findFirst()
                        .orElse(null));
    }

    private static List<ReleaseAsset> parseAssets(String json) {
        List<ReleaseAsset> assets = new ArrayList<>();
        Matcher assetsArray = Pattern.compile("\"assets\"\\s*:\\s*\\[(.*?)]\\s*,\\s*\"tarball_url\"", Pattern.DOTALL)
                .matcher(json);
        if (!assetsArray.find()) {
            return assets;
        }
        String body = assetsArray.group(1);
        Matcher urlMatcher = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        int searchFrom = 0;
        while (urlMatcher.find()) {
            String beforeUrl = body.substring(searchFrom, urlMatcher.start());
            Matcher nameMatcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(beforeUrl);
            String name = null;
            while (nameMatcher.find()) {
                name = nameMatcher.group(1);
            }
            if (name != null) {
                assets.add(new ReleaseAsset(unescapeJson(name), unescapeJson(urlMatcher.group(1))));
            }
            searchFrom = urlMatcher.end();
        }
        return assets;
    }

    private static String jsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? unescapeJson(matcher.group(1)) : null;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int compareVersions(String releaseTag, String currentVersion) {
        int[] release = versionParts(releaseTag);
        int[] current = versionParts(currentVersion);
        if (release.length == 0 || current.length == 0) return 0;
        int len = Math.max(release.length, current.length);
        for (int i = 0; i < len; i++) {
            int a = i < release.length ? release[i] : 0;
            int b = i < current.length ? current[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static int[] versionParts(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceFirst("^[^0-9]+", "");
        Matcher matcher = Pattern.compile("\\d+").matcher(normalized);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find() && parts.size() < 4) {
            parts.add(Integer.parseInt(matcher.group()));
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ReleaseAsset(String name, String url) {}
    private record BuildInfo(String version, Instant buildTime) {}
}
