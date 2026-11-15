package com.mclauncher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class LoaderInstaller {
    private static final Gson GSON = new Gson();

    /** 预测安装器会创建的版本目录名（用于打"安装中"标记） */
    public static String predictedVersionId(String loader, String mcVersion, String loaderVersion) {
        return switch (loader) {
            case "fabric" -> "fabric-loader-" + loaderVersion + "-" + mcVersion;
            case "quilt" -> "quilt-loader-" + loaderVersion + "-" + mcVersion;
            case "forge" -> mcVersion + "-forge-" + loaderVersion;
            case "neoforge" -> "neoforge-" + loaderVersion;
            default -> null;
        };
    }

    public static String install(String loader, String mcVersion, String loaderVersion,
                                 Path gameDir, Settings settings, ProgressCallback cb, FileProgressListener fileListener) throws Exception {
        cb.update("下载 " + loader + " 安装器", 5);

        String url = switch (loader) {
            case "fabric" -> {
                // Fabric 安装器版本在 fabric-meta 的 installer 接口里，不在 maven-metadata.xml
                String rel = fabricInstallerVersion();
                yield "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + rel + "/fabric-installer-" + rel + ".jar";
            }
            case "quilt" -> {
                String officialMeta = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/maven-metadata.xml";
                String rel = null;
                Exception last = null;
                List<String> tried = new ArrayList<>();
                for (Mirror.LoaderType type : Mirror.loaderOrder()) {
                    String meta = Mirror.rewriteLoader(officialMeta, type);
                    if (tried.contains(meta)) continue;
                    tried.add(meta);
                    try {
                        rel = metaRelease(DownloadUtil.downloadStringFast(meta, 6, 1));
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (rel == null) throw new RuntimeException("无法获取 Quilt 安装器版本", last);
                yield "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/" + rel + "/quilt-installer-" + rel + ".jar";
            }
            case "forge" -> "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                    + mcVersion + "-" + loaderVersion
                    + "/forge-" + mcVersion + "-" + loaderVersion + "-installer.jar";
            case "neoforge" -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                    + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
            default -> throw new RuntimeException("未知加载器: " + loader);
        };

        // 安装器也按当前设置的优先级尝试三个源
        String officialUrl = url;
        List<String> downloadUrls = new ArrayList<>();
        for (Mirror.LoaderType type : Mirror.loaderOrder()) {
            String candidate = Mirror.rewriteLoader(officialUrl, type);
            if (!downloadUrls.contains(candidate)) downloadUrls.add(candidate);
        }

        if ((loader.equals("fabric") || loader.equals("quilt"))
                && Mirror.getLoader() != Mirror.LoaderType.OFFICIAL) {
            cb.update("提示: " + loader + " 安装器内部库下载不走镜像，失败时请切换官方源", 10);
        }

        // 每次用唯一文件名，避免上一次安装器残留/被占用导致 FileSystemException
        Path installerJar = gameDir.resolve(loader + "-installer-" + System.nanoTime() + ".jar");

        // 预测即将创建的版本目录，提前标记"安装中"
        String predictedId = predictedVersionId(loader, mcVersion, loaderVersion);
        Path predictedDir = predictedId != null
                ? gameDir.resolve("versions").resolve(predictedId) : null;

        // 上次可能已经装完，只是没来得及改名/去掉 .installing。
        // 先检查原生目录和“游戏版本-加载器”目录，完整就直接收尾，不重复下载。
        if (predictedDir != null && Files.isDirectory(predictedDir)
                && isVersionComplete(predictedDir, predictedId, gameDir)) {
            String target = finalizeVersion(gameDir, predictedId, loader, mcVersion, loaderVersion);
            cb.update("加载器安装完成", 100);
            return target;
        }
        String existingTargetId = mcVersion + "-" + loader;
        Path targetDir = gameDir.resolve("versions").resolve(existingTargetId);
        if (Files.isDirectory(targetDir) && isVersionComplete(targetDir, existingTargetId, gameDir)) {
            VersionInstaller.unmarkInstalling(targetDir);
            cb.update("加载器安装完成", 100);
            return existingTargetId;
        }

        if (predictedDir != null) VersionInstaller.markInstalling(predictedDir);

        boolean success = false;
        Path patchedJar = null;
        try {
            Exception downloadError = null;
            boolean downloaded = false;
            for (String downloadUrl : downloadUrls) {
                try {
                    DownloadUtil.downloadFile(downloadUrl, installerJar, null, fileListener);
                    downloaded = true;
                    break;
                } catch (Exception e) {
                    downloadError = e;
                }
            }
            if (!downloaded) throw downloadError != null ? downloadError
                    : new RuntimeException("安装器下载失败");

            if (loader.equals("forge") || loader.equals("neoforge")) {
                createLauncherProfiles(gameDir);
            }

            // Forge/NeoForge 安装器内部仍从 maven.minecraftforge.net、libraries.minecraft.net
            // 下载库，这些地址在部分网络下不可达。使用国内镜像时，改写安装器里的下载地址，
            // 并预先下载 install_profile 中列出的库，避免解压完卡在 Forge 安装阶段。
            Path runJar = installerJar;
            if ((loader.equals("forge") || loader.equals("neoforge"))
                    && Mirror.getLoader() == Mirror.LoaderType.MIRROR) {
                try {
                    cb.update("准备国内镜像安装", 35);
                    patchedJar = patchInstallerForMirror(installerJar, gameDir, fileListener);
                    runJar = patchedJar;
                } catch (Throwable ignored) {
                    runJar = installerJar;
                }
            }

            cb.update("运行 " + loader + " 安装器", 40);

            List<String> cmd = new ArrayList<>();
            cmd.add(settings.resolveJavaForInstaller());
            cmd.add("-jar");
            cmd.add(runJar.toString());

            switch (loader) {
                case "fabric" -> {
                    cmd.add("client");
                    cmd.add("-dir"); cmd.add(gameDir.toString());
                    cmd.add("-mcversion"); cmd.add(mcVersion);
                    cmd.add("-loader"); cmd.add(loaderVersion);
                }
                case "quilt" -> {
                    cmd.add("install");
                    cmd.add("client");
                    cmd.add(mcVersion);
                    cmd.add("--loader"); cmd.add(loaderVersion);
                    cmd.add("--install-dir=" + gameDir);
                }
                case "forge", "neoforge" -> {
                    cmd.add("--installClient");
                    cmd.add(gameDir.toString());
                }
                default -> {}
            }

            Process p = new ProcessBuilder(cmd)
                    .directory(gameDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder installerOut = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (installerOut) {
                            if (installerOut.length() < 64 * 1024) {
                                installerOut.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {}
            });
            drain.start();
            int exit = p.waitFor();
            drain.join(2000);

            if (exit != 0) {
                // 安装器有时已经把版本和库都装好，只是在 profile 等收尾步骤返回非 0。
                // 文件完整时按成功处理，避免完整目录永远卡在 .installing。
                if (predictedDir != null && isVersionComplete(predictedDir, predictedId, gameDir)) {
                    predictedDir = gameDir.resolve("versions").resolve(
                            finalizeVersion(gameDir, predictedId, loader, mcVersion, loaderVersion));
                    cb.update("加载器安装完成", 100);
                    success = true;
                    return predictedDir.getFileName().toString();
                }
                String tail;
                synchronized (installerOut) {
                    tail = tailOf(installerOut.toString(), 4000);
                }
                throw new RuntimeException(loader + " 安装器退出码: " + exit
                        + (tail.isBlank() ? "" : "\n安装器输出:\n" + tail));
            }

            predictedDir = gameDir.resolve("versions").resolve(
                    finalizeVersion(gameDir, predictedId, loader, mcVersion, loaderVersion));
            String targetId = predictedDir.getFileName().toString();
            cb.update("加载器安装完成", 100);
            success = true;
            return targetId;
        } finally {
            try { Files.deleteIfExists(installerJar); } catch (Exception ignored) {}
            if (patchedJar != null) {
                try { Files.deleteIfExists(patchedJar); } catch (Exception ignored) {}
            }
            // 只有成功才删除标记；失败时保留，主页不显示不完整版本
            if (success && predictedDir != null) {
                VersionInstaller.unmarkInstalling(predictedDir);
            }
        }
    }

    private static final String MIRROR_MAVEN = "https://bmclapi2.bangbang93.com/maven";
    private static final String MIRROR_ROOT = "https://bmclapi2.bangbang93.com";

    /**
     * 复制一份安装器并改写其中的下载地址，同时预取 install_profile 的库。
     * Forge/NeoForge 安装器默认从 maven.minecraftforge.net / libraries.minecraft.net 下载，
     * 这些地址在部分网络下不可达，会导致“解压完就没反应/报错”。
     */
    private static Path patchInstallerForMirror(Path installerJar, Path gameDir,
                                                FileProgressListener listener) throws Exception {
        try (ZipFile zin = new ZipFile(installerJar.toFile())) {
            ZipEntry profileEntry = zin.getEntry("install_profile.json");
            if (profileEntry != null) {
                String json;
                try (InputStream in = zin.getInputStream(profileEntry)) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                try {
                    prefetchProfileLibraries(JsonParser.parseString(json).getAsJsonObject(),
                            gameDir, listener);
                } catch (Throwable ignored) {}
            }
        }

        Path patched = installerJar.resolveSibling(
                "forge-installer-patched-" + System.nanoTime() + ".jar");
        try (ZipFile zin = new ZipFile(installerJar.toFile());
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(patched))) {
            Enumeration<? extends ZipEntry> entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // 改写后原签名文件会失效，必须去掉
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zin.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (name.equals("install_profile.json") || name.equals("version.json")) {
                    data = rewriteInstallerUrls(new String(data, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
        return patched;
    }

    private static String rewriteInstallerUrls(String text) {
        return text
                .replace("https://maven.minecraftforge.net", MIRROR_MAVEN)
                .replace("https://files.minecraftforge.net/maven", MIRROR_MAVEN)
                .replace("https://libraries.minecraft.net", MIRROR_MAVEN)
                .replace("https://maven.neoforged.net/releases", MIRROR_MAVEN)
                .replace("https://launchermeta.mojang.com", MIRROR_ROOT)
                .replace("https://piston-data.mojang.com", MIRROR_ROOT)
                .replace("https://resources.download.minecraft.net", MIRROR_ROOT + "/assets");
    }

    private static void prefetchProfileLibraries(JsonObject profile, Path gameDir,
                                                 FileProgressListener listener) {
        JsonArray libs = profile.getAsJsonArray("libraries");
        if (libs == null) return;
        // 只补缺失文件，并限制总耗时；剩余缺失项交给安装器自己下载
        long deadline = System.currentTimeMillis() + 90_000L;
        for (JsonElement el : libs) {
            if (System.currentTimeMillis() > deadline) break;
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("clientreq") && !lib.get("clientreq").isJsonNull()
                    && !lib.get("clientreq").getAsBoolean()) {
                continue;
            }
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) continue;
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            if (artifact == null || !artifact.has("path") || artifact.get("path").isJsonNull()) {
                continue;
            }
            String path = artifact.get("path").getAsString();
            String sha1 = artifact.has("sha1") && !artifact.get("sha1").isJsonNull()
                    ? artifact.get("sha1").getAsString() : null;
            String url = artifact.has("url") && !artifact.get("url").isJsonNull()
                    ? artifact.get("url").getAsString() : null;
            Path target = gameDir.resolve("libraries").resolve(path);
            if (Files.exists(target)) continue;
            downloadWithMirrors(target, path, sha1, url, listener);
        }
    }

    private static void downloadWithMirrors(Path target, String path, String sha1,
                                            String officialUrl, FileProgressListener listener) {
        List<String> urls = new ArrayList<>();
        urls.add(MIRROR_MAVEN + "/" + path);
        urls.add("https://mirrors.cernet.edu.cn/bmclapi/" + path);
        urls.add("https://mirrors.hit.edu.cn/bmclapi/" + path);
        if (officialUrl != null && !officialUrl.isBlank()) urls.add(officialUrl);
        for (String url : urls) {
            try {
                DownloadUtil.downloadFile(url, target, sha1, listener);
                return;
            } catch (Exception ignored) {
                // 继续尝试下一个镜像；全部失败时交给安装器自己处理
            }
        }
    }

    private static String finalizeVersion(Path gameDir, String nativeId,
                                          String loader, String mcVersion,
                                          String loaderVersion) throws Exception {
        String target = normalizeVersionDir(gameDir, nativeId, loader, mcVersion, loaderVersion);
        mergeBaseIntoVersion(gameDir, target);
        VersionInstaller.unmarkInstalling(gameDir.resolve("versions").resolve(target));
        return target;
    }

    /** 判断文件夹里的版本 JSON 和依赖库是否已经完整。 */
    private static boolean isVersionComplete(Path dir, String id, Path gameDir) {
        if (dir == null || id == null) return false;
        Path json = dir.resolve(id + ".json");
        if (!Files.exists(json)) return false;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()) {
                String base = obj.get("inheritsFrom").getAsString();
                Path baseJson = gameDir.resolve("versions").resolve(base).resolve(base + ".json");
                if (!Files.exists(baseJson)) return false;
            }
            JsonArray libs = obj.getAsJsonArray("libraries");
            if (libs != null) {
                for (var el : libs) {
                    if (!el.isJsonObject()) continue;
                    JsonObject lib = el.getAsJsonObject();
                    if (!lib.has("name")) continue;
                    String[] p = lib.get("name").getAsString().split(":");
                    if (p.length < 3) continue;
                    String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
                    Path jar = gameDir.resolve("libraries")
                            .resolve(p[0].replace('.', '/'))
                            .resolve(p[1]).resolve(p[2]).resolve(file);
                    if (!Files.exists(jar)) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把基础原版合并进加载器版本，形成一个自包含目录：
     * versions/26.2-fabric 同时拥有 26.2 的 jar、natives、libraries 和参数。
     * 合并成功后删除不再被引用的 versions/26.2，保证一个游戏只有一个一级目录。
     */
    public static void mergeBaseIntoVersion(Path gameDir, String targetId) throws Exception {
        Path versions = gameDir.resolve("versions");
        Path targetDir = versions.resolve(targetId);
        Path targetJson = targetDir.resolve(targetId + ".json");
        if (!Files.exists(targetJson)) return;

        JsonObject target = JsonParser.parseString(Files.readString(targetJson)).getAsJsonObject();
        if (!target.has("inheritsFrom") || target.get("inheritsFrom").isJsonNull()) return;
        String baseId = target.get("inheritsFrom").getAsString();
        if (baseId == null || baseId.isBlank() || baseId.equals(targetId)) return;

        Path baseDir = versions.resolve(baseId);
        Path baseJson = baseDir.resolve(baseId + ".json");
        if (!Files.exists(baseJson)) return;
        JsonObject base = JsonParser.parseString(Files.readString(baseJson)).getAsJsonObject();

        JsonObject merged = target.deepCopy();
        merged.addProperty("id", targetId);
        merged.remove("inheritsFrom");
        merged.addProperty("jar", targetId);
        for (String key : List.of("assets", "assetIndex", "downloads", "javaVersion",
                "type", "releaseTime", "time", "minecraftArguments")) {
            if (!merged.has(key) && base.has(key)) merged.add(key, base.get(key));
        }

        // 参数：基础原版在前，加载器追加在后
        JsonObject mergedArgs = merged.has("arguments")
                ? merged.getAsJsonObject("arguments") : new JsonObject();
        JsonObject baseArgs = base.has("arguments")
                ? base.getAsJsonObject("arguments") : new JsonObject();
        JsonArray gameArgs = new JsonArray();
        appendArray(baseArgs, "game", gameArgs);
        appendArray(mergedArgs, "game", gameArgs);
        JsonArray jvmArgs = new JsonArray();
        appendArray(baseArgs, "jvm", jvmArgs);
        appendArray(mergedArgs, "jvm", jvmArgs);
        if (gameArgs.size() > 0) mergedArgs.add("game", gameArgs);
        if (jvmArgs.size() > 0) mergedArgs.add("jvm", jvmArgs);
        merged.add("arguments", mergedArgs);

        // 库：基础在前，加载器覆盖同名库
        Map<String, JsonElement> libs = new LinkedHashMap<>();
        if (base.has("libraries")) {
            for (JsonElement el : base.getAsJsonArray("libraries")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                    libs.put(el.getAsJsonObject().get("name").getAsString(), el);
                }
            }
        }
        if (target.has("libraries")) {
            for (JsonElement el : target.getAsJsonArray("libraries")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                    libs.put(el.getAsJsonObject().get("name").getAsString(), el);
                }
            }
        }
        JsonArray mergedLibs = new JsonArray();
        for (JsonElement el : libs.values()) mergedLibs.add(el);
        merged.add("libraries", mergedLibs);

        // 复制基础 jar / natives 到加载器目录
        Path baseJar = baseDir.resolve(baseId + ".jar");
        Path targetJar = targetDir.resolve(targetId + ".jar");
        if (Files.exists(baseJar) && !Files.exists(targetJar)) {
            Files.copy(baseJar, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path baseNatives = baseDir.resolve("natives");
        if (Files.isDirectory(baseNatives)) {
            copyDir(baseNatives, targetDir.resolve("natives"));
        }

        Files.writeString(targetJson, GSON.toJson(merged));

        // 不直接删除基础版本，而是收到加载器目录的 base/ 下。
        // 这样 versions 一级目录只有一个“游戏版本-加载器”文件夹，也不会因误删丢文件。
        if (!hasOtherInheritor(versions, baseId, targetId)) {
            Path baseHome = targetDir.resolve("base");
            if (!Files.exists(baseHome)) {
                Files.move(baseDir, baseHome);
            }
        }
    }

    private static void appendArray(JsonObject obj, String key, JsonArray out) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return;
        for (JsonElement el : obj.getAsJsonArray(key)) out.add(el);
    }

    private static boolean hasOtherInheritor(Path versions, String baseId, String targetId) {
        try (var stream = Files.newDirectoryStream(versions, Files::isDirectory)) {
            for (Path dir : stream) {
                String id = dir.getFileName().toString();
                if (id.equals(targetId)) continue;
                Path json = dir.resolve(id + ".json");
                if (!Files.exists(json)) continue;
                try {
                    JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()
                            && baseId.equals(obj.get("inheritsFrom").getAsString())) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path out = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 把安装器生成的“原生目录名”改成“游戏版本-加载器”。
     * 例如：1.20.1-forge-47.4.28 → 1.20.1-forge，
     *       fabric-loader-0.19.5-26.2 → 26.2-fabric。
     */
    private static String normalizeVersionDir(Path gameDir, String nativeId,
                                              String loader, String mcVersion,
                                              String loaderVersion) throws Exception {
        String targetId = mcVersion + "-" + loader;
        if (nativeId == null || nativeId.isBlank()) return targetId;

        Path versions = gameDir.resolve("versions");
        Path nativeDir = versions.resolve(nativeId);
        Path targetDir = versions.resolve(targetId);

        if (!Files.isDirectory(nativeDir)) {
            // 安装器已经用了目标名，或目录不存在
            if (Files.isDirectory(targetDir)) {
                writeLoaderMeta(targetDir, targetId, loaderVersion);
                return targetId;
            }
            return nativeId;
        }

        if (nativeId.equals(targetId)) {
            writeLoaderMeta(nativeDir, targetId, loaderVersion);
            return targetId;
        }

        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        Files.move(nativeDir, targetDir);

        Path nativeJson = targetDir.resolve(nativeId + ".json");
        Path targetJson = targetDir.resolve(targetId + ".json");
        if (Files.exists(nativeJson) && !nativeJson.equals(targetJson)) {
            Files.move(nativeJson, targetJson);
        }
        writeLoaderMeta(targetDir, targetId, loaderVersion);

        Path nativeJar = targetDir.resolve(nativeId + ".jar");
        if (Files.exists(nativeJar)) {
            Files.move(nativeJar, targetDir.resolve(targetId + ".jar"));
        }
        return targetId;
    }

    private static void writeLoaderMeta(Path dir, String targetId, String loaderVersion) throws Exception {
        Path json = dir.resolve(targetId + ".json");
        if (!Files.exists(json)) return;
        JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        obj.addProperty("id", targetId);
        if (loaderVersion != null && !loaderVersion.isBlank()) {
            obj.addProperty("mcliLoaderVersion", loaderVersion);
        }
        Files.writeString(json, GSON.toJson(obj));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void createLauncherProfiles(Path gameDir) throws Exception {
        Path profilesFile = gameDir.resolve("launcher_profiles.json");
        if (Files.exists(profilesFile)) return;

        String json = """
                {
                  "profiles": {
                    "(Default)": {
                      "name": "(Default)",
                      "type": "latest-release",
                      "created": "2026-01-01T00:00:00.000Z",
                      "lastUsed": "2026-01-01T00:00:00.000Z",
                      "icon": "Grass",
                      "lastVersionId": "latest-release"
                    }
                  },
                  "selectedProfile": "(Default)",
                  "clientToken": "00000000000000000000000000000000",
                  "launcherVersion": { "name": "2.1.1", "format": 21 },
                  "settings": {
                    "enableSnapshots": false,
                    "enableAdvanced": false,
                    "keepLauncherOpen": false,
                    "showGameLog": false,
                    "showMenu": false,
                    "soundOn": false
                  },
                  "version": 3
                }
                """;
        Files.createDirectories(gameDir);
        Files.writeString(profilesFile, json);
    }

    private static String tailOf(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return "..." + s.substring(s.length() - maxLen);
    }

    private static String metaRelease(String xml) {
        int i = xml.lastIndexOf("<release>");
        if (i < 0) return "1.0.1";
        int e = xml.indexOf("</release>", i);
        return xml.substring(i + 9, e).trim();
    }

    /** Fabric 安装器版本在 fabric-meta 的 installer 接口中，优先 BMCLAPI。 */
    private static String fabricInstallerVersion() throws Exception {
        List<String> urls = new ArrayList<>();
        for (Mirror.LoaderType type : Mirror.loaderOrder()) {
            String url = type == Mirror.LoaderType.MIRROR
                    ? "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/installer"
                    : "https://meta.fabricmc.net/v2/versions/installer";
            if (!urls.contains(url)) urls.add(url);
        }
        Exception last = null;
        for (String url : urls) {
            try {
                String json = DownloadUtil.downloadStringFast(url, 6, 1);
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                String fallback = null;
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("version")) continue;
                    String version = o.get("version").getAsString();
                    if (version.isBlank()) continue;
                    if (o.has("stable") && o.get("stable").getAsBoolean()) return version;
                    if (fallback == null) fallback = version;
                }
                if (fallback != null) return fallback;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new RuntimeException("无法获取 Fabric 安装器版本", last);
    }
}