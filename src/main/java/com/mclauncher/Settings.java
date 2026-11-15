package com.mclauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Settings {
    public String javaPath = "";
    public String javaPath25 = "";
    public String javaPath21 = "";
    public String javaPath17 = "";
    public String javaPath8 = "";

    public String maxMemory = "2G";

    /** 内存分配模式：true = 自动配置，false = 自定义 */
    public boolean autoMemory = true;
    /** 自定义内存（MB） */
    public int customMemoryMb = 8192;
    /** 启动游戏前进行内存优化 */
    public boolean optimizeMemoryBeforeLaunch = false;

    public boolean versionIsolation = false;
    public boolean darkMode = false;
    public String username = "Player";
    public String uuid = "";
    public String mirror = "OFFICIAL";
    public String loaderMirror = "MIRROR";

    public int bufferSizeKb = 64;
    public int downloadThreads = 4;

    public Map<String, String> versionNames =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 全局保存锁：worker 线程与 EDT 可能同时保存，避免写坏配置。 */
    private static final Object SAVE_LOCK = new Object();

    /** 最近一次保存失败的原因（成功后清空），用于界面提示。 */
    public static volatile String lastSaveError = "";

    public static Settings load(Path file) {
        Settings s = read(file);
        return s != null ? s : new Settings();
    }

    private static Settings read(Path file) {
        try {
            if (Files.exists(file)) {
                Settings s = GSON.fromJson(Files.readString(file), Settings.class);
                if (s != null) {
                    s.normalize();
                    return s;
                }
                backupCorruptFile(file, "解析结果为空");
            }
        } catch (Exception e) {
            backupCorruptFile(file, e.toString());
        }
        return null;
    }

    /** 配置损坏时备份原文件，避免用户配置被静默丢弃。 */
    private static void backupCorruptFile(Path file, String reason) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".bak-"
                    + System.currentTimeMillis());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("配置解析失败，已备份到 " + backup + "（" + reason + "）");
        } catch (Exception ignored) {}
    }

    private void normalize() {
        if (mirror == null) mirror = "OFFICIAL";
        if (loaderMirror == null) loaderMirror = "MIRROR";
        if (uuid == null) uuid = "";
        if (javaPath == null) javaPath = "";
        if (javaPath25 == null) javaPath25 = "";
        if (javaPath21 == null) javaPath21 = "";
        if (javaPath17 == null) javaPath17 = "";
        if (javaPath8 == null) javaPath8 = "";
        if (maxMemory == null || !maxMemory.toUpperCase().matches("\\d+[GM]")) maxMemory = "2G";
        if (customMemoryMb < 512) customMemoryMb = 8192;
        if (customMemoryMb > 32768) customMemoryMb = 32768;
        if (bufferSizeKb < 8 || bufferSizeKb > 1024) bufferSizeKb = 64;
        if (downloadThreads < 1 || downloadThreads > 16) downloadThreads = 4;
        if (versionNames == null) {
            versionNames = Collections.synchronizedMap(new LinkedHashMap<>());
        } else {
            // Gson 反序列化得到的是普通 LinkedHashMap，统一包装成同步 Map
            versionNames = Collections.synchronizedMap(new LinkedHashMap<>(versionNames));
        }
    }

    /** 保存配置到启动器目录；失败时返回 false，不再写备用位置。 */
    public boolean save(Path file) {
        synchronized (SAVE_LOCK) {
            lastSaveError = "";
            return write(file);
        }
    }

    private boolean write(Path file) {
        Path tmp = null;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            String json;
            synchronized (versionNames) {
                json = GSON.toJson(this);
            }
            tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            lastSaveError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("保存配置失败: " + file + " -> " + e);
            return false;
        }
    }

    public String javaPathFor(int requiredMajor) {
        if (requiredMajor >= 25) return nonBlank(javaPath25);
        if (requiredMajor >= 21) return nonBlank(javaPath21);
        if (requiredMajor >= 17) return nonBlank(javaPath17);
        return nonBlank(javaPath8);
    }

    public String resolveJava(int requiredMajor) {
        String p = javaPathFor(requiredMajor);
        if (!p.isBlank() && validJavaPath(p)) return p;
        if (javaPath != null && !javaPath.isBlank() && validJavaPath(javaPath)) return javaPath;
        String smart = findSuitableJava(requiredMajor);
        if (smart != null) return smart;
        return systemJava();
    }

    public String resolveJava() {
        if (javaPath != null && !javaPath.isBlank() && validJavaPath(javaPath)) return javaPath;
        return systemJava();
    }

    public String resolveJavaForInstaller() {
        if (javaPath != null && !javaPath.isBlank() && validJavaPath(javaPath)) return javaPath;
        if (!javaPath21.isBlank() && validJavaPath(javaPath21)) return javaPath21;
        if (!javaPath17.isBlank() && validJavaPath(javaPath17)) return javaPath17;
        if (!javaPath25.isBlank() && validJavaPath(javaPath25)) return javaPath25;
        if (!javaPath8.isBlank() && validJavaPath(javaPath8)) return javaPath8;
        return systemJava();
    }

    public List<String> allConfiguredJava() {
        List<String> list = new ArrayList<>();
        if (!javaPath25.isBlank()) list.add(javaPath25);
        if (!javaPath21.isBlank()) list.add(javaPath21);
        if (!javaPath17.isBlank()) list.add(javaPath17);
        if (!javaPath8.isBlank()) list.add(javaPath8);
        if (javaPath != null && !javaPath.isBlank()) list.add(javaPath);
        return list;
    }

    private static boolean validJavaPath(String p) {
        if (p == null || p.isBlank()) return false;
        if (!p.contains("/") && !p.contains("\\")) return true;
        try {
            return Files.exists(Path.of(p));
        } catch (Exception e) {
            return false;
        }
    }

    private static String javaExeName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private static String systemJava() {
        return javaExeName();
    }

    private static String findSuitableJava(int requiredMajor) {
        Set<String> candidates = new LinkedHashSet<>();
        String exeName = javaExeName();
        String os = System.getProperty("os.name").toLowerCase();

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path exe = Path.of(javaHome, "bin", exeName);
            if (Files.exists(exe)) candidates.add(exe.toString());
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String p : pathEnv.split(java.io.File.pathSeparator)) {
                if (p.isBlank()) continue;
                Path exe = Path.of(p, exeName);
                if (Files.exists(exe)) candidates.add(exe.toString());
            }
        }

        String[] searchDirs;
        if (os.contains("win")) {
            String userHome = System.getProperty("user.home", "");
            searchDirs = new String[]{
                    "C:\\Program Files\\Java",
                    "C:\\Program Files\\Eclipse Adoptium",
                    "C:\\Program Files\\AdoptOpenJDK",
                    "C:\\Program Files\\Microsoft",
                    "C:\\Program Files\\Amazon Corretto",
                    "C:\\Program Files\\Zulu",
                    "C:\\Program Files\\BellSoft",
                    userHome + "\\.jdks"};
        } else if (os.contains("mac")) {
            searchDirs = new String[]{
                    "/Library/Java/JavaVirtualMachines",
                    "/System/Library/Java/JavaVirtualMachines",
                    System.getProperty("user.home") + "/Library/Java/JavaVirtualMachines"};
        } else {
            searchDirs = new String[]{
                    "/usr/lib/jvm",
                    "/usr/java",
                    "/opt/java",
                    "/usr/local/java",
                    "/opt/jdk"};
        }

        for (String dir : searchDirs) {
            Path d = Path.of(dir);
            if (!Files.isDirectory(d)) continue;
            try (var stream = Files.newDirectoryStream(d)) {
                for (Path sub : stream) {
                    Path exe1 = sub.resolve("bin").resolve(exeName);
                    if (Files.exists(exe1)) candidates.add(exe1.toString());
                    Path exe2 = sub.resolve("Contents").resolve("Home").resolve("bin").resolve(exeName);
                    if (Files.exists(exe2)) candidates.add(exe2.toString());
                }
            } catch (Exception ignored) {}
        }

        for (String exe : candidates) {
            int ver = readJavaMajor(exe);
            if (ver > 0 && ver >= requiredMajor) return exe;
        }
        return null;
    }

    private static int readJavaMajor(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-version")
                    .redirectErrorStream(true).start();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.contains("version")) {
                    String v = line.split("\"")[1];
                    String[] parts = v.split("\\.");
                    int major = Integer.parseInt(parts[0]);
                    if (major == 1 && parts.length > 1) {
                        major = Integer.parseInt(parts[1]);
                    }
                    return major;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static String nonBlank(String s) {
        return s == null ? "" : s;
    }
}