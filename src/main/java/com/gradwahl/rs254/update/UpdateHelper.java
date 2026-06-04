package com.gradwahl.rs254.update;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateHelper {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/2004sp/Progressive-Java-Client/releases/latest";
    private static final int MAX_REPLACE_ATTEMPTS = 60;
    private static final long RETRY_DELAY_MS = 500L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-helper");
        t.setDaemon(true);
        return t;
    });
    private final JLabel status = new JLabel("Click Check for updates.", SwingConstants.CENTER);
    private final JButton checkButton = new JButton("Check for updates");
    private final JButton applyButton = new JButton("Apply update");

    private JFrame frame;
    private UpdateInfo updateInfo;
    private Path clientFile;

    private UpdateHelper() {}

    public static void main(String[] args) {
        new UpdateHelper().run(args);
    }

    private void run(String[] args) {
        if (args.length == 0) {
            runStandaloneGui();
            return;
        }

        try {
            Request request = Request.parse(args);
            showApplyWindow();
            waitForClient(request.clientPid());
            replaceClient(request.download(), request.current());
            relaunch(request.current(), request.restartCommand());
            closeWindow();
        } catch (Exception e) {
            showError(e);
        }
    }

    private void runStandaloneGui() {
        try {
            showStandaloneWindow();
        } catch (Exception e) {
            showError(e);
        }
    }

    private void showStandaloneWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = baseFrame(WindowConstants.EXIT_ON_CLOSE);
            applyButton.setEnabled(false);
            checkButton.addActionListener(e -> checkForUpdates());
            applyButton.addActionListener(e -> applyStandaloneUpdate());

            JPanel buttons = new JPanel();
            buttons.add(checkButton);
            buttons.add(applyButton);
            frame.add(buttons, BorderLayout.SOUTH);
            frame.setVisible(true);
        });
    }

    private void showApplyWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = baseFrame(WindowConstants.DO_NOTHING_ON_CLOSE);
            status.setText("Preparing update...");
            frame.setVisible(true);
        });
    }

    private JFrame baseFrame(int closeOperation) {
        JFrame window = new JFrame("Progressive Java Client Updater");
        window.setDefaultCloseOperation(closeOperation);
        window.setLayout(new BorderLayout(10, 10));
        window.add(status, BorderLayout.CENTER);
        window.setPreferredSize(new Dimension(420, 130));
        window.pack();
        window.setLocationRelativeTo(null);
        return window;
    }

    private void checkForUpdates() {
        setBusy(true);
        setStatus("Checking GitHub releases...");
        executor.execute(() -> {
            try {
                clientFile = findClientFile();
                updateInfo = checkLatest(clientFile);
                if (updateInfo.updateAvailable()) {
                    setStatus("Current: " + updateInfo.currentVersion() + " | Latest: " + updateInfo.tagName());
                    SwingUtilities.invokeLater(() -> applyButton.setEnabled(true));
                } else {
                    setStatus("Up to date. Current: " + updateInfo.currentVersion() + " | Latest: " + updateInfo.tagName());
                    SwingUtilities.invokeLater(() -> applyButton.setEnabled(false));
                }
            } catch (Exception e) {
                updateInfo = null;
                SwingUtilities.invokeLater(() -> applyButton.setEnabled(false));
                showError(e);
            } finally {
                setBusy(false);
            }
        });
    }

    private void applyStandaloneUpdate() {
        UpdateInfo info = updateInfo;
        Path current = clientFile;
        if (info == null || current == null) {
            setStatus("Check for updates first.");
            return;
        }

        setBusy(true);
        setStatus("Downloading " + info.assetName() + "...");
        executor.execute(() -> {
            Path download = current.resolveSibling(current.getFileName() + ".download");
            try {
                download(info.assetUrl(), download);
                replaceClient(download, current);
                setStatus("Update applied.");
                relaunch(current, standaloneRestartCommand(current));
                closeWindow();
            } catch (Exception e) {
                showError(e);
            } finally {
                setBusy(false);
            }
        });
    }

    private void setBusy(boolean busy) {
        SwingUtilities.invokeLater(() -> {
            checkButton.setEnabled(!busy);
            applyButton.setEnabled(!busy && updateInfo != null && updateInfo.updateAvailable());
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private void waitForClient(long pid) throws Exception {
        setStatus("Waiting for the client to close...");
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isPresent() && handle.get().isAlive()) {
            handle.get().onExit().get();
        }
        Thread.sleep(500L);
    }

    private void replaceClient(Path download, Path current) throws Exception {
        setStatus("Installing update...");
        Exception last = null;
        for (int i = 0; i < MAX_REPLACE_ATTEMPTS; i++) {
            try {
                Files.move(download, current, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception e) {
                last = e;
                setStatus("Waiting for file lock... " + (i + 1) + "/" + MAX_REPLACE_ATTEMPTS);
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw new IllegalStateException("Could not replace the client. Close any remaining client windows and try again.", last);
    }

    private void relaunch(Path current, List<String> restartCommand) throws Exception {
        setStatus("Restarting client...");
        if (restartCommand.isEmpty()) {
            throw new IllegalArgumentException("Missing restart command");
        }
        new ProcessBuilder(restartCommand)
                .directory(current.getParent().toFile())
                .start();
    }

    private void closeWindow() throws Exception {
        setStatus("Done.");
        Thread.sleep(300L);
        SwingUtilities.invokeAndWait(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
        executor.shutdown();
    }

    private void showError(Exception e) {
        e.printStackTrace(System.err);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Runnable show = () -> JOptionPane.showMessageDialog(
                frame,
                message,
                "Update failed",
                JOptionPane.ERROR_MESSAGE
        );
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(show);
            } catch (Exception ignored) {
            }
        }
    }

    private static Path findClientFile() throws Exception {
        Path dir = new File(UpdateHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toPath()
                .getParent();
        Path exact = dir.resolve("Progressive-Java-Client.jar");
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("progressive-java-client")
                                && name.endsWith(".jar")
                                && !name.contains("updater")
                                && !name.endsWith(".download");
                    })
                    .max(Comparator.comparing(path -> path.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("Could not find Progressive-Java-Client.jar beside the updater."));
        }
    }

    private static UpdateInfo checkLatest(Path current) throws Exception {
        String json = httpGet(LATEST_RELEASE_API);
        String tag = jsonString(json, "tag_name");
        String publishedAt = jsonString(json, "published_at");
        List<ReleaseAsset> assets = parseAssets(json);
        ReleaseAsset asset = selectAsset(assets);
        if (tag == null || publishedAt == null || asset == null) {
            throw new IllegalStateException("Latest release has no usable JAR asset");
        }
        BuildInfo currentInfo = currentBuildInfo(current);
        String currentVersion = resolveCurrentVersion(current, currentInfo);
        boolean updateAvailable = isNewerThanCurrent(current, currentVersion, currentInfo, tag, publishedAt, asset.size());
        String label = (currentVersion != null && !currentVersion.isBlank()) ? currentVersion : versionLabel(currentInfo);
        return new UpdateInfo(tag, publishedAt, asset.name(), asset.url(), updateAvailable, label);
    }

    /** Prefers the version recorded in config.json beside the client, falling back to the jar manifest. */
    private static String resolveCurrentVersion(Path current, BuildInfo currentInfo) {
        String configVersion = configVersion(current);
        if (configVersion != null && !configVersion.isBlank()) {
            return configVersion;
        }
        return currentInfo.version();
    }

    private static String configVersion(Path current) {
        try {
            Path config = current.getParent().resolve("config.json");
            if (!Files.isRegularFile(config)) {
                return null;
            }
            return jsonString(Files.readString(config, StandardCharsets.UTF_8), "version");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isNewerThanCurrent(Path current, String currentVersion, BuildInfo currentInfo,
                                              String releaseTag, String publishedAt, long assetSize) {
        // When we know the current version (from config.json or the manifest), it is authoritative:
        // only update when the release tag is strictly newer. This stops re-downloading the same version.
        if (currentVersion != null && !currentVersion.isBlank()) {
            return compareVersions(releaseTag, currentVersion) > 0;
        }
        if (currentInfo.buildTime() != null) {
            try {
                return Instant.parse(publishedAt).isAfter(currentInfo.buildTime());
            } catch (Exception ignored) {
            }
        }
        try {
            if (assetSize > 0 && Files.size(current) != assetSize) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static String versionLabel(BuildInfo info) {
        if (info.version() != null && !info.version().isBlank()) {
            return info.version();
        }
        if (info.buildTime() != null) {
            return info.buildTime().toString();
        }
        return "unknown";
    }

    private static BuildInfo currentBuildInfo(Path current) {
        try (JarFile jar = new JarFile(current.toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            String version = attrs.getValue("Implementation-Version");
            String buildTime = attrs.getValue("Build-Time");
            Instant instant = null;
            if (buildTime != null && !buildTime.isBlank()) {
                instant = Instant.parse(buildTime);
            }
            return new BuildInfo(version, instant);
        } catch (Exception ignored) {
            return new BuildInfo(null, null);
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
        return assets.stream()
                .filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .min(Comparator.comparing(ReleaseAsset::name))
                .orElse(null);
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
                long size = latestLong(beforeUrl, "size");
                assets.add(new ReleaseAsset(unescapeJson(name), unescapeJson(urlMatcher.group(1)), size));
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

    private static long latestLong(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        long value = -1L;
        while (matcher.find()) {
            value = Long.parseLong(matcher.group(1));
        }
        return value;
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

    private static List<String> standaloneRestartCommand(Path current) {
        if (!current.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return List.of(current.toString());
        }
        List<String> command = new ArrayList<>();
        command.add(javawPath());
        command.add("-Xmx1g");
        command.add("-Dsun.java2d.noddraw=true");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang.reflect=ALL-UNNAMED");
        command.add("-jar");
        command.add(current.toString());
        command.add("10");
        command.add("0");
        command.add("highmem");
        command.add("members");
        command.add("32");
        return command;
    }

    private static String javawPath() {
        File javaw = new File(System.getProperty("java.home"), "bin" + File.separator + (isWindows() ? "javaw.exe" : "javaw"));
        if (javaw.isFile()) {
            return javaw.getPath();
        }
        File java = new File(System.getProperty("java.home"), "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        return java.isFile() ? java.getPath() : (isWindows() ? "javaw" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record Request(long clientPid, Path current, Path download, List<String> restartCommand) {
        private static Request parse(String[] args) {
            int separator = Arrays.asList(args).indexOf("--");
            if (separator < 3) {
                throw new IllegalArgumentException("Usage: updater <client-pid> <current-file> <download-file> -- <restart-command>");
            }

            long pid = Long.parseLong(args[0]);
            Path current = Path.of(args[1]).toAbsolutePath();
            Path download = Path.of(args[2]).toAbsolutePath();
            List<String> restart = new ArrayList<>();
            for (int i = separator + 1; i < args.length; i++) {
                restart.add(args[i]);
            }
            return new Request(pid, current, download, restart);
        }
    }

    private record UpdateInfo(String tagName, String publishedAt, String assetName,
                              String assetUrl, boolean updateAvailable, String currentVersion) {}
    private record ReleaseAsset(String name, String url, long size) {}
    private record BuildInfo(String version, Instant buildTime) {}
}
