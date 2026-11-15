package com.mclauncher;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ModpackInstaller {
    private static final Gson GSON = new Gson();

    public static void install(Path file, Path gameDir, Settings settings, ProgressUi ui) throws Exception {
        String fileName = file.getFileName().toString().toLowerCase();
        // 很多 Modrinth 整合包被重新打包成 .zip，内部实际是 modrinth.index.json
        if (fileName.endsWith(".mrpack")
                || (fileName.endsWith(".zip") && hasEntry(file, "modrinth.index.json"))) {
            installMrpack(file, gameDir, settings, ui);
        } else if (fileName.endsWith(".zip")) {
            installZip(file, gameDir, settings, ui);
        } else {
            throw new RuntimeException("不支持的整合包格式，仅支持 .mrpack 或 .zip");
        }
    }

    private static boolean hasEntry(Path zip, String entryName) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return zf.getEntry(entryName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path contentDirFor(Path instanceDir, Path gameDir, Settings settings) {
        return settings.versionIsolation ? instanceDir.resolve("game") : gameDir;
    }

    private static void installMrpack(Path mrpackFile, Path gameDir, Settings settings, ProgressUi ui) throws Exception {
        ui.println("正在解析 Modrinth 整合包...");
        JsonObject index = null;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(mrpackFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("modrinth.index.json")) {
                    String json = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    index = JsonParser.parseString(json).getAsJsonObject();
                    break;
                }
            }
        }
        if (index == null) throw new RuntimeException("无效的 .mrpack 文件：缺少 modrinth.index.json");

        String packName = index.has("name") ? index.get("name").getAsString() : "Modpack";
        String safeName = packName.replaceAll("[\\\\/:*?\"<>|]", "_")
                + "-" + Integer.toHexString(packName.hashCode() & 0xffff);
        ui.println("整合包名称: " + safeName);

        JsonObject dependencies = index.getAsJsonObject("dependencies");
        if (dependencies == null || !dependencies.has("minecraft")) {
            throw new RuntimeException("无效的 .mrpack：缺少 dependencies.minecraft");
        }
        String mcVersion = dependencies.get("minecraft").getAsString();

        String loaderType = null;
        String loaderVersion = null;
        if (dependencies.has("fabric-loader")) {
            loaderType = "fabric"; loaderVersion = dependencies.get("fabric-loader").getAsString();
        } else if (dependencies.has("forge")) {
            loaderType = "forge"; loaderVersion = dependencies.get("forge").getAsString();
        } else if (dependencies.has("neoforge")) {
            loaderType = "neoforge"; loaderVersion = dependencies.get("neoforge").getAsString();
        } else if (dependencies.has("quilt-loader")) {
            loaderType = "quilt"; loaderVersion = dependencies.get("quilt-loader").getAsString();
        }

        ui.println("目标 MC 版本: " + mcVersion + " | 加载器: "
                + (loaderType != null ? loaderType + " " + loaderVersion : "原版"));

        ui.setInfo(0, "正在安装原版与加载器");
        VersionInstaller.install(mcVersion, gameDir,
                (task, pct) -> ui.progress(task, pct * 60 / 100), ui);

        String loaderInstanceId = null;
        if (loaderType != null) {
            loaderInstanceId = LoaderInstaller.install(loaderType, mcVersion, loaderVersion,
                    gameDir, settings,
                    (task, pct) -> ui.progress(task, 60 + pct * 20 / 100), ui);
        }

        Path instanceDir = gameDir.resolve("versions").resolve(safeName);
        Path contentDir = contentDirFor(instanceDir, gameDir, settings);
        Files.createDirectories(instanceDir);
        Files.createDirectories(contentDir);

        ui.clearInfo();
        ui.setInfo(0, "正在下载整合包模组文件");
        JsonArray files = index.getAsJsonArray("files");
        int total = files.size(), done = 0;

        for (JsonElement e : files) {
            JsonObject fileObj = e.getAsJsonObject();
            if (fileObj.has("env")) {
                JsonObject env = fileObj.getAsJsonObject("env");
                if (env.has("client") && env.get("client").getAsString().equals("unsupported")) {
                    done++;
                    continue;
                }
            }

            String path = fileObj.get("path").getAsString();
            JsonArray urls = fileObj.getAsJsonArray("downloads");
            JsonObject hashes = fileObj.getAsJsonObject("hashes");
            String sha1 = hashes.has("sha1") ? hashes.get("sha1").getAsString() : null;

            Path target = contentDir.resolve(path);
            if (!Files.exists(target) && urls.size() > 0) {
                boolean downloaded = false;
                for (JsonElement urlElem : urls) {
                    try {
                        DownloadUtil.downloadFile(urlElem.getAsString(), target, sha1, ui);
                        downloaded = true;
                        break;
                    } catch (Exception ignored) {}
                }
                if (!downloaded) throw new RuntimeException("下载失败: " + path);
            }
            done++;
            ui.progress("模组 " + done + "/" + total, 80 + done * 20 / Math.max(total, 1));
        }

        ui.setInfo(0, "正在解压配置覆盖文件");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(mrpackFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ((entry.getName().startsWith("overrides/")
                        || entry.getName().startsWith("client-overrides/"))
                        && !entry.isDirectory()) {
                    int idx = entry.getName().indexOf('/');
                    String relPath = entry.getName().substring(idx + 1);
                    if (relPath.equals("manifest.json") || relPath.equals("modrinth.index.json")) {
                        continue;
                    }
                    Path out = contentDir.resolve(relPath).normalize();
                    if (!out.startsWith(contentDir)) continue;
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        createModpackVersionJson(instanceDir, safeName,
                loaderInstanceId != null ? loaderInstanceId : mcVersion);
        ui.finishProgress();
        ui.clearInfo();
        ui.println("整合包 [" + safeName + "] 安装成功！可在版本列表中启动。");
    }

    private static void installZip(Path zipFile, Path gameDir, Settings settings, ProgressUi ui) throws Exception {
        String packName = zipFile.getFileName().toString().replace(".zip", "");
        String safeName = packName.replaceAll("[\\\\/:*?\"<>|]", "_")
                + "-" + Integer.toHexString(packName.hashCode() & 0xffff);
        Path instanceDir = gameDir.resolve("versions").resolve(safeName);
        Path contentDir = contentDirFor(instanceDir, gameDir, settings);
        Files.createDirectories(instanceDir);
        Files.createDirectories(contentDir);

        ui.println("正在解压 ZIP 整合包...");
        String mcVersion = null;
        String loaderType = null;
        String loaderVersion = null;
        boolean isManaged = false;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                String rel = entryName;
                if (rel.startsWith("overrides/")) {
                    rel = rel.substring("overrides/".length());
                } else if (rel.startsWith("client-overrides/")) {
                    rel = rel.substring("client-overrides/".length());
                }
                if (rel.isEmpty()) continue;

                String baseName = rel.substring(rel.lastIndexOf('/') + 1);
                if (baseName.equals("manifest.json") || baseName.equals("modrinth.index.json")) {
                    byte[] content = zis.readAllBytes();
                    try {
                        JsonObject manifest = JsonParser.parseString(
                                new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (manifest.has("minecraft")) {
                            JsonObject mc = manifest.getAsJsonObject("minecraft");
                            if (mc.has("version")) mcVersion = mc.get("version").getAsString();
                            if (mc.has("modLoaders") && mc.getAsJsonArray("modLoaders").size() > 0) {
                                String id = mc.getAsJsonArray("modLoaders").get(0)
                                        .getAsJsonObject().get("id").getAsString();
                                if (id.startsWith("fabric-")) {
                                    loaderType = "fabric"; loaderVersion = id.substring(7);
                                } else if (id.startsWith("forge-")) {
                                    loaderType = "forge"; loaderVersion = id.substring(6);
                                } else if (id.startsWith("neoforge-")) {
                                    loaderType = "neoforge"; loaderVersion = id.substring(9);
                                } else if (id.startsWith("quilt-")) {
                                    loaderType = "quilt"; loaderVersion = id.substring(6);
                                }
                            }
                            isManaged = true;
                        } else if (manifest.has("dependencies")) {
                            JsonObject deps = manifest.getAsJsonObject("dependencies");
                            if (deps.has("minecraft")) mcVersion = deps.get("minecraft").getAsString();
                            if (deps.has("fabric-loader")) {
                                loaderType = "fabric"; loaderVersion = deps.get("fabric-loader").getAsString();
                            } else if (deps.has("forge")) {
                                loaderType = "forge"; loaderVersion = deps.get("forge").getAsString();
                            } else if (deps.has("neoforge")) {
                                loaderType = "neoforge"; loaderVersion = deps.get("neoforge").getAsString();
                            }
                            isManaged = true;
                        }
                    } catch (Exception ignored) {}
                    // 写入实例目录，避免版本隔离关闭时污染游戏根目录
                    Files.write(instanceDir.resolve(baseName), content);
                    continue;
                }

                Path out = contentDir.resolve(rel).normalize();
                if (!out.startsWith(contentDir)) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (isManaged && mcVersion != null) {
            ui.println("检测到整合包元数据，正在安装适配的原版与加载器...");
            ui.setInfo(0, "正在安装原版与加载器");
            VersionInstaller.install(mcVersion, gameDir,
                    (task, pct) -> ui.progress(task, pct * 60 / 100), ui);
            String loaderInstanceId = null;
            if (loaderType != null) {
                loaderInstanceId = LoaderInstaller.install(loaderType, mcVersion, loaderVersion,
                        gameDir, settings,
                        (task, pct) -> ui.progress(task, 60 + pct * 40 / 100), ui);
            }
            createModpackVersionJson(instanceDir, safeName,
                    loaderInstanceId != null ? loaderInstanceId : mcVersion);
            ui.finishProgress();
            ui.clearInfo();
            ui.println("ZIP 整合包解压完成！已注册为版本: " + safeName);
        } else if (isManaged) {
            ui.println("⚠ 整合包元数据缺少 Minecraft 版本信息，仅解压文件，未注册为可启动版本。");
            ui.println("解压位置: " + contentDir);
        } else {
            ui.println("⚠ 未检测到标准整合包元数据 (manifest.json)，仅解压文件，未注册为可启动版本。");
            ui.println("解压位置: " + contentDir);
        }
    }

    private static void createModpackVersionJson(Path instanceDir, String name, String parentId) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("id", name);
        json.addProperty("type", "release");
        json.addProperty("inheritsFrom", parentId);
        Files.writeString(instanceDir.resolve(name + ".json"), GSON.toJson(json));
    }
}