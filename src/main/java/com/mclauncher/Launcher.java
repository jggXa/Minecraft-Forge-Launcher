package com.mclauncher;

import com.google.gson.Gson;

import java.io.DataInputStream;
import java.io.IOException;
import java.awt.GraphicsEnvironment;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Launcher {
    private static final Gson GSON = new Gson();
    private static final Pattern MC_VER_PATTERN = Pattern.compile("(1\\.[0-9]+(?:\\.[0-9]+)?)");

    public static Process launch(String versionId, Path gameDir, Settings settings,
                                 Consumer<String> logSink) throws Exception {
        return launch(versionId, gameDir, settings, logSink, null);
    }

    public static Process launch(String versionId, Path gameDir, Settings settings,
                                 Consumer<String> logSink,
                                 IntConsumer progress) throws Exception {
        report(progress, 1);

        if (settings.optimizeMemoryBeforeLaunch) {
            try {
                long before = usedMemoryMb();
                System.gc();
                Thread.sleep(300);
                long after = usedMemoryMb();
                if (logSink != null) {
                    logSink.accept("[内存] 启动前已执行 GC 优化（占用 " + before + " MB → " + after + " MB）");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        report(progress, 3);
        VersionManifest.VersionDetail detail = loadMerged(gameDir, versionId, new HashSet<>());
        if (detail == null) throw new RuntimeException("Version file missing: " + versionId);
        report(progress, 6);

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Path clientJar = versionDir.resolve(versionId + ".jar");
        if (!Files.exists(clientJar)) {
            String parentId = detail.jar != null ? detail.jar : detail.inheritsFrom;
            if (parentId != null) {
                clientJar = gameDir.resolve("versions").resolve(parentId).resolve(parentId + ".jar");
            }
        }
        report(progress, 9);

        List<String> cp = new ArrayList<>();
        cp.add(clientJar.toString());
        Path libRoot = gameDir.resolve("libraries");
        if (detail.libraries != null) {
            for (VersionManifest.Library lib : detail.libraries) {
                if (!VersionInstaller.isAllowed(lib)) continue;
                Path libPath = resolveLibraryPath(libRoot, lib);
                if (libPath != null && Files.exists(libPath)) {
                    cp.add(libPath.toString());
                }
            }
        }
        report(progress, 12);

        String uuid;
        if (settings.uuid != null && !settings.uuid.isBlank()) {
            uuid = settings.uuid.trim();
        } else {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + settings.username).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        }

        String assetIndex = detail.assetIndex != null ? detail.assetIndex.id
                : (detail.assets != null ? detail.assets : "");
        Path nativesDir = resolveNativesDir(gameDir, versionId, detail);

        String gameDirArg;
        if (settings.versionIsolation) {
            Path iso = versionDir.resolve("game");
            Files.createDirectories(iso);
            gameDirArg = iso.toString();
        } else {
            gameDirArg = gameDir.toString();
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", settings.username);
        vars.put("version_name", versionId);
        vars.put("game_directory", gameDirArg);
        vars.put("assets_root", gameDir.resolve("assets").toString());
        vars.put("assets_index_name", assetIndex);
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("version_type", detail.type != null ? detail.type : "release");
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", BuildInfo.APP_NAME);
        vars.put("launcher_version", "1.0");
        vars.put("classpath", String.join(System.getProperty("path.separator"), cp));
        vars.put("classpath_separator", System.getProperty("path.separator"));
        vars.put("library_directory", gameDir.resolve("libraries").toString());
        vars.put("game_assets", gameDir.resolve("assets").toString());
        vars.put("user_properties", "{}");
        report(progress, 15);

        boolean hasJvmArgs = detail.arguments != null
                && detail.arguments.jvm != null
                && !detail.arguments.jvm.isEmpty();

        int requiredMajor = requiredJavaMajor(detail);
        report(progress, 18);

        List<String> cmd = new ArrayList<>();
        String javaExe = settings.resolveJava(requiredMajor);
        cmd.add(javaExe);
        report(progress, 21);

        cmd.add("-Xmx" + settings.maxMemory);
        cmd.add("-Dminecraft.launcher.brand=" + BuildInfo.APP_NAME);
        cmd.add("-Dminecraft.launcher.version=1.0");

        boolean nativesPathSet = false;
        if (hasJvmArgs) {
            for (Object o : detail.arguments.jvm) {
                for (String s : evalArg(o, vars)) {
                    cmd.add(s);
                    if (s.startsWith("-Djava.library.path=")) nativesPathSet = true;
                }
            }
        }
        if (!nativesPathSet) {
            cmd.add("-Djava.library.path=" + nativesDir);
        }

        cmd.add("-cp");
        cmd.add(vars.get("classpath"));
        cmd.add(detail.mainClass);

        if (detail.arguments != null && detail.arguments.game != null
                && !detail.arguments.game.isEmpty()) {
            for (Object o : detail.arguments.game) {
                cmd.addAll(evalArg(o, vars));
            }
        } else if (detail.minecraftArguments != null) {
            for (String s : detail.minecraftArguments.split(" ")) {
                String r = replace(s, vars);
                if (r != null && !r.isBlank()) cmd.add(r);
            }
        }

        report(progress, 24);
        Process process;
        report(progress, 27);
        try {
            process = new ProcessBuilder(cmd)
                    .directory(Path.of(gameDirArg).toFile())
                    .redirectErrorStream(true)
                    .start();
            report(progress, 30);
        } catch (java.io.IOException e) {
            throw new RuntimeException("无法启动 Java (" + javaExe
                    + ")，请检查「启动器设置 → 2」中的 Java 路径: " + e.getMessage(), e);
        }

        if (logSink != null) {
            Thread pump = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logSink.accept(line);
                        reportFromLog(progress, line);
                    }
                } catch (Exception ignored) {}
            });
            pump.setDaemon(true);
            pump.start();
        }

        return process;
    }

    // ==================== 服务器核心启动 ====================

    public static Process launchServer(String versionId, Path gameDir, Settings settings,
                                       Consumer<String> logSink) throws Exception {
        return launchServer(versionId, gameDir, settings, logSink, null);
    }

    public static Process launchServer(String versionId, Path gameDir, Settings settings,
                                       Consumer<String> logSink,
                                       IntConsumer progress) throws Exception {
        report(progress, 3);
        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Path serverJar = versionDir.resolve("server.jar");
        if (!Files.exists(serverJar)) {
            throw new RuntimeException("服务端核心文件不存在: " + serverJar);
        }

        // 1. 获取局域网 IP
        try {
            String localIp = InetAddress.getLocalHost().getHostAddress();
            logSink.accept("[服务端] 局域网 IP: " + localIp + " (仅供同一路由器的玩家连接)");
        } catch (Exception e) {
            logSink.accept("[服务端] 无法获取局域网 IP");
        }
        report(progress, 10);

        // 2. 异步获取公网 IP，不能阻塞服务端启动
        Thread publicIpThread = new Thread(() -> {
            try {
                String publicIp = DownloadUtil.downloadString("https://api.ipify.org").trim();
                if (logSink != null) {
                    logSink.accept("[服务端] 公网 IP: " + publicIp
                            + " (外网玩家连接需要路由器设置端口映射: 25565)");
                }
            } catch (Exception e) {
                if (logSink != null) logSink.accept("[服务端] 无法获取公网 IP (请检查网络或 API 限制)");
            }
        }, "Server-PublicIP");
        publicIpThread.setDaemon(true);
        publicIpThread.start();
        report(progress, 20);

        // 3. 动态检测 server.jar 需要的 Java 版本
        int requiredMajor = 21; // 默认值
        try {
            int detected = getJavaVersionFromJar(serverJar);
            if (detected > 0) requiredMajor = detected;
            logSink.accept("[服务端] server.jar 需要 Java " + requiredMajor
                    + (detected > 0 ? "（从 class 版本检测）" : "（无法检测，使用默认值）"));
        } catch (Exception e) {
            logSink.accept("[服务端] Java 版本检测失败，使用默认 Java 21: " + e.getMessage());
        }
        report(progress, 30);

        String javaExe = settings.resolveJava(requiredMajor);

        // 4. 校验实际 Java 版本，低于 requiredMajor 直接报错，避免 UnsupportedClassVersionError
        int actualMajor = readJavaMajor(javaExe);
        if (actualMajor > 0 && actualMajor < requiredMajor) {
            throw new RuntimeException(
                    "服务端需要 Java " + requiredMajor + "，但当前解析到的 Java 是 "
                            + actualMajor + "（" + javaExe + "）。\n"
                            + "请在「设置 → Java 环境」中把 Java " + requiredMajor
                            + " 的路径配置到对应的输入框，或安装 Java " + requiredMajor + " 后重新扫描。");
        }
        logSink.accept("[服务端] 使用 Java " + (actualMajor > 0 ? actualMajor : "?")
                + ": " + javaExe);
        report(progress, 40);

        // 5. 启动服务端
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Xmx" + settings.maxMemory);
        // 避免服务端日志乱码（Java 18+ 支持，旧版忽略）
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add("server.jar");
        if (GraphicsEnvironment.isHeadless()) {
            cmd.add("nogui");
            logSink.accept("[服务端] 当前为 headless 环境，使用 nogui 模式");
        } else {
            logSink.accept("[服务端] 已启用服务端 GUI 窗口（终端/日志页仍会同步输出）");
        }

        Process process;
        try {
            process = new ProcessBuilder(cmd)
                    .directory(versionDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            report(progress, 50);
        } catch (java.io.IOException e) {
            throw new RuntimeException("无法启动服务端 Java (" + javaExe + "): " + e.getMessage(), e);
        }

        if (logSink != null) {
            // 中文 Windows 下服务端输出可能是 GBK，尝试用系统默认编码读，减少乱码
            final Charset charset = resolveConsoleCharset();
            Thread pump = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logSink.accept("[服务端] " + line);
                        reportServerFromLog(progress, line);
                    }
                } catch (Exception ignored) {}
            });
            pump.setDaemon(true);
            pump.start();
        }

        return process;
    }

    // ========================================================

    /**
     * 读取 server.jar 中主类的 class 文件主版本号，换算成 Java 主版本。
     * 依次尝试 bundler Main、server Main、client Main；都找不到时返回 -1（调用方用默认值）。
     */
    private static int getJavaVersionFromJar(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            String[] candidates = {
                    "net/minecraft/bundler/Main.class",
                    "net/minecraft/server/Main.class",
                    "net/minecraft/client/main/Main.class"
            };
            for (String name : candidates) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) continue;
                try (DataInputStream dis = new DataInputStream(zip.getInputStream(entry))) {
                    int magic = dis.readInt();
                    if (magic != 0xCAFEBABE) continue;
                    dis.readUnsignedShort(); // minor version
                    int major = dis.readUnsignedShort(); // major version
                    return classVersionToJavaMajor(major);
                }
            }
        }
        return -1;
    }

    /** class 文件主版本号 → Java 主版本。 */
    private static int classVersionToJavaMajor(int classVersion) {
        if (classVersion >= 69) return 25;   // Java 25
        if (classVersion >= 65) return 21;   // Java 21
        if (classVersion >= 61) return 17;   // Java 17
        if (classVersion >= 55) return 11;   // Java 11
        if (classVersion >= 52) return 8;    // Java 8
        return 8;
    }

    /**
     * 执行 java -version 解析主版本号；失败返回 -1。
     * 用于启动前校验实际 Java 是否满足服务端要求。
     */
    private static int readJavaMajor(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-version")
                    .redirectErrorStream(true)
                    .start();
            String first = null;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (first == null && line.contains("version")) first = line;
                }
            }
            p.waitFor();
            if (first == null) return -1;
            int q1 = first.indexOf('"');
            int q2 = first.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return -1;
            String ver = first.substring(q1 + 1, q2);
            String[] parts = ver.split("\\.");
            int major = Integer.parseInt(parts[0]);
            if (major == 1 && parts.length > 1) major = Integer.parseInt(parts[1]);
            return major;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Charset resolveConsoleCharset() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                return Charset.forName(System.getProperty("sun.jnu.encoding", "GBK"));
            } catch (Exception ignored) {
                try {
                    return Charset.forName("GBK");
                } catch (Exception ignored2) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress == null) return;
        try {
            progress.accept(percent);
        } catch (RuntimeException ignored) {}
    }

    /** 服务端进程启动后，用日志阶段推进进度。 */
    private static void reportServerFromLog(IntConsumer progress, String line) {
        if (progress == null || line == null) return;
        String s = line.toLowerCase(Locale.ROOT);
        if (s.contains("starting minecraft server version")) report(progress, 60);
        else if (s.contains("loading properties")) report(progress, 65);
        else if (s.contains("preparing level")) report(progress, 75);
        else if (s.contains("preparing spawn area")) report(progress, 85);
        else if (s.contains("done (")) report(progress, 95);
        else if (s.contains("for help")) report(progress, 98);
    }

    /** 游戏进程启动后，用日志阶段把进度从 30% 继续推进到 98%。 */
    private static void reportFromLog(IntConsumer progress, String line) {
        if (progress == null || line == null) return;
        String s = line.toLowerCase(Locale.ROOT);
        if (s.contains("loading minecraft")) report(progress, 32);
        else if (s.contains("mappings not present")) report(progress, 36);
        else if (s.contains("datafixer bootstrap")) report(progress, 40);
        else if (s.contains("environment: environment")) report(progress, 44);
        else if (s.contains("setting user:")) report(progress, 48);
        else if (s.contains("backend library:")) report(progress, 54);
        else if (s.contains("opengl")) report(progress, 60);
        else if (s.contains("using graphics backend")) report(progress, 66);
        else if (s.contains("render thread")) report(progress, 72);
        else if (s.contains("created window")) report(progress, 84);
        else if (s.contains("reloading resourcemanager")) report(progress, 90);
        else if (s.contains("sound engine started")) report(progress, 95);
        else if (s.contains("openal initialized")) report(progress, 98);
    }

    /** Forge/原版库有 downloads.artifact；Fabric/Quilt 库只有 Maven name，需要按坐标拼路径。 */
    private static Path resolveLibraryPath(Path libRoot, VersionManifest.Library lib) {
        if (lib.downloads != null && lib.downloads.artifact != null
                && lib.downloads.artifact.path != null) {
            return libRoot.resolve(lib.downloads.artifact.path);
        }
        if (lib.name == null) return null;
        String[] p = lib.name.split(":");
        if (p.length < 3) return null;
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
        return libRoot.resolve(p[0].replace('.', '/')).resolve(p[1]).resolve(p[2]).resolve(file);
    }

    private static long usedMemoryMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    public static int requiredJavaMajor(String versionId, Path gameDir) throws Exception {
        VersionManifest.VersionDetail detail = loadMerged(gameDir, versionId, new HashSet<>());
        return requiredJavaMajor(detail);
    }

    public static int requiredJavaMajor(VersionManifest.VersionDetail detail) {
        if (detail == null) return 17;
        if (detail.javaVersion != null && detail.javaVersion.majorVersion > 0) {
            return detail.javaVersion.majorVersion;
        }
        String mcVer = extractMcVersion(detail.id);
        if (mcVer == null) mcVer = extractMcVersion(detail.jar);
        if (mcVer == null) mcVer = extractMcVersion(detail.inheritsFrom);
        return versionToJavaMajor(mcVer);
    }

    private static String extractMcVersion(String s) {
        if (s == null) return null;
        Matcher m = MC_VER_PATTERN.matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    private static int versionToJavaMajor(String mcVer) {
        if (mcVer == null) return 17;
        try {
            String[] p = mcVer.split("\\.");
            int minor = Integer.parseInt(p[1]);
            int patch = p.length >= 3 ? Integer.parseInt(p[2]) : 0;
            if (minor >= 22) return 25;
            if (minor >= 21) return 21;
            if (minor == 20 && patch >= 5) return 21;
            if (minor >= 18) return 17;
            if (minor == 17) return 17;
            return 8;
        } catch (Exception e) {
            return 17;
        }
    }

    private static Path resolveNativesDir(Path gameDir, String versionId,
                                          VersionManifest.VersionDetail detail) {
        Set<String> visited = new HashSet<>();
        String current = versionId;
        VersionManifest.VersionDetail d = detail;
        while (current != null && visited.add(current)) {
            Path cand = gameDir.resolve("versions").resolve(current).resolve("natives");
            if (Files.isDirectory(cand) && hasFiles(cand)) return cand;
            current = d != null ? d.inheritsFrom : null;
            if (current != null) {
                try {
                    d = loadMerged(gameDir, current, new HashSet<>());
                } catch (Exception e) {
                    d = null;
                }
            }
        }
        return gameDir.resolve("versions").resolve(versionId).resolve("natives");
    }

    private static boolean hasFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> evalArg(Object o, Map<String, String> vars) {
        List<String> result = new ArrayList<>();
        if (o instanceof String s) {
            String r = replace(s, vars);
            if (r != null && !r.isBlank()) result.add(r);
            return result;
        }
        if (o instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) m;
            if (map.get("rules") instanceof List<?> rules && !rulesAllow(rules)) return result;
            Object value = map.get("value");
            if (value instanceof String s) {
                String r = replace(s, vars);
                if (r != null && !r.isBlank()) result.add(r);
            } else if (value instanceof List<?> list) {
                for (Object v : list) {
                    String r = replace(v.toString(), vars);
                    if (r != null && !r.isBlank()) result.add(r);
                }
            }
        }
        return result;
    }

    private static boolean rulesAllow(List<?> rules) {
        String os = VersionInstaller.currentOs();
        String arch = VersionInstaller.currentArch();
        String osVersion = System.getProperty("os.version", "");
        boolean allowed = false;
        for (Object r : rules) {
            if (!(r instanceof Map<?, ?> m)) continue;
            String action = String.valueOf(m.get("action"));
            boolean match = true;

            if (m.get("os") instanceof Map<?, ?> osMap) {
                Object n = osMap.get("name");
                if (n != null && !n.toString().equals(os)) match = false;
                Object a = osMap.get("arch");
                if (a != null && !a.toString().equals(arch)) match = false;
                Object v = osMap.get("version");
                if (v != null) {
                    try {
                        if (!Pattern.compile(v.toString()).matcher(osVersion).find()) match = false;
                    } catch (Exception ignored) {}
                }
            }

            if (m.get("features") instanceof Map<?, ?> features) {
                for (var e : features.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) match = false;
                }
            }

            if (match) allowed = "allow".equals(action);
        }
        return allowed;
    }

    private static String replace(String s, Map<String, String> vars) {
        String r = s;
        for (var e : vars.entrySet()) {
            r = r.replace("${" + e.getKey() + "}", e.getValue());
        }
        return r;
    }

    private static VersionManifest.VersionDetail loadMerged(
            Path gameDir, String id, Set<String> visited) throws Exception {
        Path json = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(json)) return null;
        VersionManifest.VersionDetail d = GSON.fromJson(
                Files.readString(json), VersionManifest.VersionDetail.class);

        if (id.equals(d.inheritsFrom)) {
            d.inheritsFrom = null;
        }

        if (d.inheritsFrom != null && !visited.contains(d.inheritsFrom)) {
            visited.add(id);
            VersionManifest.VersionDetail p = loadMerged(gameDir, d.inheritsFrom, visited);
            if (p != null) {
                if (d.mainClass == null) d.mainClass = p.mainClass;
                if (d.assets == null) d.assets = p.assets;
                if (d.assetIndex == null) d.assetIndex = p.assetIndex;
                if (d.downloads == null) d.downloads = p.downloads;
                if (d.javaVersion == null) d.javaVersion = p.javaVersion;
                if (d.type == null) d.type = p.type;
                if (d.jar == null) d.jar = p.jar;

                Map<String, VersionManifest.Library> mergedLibs = new LinkedHashMap<>();
                if (p.libraries != null) {
                    for (VersionManifest.Library l : p.libraries) {
                        mergedLibs.put(l.name != null ? l.name : ("lib@" + System.identityHashCode(l)), l);
                    }
                }
                if (d.libraries != null) {
                    for (VersionManifest.Library l : d.libraries) {
                        mergedLibs.put(l.name != null ? l.name : ("lib@" + System.identityHashCode(l)), l);
                    }
                }
                d.libraries = new ArrayList<>(mergedLibs.values());

                if (p.arguments != null || d.arguments != null) {
                    if (d.arguments == null) d.arguments = new VersionManifest.Arguments();
                    if (d.arguments.game == null) d.arguments.game = new ArrayList<>();
                    if (d.arguments.jvm == null) d.arguments.jvm = new ArrayList<>();
                }
                if (p.arguments != null) {
                    if (p.arguments.game != null) {
                        List<Object> g = new ArrayList<>(p.arguments.game);
                        g.addAll(d.arguments.game);
                        d.arguments.game = g;
                    }
                    if (p.arguments.jvm != null) {
                        List<Object> j = new ArrayList<>(p.arguments.jvm);
                        j.addAll(d.arguments.jvm);
                        d.arguments.jvm = j;
                    }
                }
                if (d.minecraftArguments == null) d.minecraftArguments = p.minecraftArguments;
            }
        }
        return d;
    }
}