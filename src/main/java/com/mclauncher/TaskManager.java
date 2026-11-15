package com.mclauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class TaskManager {
    private static final TaskManager INSTANCE = new TaskManager();
    public static TaskManager get() { return INSTANCE; }

    private final List<InstallTask> tasks = new CopyOnWriteArrayList<>();
    private Thread worker;
    private Settings settings;
    private Path gameDir;
    private Path configFile;
    private Consumer<String> logger;
    private volatile boolean shuttingDown = false;

    public void init(Settings settings, Path gameDir, Path configFile, Consumer<String> logger) {
        this.settings = settings;
        this.gameDir = gameDir;
        this.configFile = configFile;
        this.logger = logger;
    }

    public List<InstallTask> getTasks() { return tasks; }

    public boolean hasActive() {
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.QUEUED || t.state == InstallTask.State.RUNNING) return true;
        }
        return false;
    }

    public InstallTask getRunningTask() {
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.RUNNING) return t;
        }
        return null;
    }

    public InstallTask submit(String displayName, String typeLabel, String versionId,
                              String loaderType, String loaderVersion, String customName) {
        InstallTask task = new InstallTask(displayName, typeLabel, versionId,
                loaderType, loaderVersion, customName);
        tasks.add(task);
        startNextIfIdle();
        return task;
    }

    public InstallTask submitModDownload(String modName, String url,
                                         String instanceName, Path target) {
        InstallTask task = InstallTask.forModDownload(modName, url, instanceName, target);
        tasks.add(task);
        startNextIfIdle();
        return task;
    }

    public InstallTask submitServer(String displayName, String versionId,
                                    String serverUrl, String customName) {
        InstallTask task = InstallTask.forServerDownload(displayName, versionId, serverUrl, customName);
        tasks.add(task);
        startNextIfIdle();
        return task;
    }

    public void clearFinished() {
        tasks.removeIf(t -> t.state == InstallTask.State.DONE || t.state == InstallTask.State.FAILED);
    }

    public void cancelAll() {
        shuttingDown = true;
        DownloadUtil.cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.QUEUED
                    || t.state == InstallTask.State.RUNNING) {
                t.state = InstallTask.State.FAILED;
                t.status = "启动器已关闭";
            }
        }
        if (w != null) {
            try { w.join(8000); } catch (InterruptedException ignored) {}
        }
    }

    private synchronized void startNextIfIdle() {
        if (shuttingDown) return;
        if (worker != null && worker.isAlive()) return;
        InstallTask next = null;
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.QUEUED) { next = t; break; }
        }
        if (next == null) return;

        final InstallTask task = next;
        worker = new Thread(() -> runTask(task), "Install-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void runTask(InstallTask task) {
        task.state = InstallTask.State.RUNNING;
        task.currentFileName = "";
        task.remainingFiles = -1;

        if (task.kind == InstallTask.Kind.DOWNLOAD_MOD) {
            runModDownloadTask(task);
        } else if (task.kind == InstallTask.Kind.DOWNLOAD_SERVER) {
            runServerDownloadTask(task);
        } else {
            runMcInstallTask(task);
        }
    }

    // ==================== Minecraft 安装 ====================

    private void runMcInstallTask(InstallTask task) {
        task.status = "正在下载";
        if (logger != null) logger.accept("[任务开始] " + task.subtitle());

        FileProgressListener fileListener = new FileProgressListener() {
            @Override public void onStart(String url, long total) {
                task.filePercent = 0;
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
                task.currentFileName = name;
            }
            @Override public void onProgress(String url, long downloaded, long total) {
                if (total > 0) {
                    task.filePercent = (int) (downloaded * 100 / total);
                }
            }
            @Override public void onEnd(String url) {
                task.filePercent = 100;
            }
        };

        try {
            boolean hasLoader = task.loaderType != null;

            ProgressCallback cb = new ProgressCallback() {
                @Override public void update(String t, int p) {
                    task.totalPercent = hasLoader ? p * 80 / 100 : p;
                    if (task.totalPercent < 100) task.status = "正在下载";
                }
                @Override public void updateFiles(String t, int p, int done, int total) {
                    task.totalPercent = hasLoader ? p * 80 / 100 : p;
                    task.remainingFiles = Math.max(0, total - done);
                    if (task.totalPercent < 100) task.status = "正在下载";
                }
            };

            VersionInstaller.install(task.versionId, gameDir, cb, fileListener);

            String finalId = task.versionId;
            if (hasLoader) {
                String loaded = LoaderInstaller.install(task.loaderType, task.versionId,
                        task.loaderVersion, gameDir, settings, (t, p) -> {
                            task.totalPercent = 80 + p * 20 / 100;
                            task.status = "正在安装 " + task.loaderType;
                        }, fileListener);
                if (loaded != null) finalId = loaded;
            }

            task.finalVersionId = finalId;
            task.totalPercent = 100;
            task.filePercent = 100;
            task.remainingFiles = 0;
            task.state = InstallTask.State.DONE;
            task.status = "已完成";

            if (task.customName != null && !task.customName.isEmpty()) {
                settings.versionNames.put(finalId, task.customName);
                settings.save(configFile);
            }
            if (logger != null) logger.accept("[任务完成] " + task.subtitle() + " → " + finalId);
        } catch (Throwable e) {
            task.state = InstallTask.State.FAILED;
            task.status = "失败: " + e.getMessage();
            if (logger != null) {
                logger.accept("[任务失败] " + task.subtitle() + ": " + e.getMessage());
                try {
                    Path failedDir = gameDir.resolve("versions").resolve(task.versionId);
                    if (Files.exists(failedDir.resolve(".installing"))) {
                        logger.accept("[提示] 版本目录 " + failedDir
                                + " 仍保留 .installing 标记，主页不会显示该版本；"
                                + "删除该目录后重新安装即可。");
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            synchronized (this) { worker = null; }
            startNextIfIdle();
        }
    }

    // ==================== 模组下载 ====================

    private void runModDownloadTask(InstallTask task) {
        task.status = "正在下载";
        if (logger != null) logger.accept("[任务开始] 模组下载: " + task.displayName
                + " → " + task.modTarget);

        FileProgressListener fileListener = new FileProgressListener() {
            @Override public void onStart(String url, long total) {
                task.filePercent = 0;
                task.currentFileName = task.displayName;
            }
            @Override public void onProgress(String url, long downloaded, long total) {
                if (total > 0) {
                    int pct = (int) (downloaded * 100 / total);
                    task.filePercent = pct;
                    task.totalPercent = pct;
                }
            }
            @Override public void onEnd(String url) {
                task.filePercent = 100;
                task.totalPercent = 100;
            }
        };

        try {
            DownloadUtil.downloadFile(task.modUrl, task.modTarget, null, fileListener);
            task.state = InstallTask.State.DONE;
            task.status = "已完成";
            task.totalPercent = 100;
            task.filePercent = 100;
            if (logger != null) logger.accept("[任务完成] 模组下载: " + task.displayName);
        } catch (Throwable e) {
            task.state = InstallTask.State.FAILED;
            task.status = "失败: " + e.getMessage();
            if (logger != null) logger.accept("[任务失败] 模组下载: " + task.displayName
                    + ": " + e.getMessage());
        } finally {
            synchronized (this) { worker = null; }
            startNextIfIdle();
        }
    }

    // ==================== 服务端核心下载 ====================

    private void runServerDownloadTask(InstallTask task) {
        task.status = "正在下载服务端";
        if (logger != null) logger.accept("[任务开始] 服务端下载: " + task.displayName
                + " (" + task.versionId + ")");

        FileProgressListener fileListener = new FileProgressListener() {
            @Override public void onStart(String url, long total) {
                task.filePercent = 0;
                task.currentFileName = "server.jar";
            }
            @Override public void onProgress(String url, long downloaded, long total) {
                if (total > 0) {
                    int pct = (int) (downloaded * 100 / total);
                    task.filePercent = pct;
                    task.totalPercent = pct;
                }
            }
            @Override public void onEnd(String url) {
                task.filePercent = 100;
                task.totalPercent = 100;
            }
        };

        try {
            Path versionDir = gameDir.resolve("versions").resolve(task.versionId);
            Files.createDirectories(versionDir);
            Path serverJar = versionDir.resolve("server.jar");

            DownloadUtil.downloadFile(task.serverUrl, serverJar, null, fileListener);

            task.status = "正在写入 EULA";
            Files.writeString(versionDir.resolve("eula.txt"), "eula=true\n");

            task.status = "正在生成配置文件";
            Path props = versionDir.resolve("server.properties");
            if (!Files.exists(props)) {
                Files.writeString(props, "online-mode=false\nserver-port=25565\n");
            }
            if (logger != null) {
                logger.accept("[服务端] 默认配置: online-mode=false, server-port=25565；"
                        + "需要正版验证或改端口，请编辑 " + props);
            }

            String json = String.format(
                    "{\"id\":\"%s\",\"type\":\"server\",\"releaseTime\":\"%s\",\"serverCore\":true}",
                    task.versionId, java.time.Instant.now().toString()
            );
            Files.writeString(versionDir.resolve(task.versionId + ".json"), json);

            if (task.customName != null && !task.customName.isEmpty()) {
                settings.versionNames.put(task.versionId, task.customName);
                settings.save(configFile);
            }

            task.state = InstallTask.State.DONE;
            task.status = "已完成";
            task.totalPercent = 100;
            task.filePercent = 100;
            if (logger != null) logger.accept("[任务完成] 服务端下载并初始化完成: " + task.versionId);
        } catch (Throwable e) {
            task.state = InstallTask.State.FAILED;
            task.status = "失败: " + e.getMessage();
            if (logger != null) {
                logger.accept("[任务失败] 服务端: " + e.getMessage());
                try {
                    Path failedDir = gameDir.resolve("versions").resolve(task.versionId);
                    if (Files.exists(failedDir)) {
                        logger.accept("[提示] 服务端目录 " + failedDir
                                + " 可能残留未完成文件，可手动删除后重试。");
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            synchronized (this) { worker = null; }
            startNextIfIdle();
        }
    }
}