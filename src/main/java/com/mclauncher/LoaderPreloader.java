package com.mclauncher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时预加载模组加载器（Forge / NeoForge / Fabric / Quilt）的版本元数据。
 * 某个源失败会换源重试，直到成功为止；预加载结果进入 {@link LoaderResolver} 缓存，
 * 下载页选择加载器时可以直接秒开。
 */
public final class LoaderPreloader {
    private static final String[] LOADERS = {"forge", "neoforge", "fabric", "quilt"};
    private static final long MAX_BACKOFF_MS = 30_000L;

    private LoaderPreloader() {}

    public static void start(MainWindow window) {
        Thread thread = new Thread(() -> run(window), "Loader-Preload");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run(MainWindow window) {
        List<String> versions = null;
        int attempt = 0;
        while (versions == null || versions.isEmpty()) {
            attempt++;
            try {
                versions = pickTargets(VersionInstaller.fetchVersions());
                if (versions.isEmpty()) throw new RuntimeException("版本清单为空");
            } catch (Exception e) {
                window.appendLog("[预加载] 获取版本清单失败，稍后换源重试（第 "
                        + attempt + " 次）: " + e.getMessage());
                sleep(backoff(attempt));
            }
        }

        window.appendLog("[预加载] 开始预加载模组加载器: " + String.join(", ", versions));
        for (String version : versions) {
            for (String loader : LOADERS) {
                preload(window, loader, version);
            }
        }
        window.appendLog("[预加载] 模组加载器元数据预加载完成");
    }

    /** 对单个加载器持续重试；每次重试内部都会遍历国内镜像 / 官方 / 备用源。 */
    private static void preload(MainWindow window, String loader, String mcVersion) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                List<LoaderResolver.LoaderVersion> versions =
                        LoaderResolver.list(loader, mcVersion);
                window.appendLog("[预加载] " + label(loader) + " / " + mcVersion
                        + " 成功，共 " + (versions == null ? 0 : versions.size()) + " 个版本");
                return;
            } catch (Exception e) {
                window.appendLog("[预加载] " + label(loader) + " / " + mcVersion
                        + " 失败，切换下载源重试（第 " + attempt + " 次）: " + e.getMessage());
                sleep(backoff(attempt));
            }
        }
    }

    /** 预加载最新正式版与最新快照；下载页默认选中最新版，能直接命中缓存。 */
    private static List<String> pickTargets(List<VersionManifest.VersionEntry> all) {
        Set<String> targets = new LinkedHashSet<>();
        String release = null;
        String snapshot = null;
        for (VersionManifest.VersionEntry v : all) {
            if (v == null || v.id == null || v.id.isBlank()) continue;
            if (release == null && "release".equals(v.type)) release = v.id;
            if (snapshot == null && "snapshot".equals(v.type)) snapshot = v.id;
            if (release != null && snapshot != null) break;
        }
        if (release != null) targets.add(release);
        if (snapshot != null) targets.add(snapshot);
        return new ArrayList<>(targets);
    }

    private static long backoff(int attempt) {
        // 2s, 4s, 8s, 16s, 30s（封顶）
        long ms = 2_000L * (1L << Math.min(Math.max(attempt - 1, 0), 4));
        return Math.min(ms, MAX_BACKOFF_MS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String label(String loader) {
        return switch (loader) {
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            default -> loader;
        };
    }
}
