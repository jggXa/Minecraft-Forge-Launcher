package com.mclauncher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VersionInstaller {
    private static final Gson GSON = new Gson();
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES_URL = "https://resources.download.minecraft.net/";
    private static final String INSTALLING_FLAG = ".installing";

    private static volatile int concurrency = 4;

    public static void setConcurrency(int n) {
        if (n >= 1 && n <= 16) concurrency = n;
    }

    public static int getConcurrency() {
        return concurrency;
    }

    public static void markInstalling(Path versionDir) {
        try {
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve(INSTALLING_FLAG),
                    String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    public static void unmarkInstalling(Path versionDir) {
        try { Files.deleteIfExists(versionDir.resolve(INSTALLING_FLAG)); }
        catch (Exception ignored) {}
    }

    public static boolean isInstalling(Path versionDir) {
        return Files.exists(versionDir.resolve(INSTALLING_FLAG));
    }

    public static List<VersionManifest.VersionEntry> fetchVersions() throws Exception {
        VersionManifest manifest = GSON.fromJson(
                DownloadUtil.downloadString(MANIFEST_URL), VersionManifest.class);
        if (manifest == null || manifest.versions == null) return List.of();
        return manifest.versions;
    }

    public static void install(String versionId, Path gameDir, ProgressCallback cb, FileProgressListener fileListener) throws Exception {
        cb.update("获取版本清单", 2);
        VersionManifest manifest = GSON.fromJson(
                DownloadUtil.downloadString(MANIFEST_URL), VersionManifest.class);
        VersionManifest.VersionEntry entry = manifest.versions.stream()
                .filter(v -> v.id.equals(versionId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("版本不存在: " + versionId));

        cb.update("下载版本元数据", 5);
        VersionManifest.VersionDetail detail = GSON.fromJson(
                DownloadUtil.downloadString(entry.url), VersionManifest.VersionDetail.class);

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);
        markInstalling(versionDir);

        boolean success = false;
        try {
            Files.writeString(versionDir.resolve(versionId + ".json"), GSON.toJson(detail));

            if (detail.downloads == null || detail.downloads.client == null)
                throw new RuntimeException("元数据缺少 downloads.client");
            cb.update("下载客户端 jar", 10);
            DownloadUtil.downloadFile(
                    detail.downloads.client.url,
                    versionDir.resolve(versionId + ".jar"),
                    detail.downloads.client.sha1,
                    fileListener);

            downloadLibraries(detail.libraries, gameDir, cb, fileListener);
            downloadAssets(detail.assetIndex, gameDir, cb, fileListener);
            cb.update("解压原生库", 99);
            extractNatives(detail.libraries, versionId, gameDir, fileListener);
            cb.update("原版安装完成", 100);

            success = true;
        } finally {
            if (success) unmarkInstalling(versionDir);
        }
    }

    private static void downloadLibraries(List<VersionManifest.Library> libs,
                                          Path gameDir, ProgressCallback cb, FileProgressListener fileListener) throws Exception {
        if (libs == null) return;
        Path libRoot = gameDir.resolve("libraries");
        String osKey = currentOs();

        List<Runnable> tasks = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int total = 0;

        for (VersionManifest.Library lib : libs) {
            if (!isAllowed(lib) || lib.downloads == null) continue;

            // 普通库
            if (lib.downloads.artifact != null) {
                final VersionManifest.Artifact a = lib.downloads.artifact;
                if (a.path != null && seen.add(a.path)) {
                    final Path target = libRoot.resolve(a.path);
                    total++;
                    tasks.add(() -> {
                        try {
                            DownloadUtil.downloadFile(a.url, target, a.sha1, fileListener);
                        } catch (Exception e) {
                            throw new RuntimeException("库文件下载失败: " + a.path, e);
                        }
                    });
                }
            }

            // 当前系统的 native 库：与其他库一起并发下载，避免卡在 99% 的串行下载
            if (lib.natives != null && lib.downloads.classifiers != null) {
                String classifier = lib.natives.get(osKey);
                if (classifier != null) {
                    VersionManifest.Artifact nativeArtifact = lib.downloads.classifiers.get(classifier);
                    if (nativeArtifact != null && nativeArtifact.path != null
                            && seen.add(nativeArtifact.path)) {
                        final VersionManifest.Artifact a = nativeArtifact;
                        final Path target = libRoot.resolve(a.path);
                        total++;
                        tasks.add(() -> {
                            try {
                                DownloadUtil.downloadFile(a.url, target, a.sha1, fileListener);
                            } catch (Exception e) {
                                throw new RuntimeException("native 库下载失败: " + a.path, e);
                            }
                        });
                    }
                }
            }
        }
        runConcurrent(tasks, "下载库文件", total, 20, 60, cb);
    }

    private static void downloadAssets(VersionManifest.AssetIndex index,
                                       Path gameDir, ProgressCallback cb, FileProgressListener fileListener) throws Exception {
        if (index == null) return;
        Path indexDir = gameDir.resolve("assets").resolve("indexes");
        Files.createDirectories(indexDir);
        Path indexPath = indexDir.resolve(index.id + ".json");
        DownloadUtil.downloadFile(index.url, indexPath, index.sha1, fileListener);

        JsonObject root = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        JsonObject objects = root.getAsJsonObject("objects");
        if (objects == null) return;
        Path objRoot = gameDir.resolve("assets").resolve("objects");

        List<Runnable> tasks = new ArrayList<>();
        int total = 0;
        for (var e : objects.entrySet()) {
            String hash = e.getValue().getAsJsonObject().get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            Path target = objRoot.resolve(prefix).resolve(hash);
            final String url = RESOURCES_URL + prefix + "/" + hash;
            final String sha = hash;
            total++;
            tasks.add(() -> {
                try {
                    DownloadUtil.downloadFile(url, target, sha, fileListener);
                } catch (Exception ex) {
                    throw new RuntimeException("资源下载失败: " + sha, ex);
                }
            });
        }
        runConcurrent(tasks, "下载资源", total, 81, 17, cb);
    }

    private static void runConcurrent(List<Runnable> tasks, String label, int total,
                                      int basePct, int spanPct, ProgressCallback cb) throws Exception {
        if (tasks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger done = new AtomicInteger();

        // 阶段开始时先发一次，让 UI 立刻显示剩余文件数
        cb.updateFiles(label + " 0/" + total, basePct, 0, total);

        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Runnable t : tasks) {
                futures.add(pool.submit(() -> {
                    t.run();
                    int d = done.incrementAndGet();
                    // 每 10 个文件或最后 1 个更新一次，避免 UI 压力
                    if (d % 10 == 0 || d == total) {
                        int percent = basePct + d * spanPct / Math.max(total, 1);
                        cb.updateFiles(label + " " + d + "/" + total, percent, d, total);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException(cause.getMessage(), cause);
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void extractNatives(List<VersionManifest.Library> libs,
                                       String versionId, Path gameDir, FileProgressListener fileListener) throws Exception {
        if (libs == null) return;
        Path nativesDir = gameDir.resolve("versions").resolve(versionId).resolve("natives");
        Files.createDirectories(nativesDir);
        String osKey = currentOs();
        for (VersionManifest.Library lib : libs) {
            if (lib.natives == null || lib.downloads == null || lib.downloads.classifiers == null) continue;
            String classifier = lib.natives.get(osKey);
            if (classifier == null) continue;
            VersionManifest.Artifact a = lib.downloads.classifiers.get(classifier);
            if (a == null) continue;
            Path jar = gameDir.resolve("libraries").resolve(a.path);
            if (!Files.exists(jar)) DownloadUtil.downloadFile(a.url, jar, a.sha1, fileListener);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (lib.extract != null && lib.extract.exclude != null) {
                        boolean excluded = false;
                        for (String ex : lib.extract.exclude) {
                            if (name.startsWith(ex)) { excluded = true; break; }
                        }
                        if (excluded) continue;
                    }
                    Path out = nativesDir.resolve(name).normalize();
                    if (!out.startsWith(nativesDir)) continue;
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static boolean isAllowed(VersionManifest.Library lib) {
        if (lib.rules == null || lib.rules.isEmpty()) return true;
        boolean allowed = false;
        String os = currentOs();
        String arch = currentArch();
        String osVersion = System.getProperty("os.version", "");
        for (VersionManifest.Rule r : lib.rules) {
            boolean match = true;
            if (r.os != null) {
                if (r.os.name != null && !r.os.name.equals(os)) match = false;
                if (r.os.arch != null && !r.os.arch.equals(arch)) match = false;
                if (r.os.version != null) {
                    try {
                        if (!Pattern.compile(r.os.version).matcher(osVersion).find()) match = false;
                    } catch (Exception ignored) {}
                }
            }
            if (r.features != null) {
                for (var e : r.features.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) match = false;
                }
            }
            if (match) allowed = "allow".equals(r.action);
        }
        return allowed;
    }

    public static String currentOs() {
        String n = System.getProperty("os.name").toLowerCase();
        if (n.contains("win")) return "windows";
        if (n.contains("mac")) return "osx";
        return "linux";
    }

    public static String currentArch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return switch (a) {
            case "amd64", "x86_64", "x86-64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> a;
        };
    }
}