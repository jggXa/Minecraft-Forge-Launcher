package com.mclauncher;

import java.nio.file.Path;

public class InstallTask {
    public enum State { QUEUED, RUNNING, DONE, FAILED }
    public enum Kind { INSTALL_MC, DOWNLOAD_MOD, DOWNLOAD_SERVER }

    public final Kind kind;
    public final String displayName;

    public final String typeLabel;
    public final String versionId;
    public final String loaderType;
    public final String loaderVersion;
    public final String customName;

    public final String modUrl;
    public final Path modTarget;
    public final String modInstanceName;

    public final String serverUrl;

    public volatile int filePercent = 0;
    public volatile int totalPercent = 0;
    public volatile String status = "正在排队";
    public volatile State state = State.QUEUED;
    public volatile String finalVersionId = null;
    public volatile String currentFileName = "";
    public volatile int remainingFiles = -1;

    /** MC 安装任务 */
    public InstallTask(String displayName, String typeLabel, String versionId,
                       String loaderType, String loaderVersion, String customName) {
        this.kind = Kind.INSTALL_MC;
        this.displayName = displayName;
        this.typeLabel = typeLabel;
        this.versionId = versionId;
        this.loaderType = loaderType;
        this.loaderVersion = loaderVersion;
        this.customName = customName;
        this.modUrl = null;
        this.modTarget = null;
        this.modInstanceName = null;
        this.serverUrl = null;
    }

    /** 模组下载任务 */
    private InstallTask(String modName, String modUrl, String modInstanceName, Path modTarget) {
        this.kind = Kind.DOWNLOAD_MOD;
        this.displayName = modName;
        this.typeLabel = null;
        this.versionId = null;
        this.loaderType = null;
        this.loaderVersion = null;
        this.customName = null;
        this.modUrl = modUrl;
        this.modTarget = modTarget;
        this.modInstanceName = modInstanceName;
        this.serverUrl = null;
    }

    /** 服务端核心下载任务。versionId 为独立目录名，例如 1.20.1-server。 */
    private InstallTask(String displayName, String versionId, String serverUrl, String customName) {
        this.kind = Kind.DOWNLOAD_SERVER;
        this.displayName = displayName;
        this.typeLabel = "服务器核心";
        this.versionId = versionId;
        this.loaderType = null;
        this.loaderVersion = null;
        this.customName = customName;
        this.modUrl = null;
        this.modTarget = null;
        this.modInstanceName = null;
        this.serverUrl = serverUrl;
    }

    public static InstallTask forModDownload(String modName, String url,
                                             String instanceName, Path target) {
        return new InstallTask(modName, url, instanceName, target);
    }

    public static InstallTask forServerDownload(String displayName, String versionId,
                                                String serverUrl, String customName) {
        return new InstallTask(displayName, versionId, serverUrl, customName);
    }

    public String subtitle() {
        if (kind == Kind.DOWNLOAD_MOD) {
            return "[模组] → " + (modInstanceName != null ? modInstanceName : "未知实例");
        }
        if (kind == Kind.DOWNLOAD_SERVER) {
            return "[服务端] " + versionId;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(typeLabel).append("]").append(versionId);
        if (loaderType != null) {
            sb.append("+[").append(loaderType).append("]").append(loaderVersion);
        }
        return sb.toString();
    }
}