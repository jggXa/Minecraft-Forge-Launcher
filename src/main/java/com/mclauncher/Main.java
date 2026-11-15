package com.mclauncher;

import org.fusesource.jansi.AnsiConsole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Main {
    private static final String APP_VERSION = BuildInfo.VERSION;
    private static final Path APP_DIR = LauncherPaths.root();
    private static final Path GAME_DIR = LauncherPaths.gameDir();
    private static final Path CONFIG_FILE = LauncherPaths.configFile();

    private static Settings settings;
    private static Ui ui;
    private static LogWindow logWindow;

    public static void main(String[] args) throws Exception {
        AnsiConsole.systemInstall();

        try {
            logWindow = new LogWindow();
            logWindow.start();

            LauncherPaths.migrateLegacyData();
            LauncherPaths.bootstrap();

            settings = Settings.load(CONFIG_FILE);
            Mirror.set(Mirror.Type.fromId(settings.mirror));
            Mirror.setLoader(Mirror.LoaderType.fromId(settings.loaderMirror));
            applyTransferSettings();

            ui = new Ui();
            ui.attachLogWindow(logWindow);

            ui.clear();
            ui.println("[启动器] 已启动，启动器目录: " + APP_DIR);
            ui.println("[启动器] 配置文件: " + CONFIG_FILE);
            ui.println("[启动器] 游戏目录: " + GAME_DIR);
            for (String note : LauncherPaths.drainNotes()) ui.println(note);
            ui.println("[启动器] 原版镜像: " + Mirror.get().label + " | 加载器镜像: " + Mirror.getLoader().label);

            if (args.length > 0) {
                Path file = Path.of(args[0].replace("\"", "").trim());
                if (Files.exists(file) && (file.toString().endsWith(".mrpack") || file.toString().endsWith(".zip"))) {
                    boolean valid = true;
                    if (file.toString().endsWith(".zip")) {
                        valid = false;
                        try (var zis = new ZipInputStream(Files.newInputStream(file))) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                String name = entry.getName();
                                if (name.endsWith("manifest.json") || name.endsWith("modrinth.index.json")) {
                                    valid = true; break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    if (valid) {
                        ui.clear();
                        ui.println("检测到整合包: " + file.getFileName());
                        ui.println("按 ESC 取消，按任意键继续安装（将强制开启版本隔离）");
                        int k = ui.readKey();
                        if (k != 27) {
                            settings.versionIsolation = true;
                            settings.save(CONFIG_FILE);
                            try {
                                installModpackWithPause(file);
                            } catch (Exception e) {
                                ui.println("安装失败: " + e.getMessage());
                            }
                            ui.println("按 ESC 进入主菜单");
                            waitEsc();
                        }
                    } else {
                        ui.println("错误: 该 .zip 不包含 manifest.json，可能不是整合包。");
                        ui.println("按 ESC 进入主菜单");
                        waitEsc();
                    }
                }
            }

            while (mainMenu()) { /* 循环直到退出 */ }
        } finally {
            if (ui != null) ui.close();
            if (logWindow != null) logWindow.close();
            AnsiConsole.systemUninstall();
        }
    }

    private static void applyTransferSettings() {
        DownloadUtil.setBufferSize(Math.max(8, settings.bufferSizeKb) * 1024);
        VersionInstaller.setConcurrency(Math.max(1, settings.downloadThreads));
    }

    private static boolean mainMenu() throws Exception {
        ui.clear();
        ui.println("========== " + BuildInfo.APP_NAME + " ==========");
        ui.println("1. 版本列表");
        ui.println("2. 启动器设置");
        ui.println("3. 下载版本");
        ui.println("0. 开源声明");
        ui.println("按 ESC 退出");
        ui.println("提示: 可直接将 .mrpack / .zip 文件拖入本窗口安装！");
        ui.println("启动器目录: " + APP_DIR);
        ui.println("游戏目录: " + GAME_DIR);

        String input = ui.readLine("输入: ");
        if (input == null) return false;
        input = input.trim().replace("\"", "");

        if (input.endsWith(".mrpack") || input.endsWith(".zip")) {
            Path file = Path.of(input);
            if (Files.exists(file)) {
                boolean valid = true;
                if (input.endsWith(".zip")) {
                    valid = false;
                    try (var zis = new ZipInputStream(Files.newInputStream(file))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            String name = entry.getName();
                            if (name.endsWith("manifest.json") || name.endsWith("modrinth.index.json")) {
                                valid = true; break;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (!valid) {
                    ui.println("错误: 该 .zip 不包含 manifest.json，可能不是整合包。按 ESC 返回");
                    waitEsc();
                    return true;
                }

                ui.clear();
                ui.println("检测到整合包: " + file.getFileName());
                ui.println("按 ESC 取消，按任意键继续安装（将强制开启版本隔离）");
                int k = ui.readKey();
                if (k != 27) {
                    settings.versionIsolation = true;
                    settings.save(CONFIG_FILE);
                    try {
                        installModpackWithPause(file);
                    } catch (Exception e) {
                        ui.println("安装失败: " + e.getMessage());
                    }
                    ui.println("按 ESC 返回");
                    waitEsc();
                }
                return true;
            }
        }

        switch (input) {
            case "0" -> showLicense();
            case "1" -> versionList();
            case "2" -> launcherSettings();
            case "3" -> downloadFlow();
            default -> {
                ui.println("未知选项: " + input + "，按 ESC 返回");
                waitEsc();
            }
        }
        return true;
    }

    private static boolean checkJava(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start();
            Thread drain = new Thread(() -> {
                try (var in = p.getInputStream()) { in.transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (Exception ignored) {}
            });
            drain.setDaemon(true);
            drain.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkJava() {
        for (String exe : settings.allConfiguredJava()) {
            if (checkJava(exe)) return true;
        }
        return checkJava(settings.resolveJava());
    }

    private static void showLicense() throws Exception {
        ui.clear();
        ui.println(BuildInfo.APP_FULL_NAME);
        ui.println("Copyright (C) 2026 jggXa, cheng, and PrismGlow Studio group contributors");
        ui.println();
        ui.println("SPDX-License-Identifier: GPL-3.0-only");
        ui.println();
        ui.println("This file is part of " + BuildInfo.APP_FULL_NAME + ".");
        ui.println();
        ui.println("This program is free software: you can redistribute it and/or modify");
        ui.println("it under the terms of the GNU General Public License as published by");
        ui.println("the Free Software Foundation, version 3 of the License.");
        ui.println();
        ui.println("This program is distributed in the hope that it will be useful,");
        ui.println("but WITHOUT ANY WARRANTY; without even the implied warranty of");
        ui.println("MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the");
        ui.println("GNU General Public License for more details.");
        ui.println();
        ui.println("================================================================================");
        ui.println();

        String content = loadGpl3Text();
        if (content == null) {
            ui.println("[错误] 找不到 GPL v3 协议文件 gpl-3.0.txt");
            ui.println("可以从 https://www.gnu.org/licenses/gpl-3.0.txt 下载，");
            ui.println("放到程序目录，或者重新打包时放在 src/main/resources/ 下。");
        } else {
            String[] lines = content.split("\r?\n");
            int perPage = 30;
            boolean aborted = false;
            for (int i = 0; i < lines.length; i += perPage) {
                for (int j = i; j < Math.min(i + perPage, lines.length); j++) {
                    ui.println(lines[j]);
                }
                if (i + perPage < lines.length) {
                    ui.println("-- 按任意键继续，按 ESC 退出 --");
                    int k = ui.readKey();
                    if (k == 27) { aborted = true; break; }
                }
            }
            if (aborted) return;
        }

        ui.println();
        ui.println("================================================================================");
        ui.println("按 ESC 返回主菜单");
        waitEsc();
    }

    private static String loadGpl3Text() {
        try {
            Path local = Path.of(System.getProperty("user.dir"), "gpl-3.0.txt");
            if (Files.exists(local)) {
                return Files.readString(local);
            }
        } catch (Exception ignored) {}

        try (var in = Main.class.getResourceAsStream("/gpl-3.0.txt")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static void versionList() throws Exception {
        ui.clear();
        List<String> installed = listInstalled();
        if (installed.isEmpty()) {
            ui.println("尚未安装版本，请前往安装。");
            ui.println("");
            ui.println("按 ESC 返回");
            waitEsc();
            return;
        }
        for (int i = 0; i < installed.size(); i++) {
            ui.println((i + 1) + ". " + installed.get(i));
        }
        ui.println("");
        String input = ui.readLine("输入序号启动该版本，或按 ESC 返回: ");
        if (input == null || input.isBlank()) return;

        try {
            int idx = Integer.parseInt(input.trim());
            if (idx < 1 || idx > installed.size()) {
                ui.println("序号无效。按 ESC 返回");
                waitEsc();
                return;
            }
            String ver = installed.get(idx - 1);

            int major;
            try {
                major = Launcher.requiredJavaMajor(ver, GAME_DIR);
            } catch (Exception e) {
                major = 17;
            }
            String javaExe = settings.resolveJava(major);

            if (!checkJava(javaExe)) {
                ui.println("启动失败: 版本「" + ver + "」需要 Java " + major + "，未找到可用 Java！");
                ui.println("已尝试: " + javaExe);
                ui.println("请在「启动器设置 → 2」中配置 JAVA"
                        + (major >= 25 ? 25 : major >= 21 ? 21 : major >= 17 ? 17 : 8)
                        + " 路径。");
                waitEsc();
                return;
            }

            ui.clear();
            ui.setInfo(0, "版本: " + ver);
            ui.setInfo(1, "所需 Java: " + major + "  |  路径: " + javaExe);
            ui.setInfo(2, "阶段: 准备启动");
            ui.println("正在启动 " + ver + " ...");
            ui.println("(使用 Java " + major + ": " + javaExe + ")");
            Launcher.launch(ver, GAME_DIR, settings, ui::log);
            ui.clearInfo();
            ui.println("已启动，游戏窗口稍后出现。");
            ui.println("按 ESC 返回");
            waitEsc();
        } catch (NumberFormatException e) {
            ui.println("请输入正确的数字。按 ESC 返回");
            waitEsc();
        } catch (Exception e) {
            ui.clearInfo();
            ui.println("启动失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            waitEsc();
        }
    }

    private static void launcherSettings() throws Exception {
        while (true) {
            ui.clear();
            ui.println("========== 启动器设置 ==========");
            ui.println("1. 版本隔离: " + (settings.versionIsolation ? "开" : "关"));
            ui.println("2. Java 路径（按版本配置）");
            ui.println("3. 最大内存: " + settings.maxMemory);
            ui.println("4. 用户名: " + settings.username);
            ui.println("5. UUID: " + (settings.uuid.isBlank() ? "(留空自动生成离线UUID)" : settings.uuid));
            ui.println("6. 原版镜像: " + Mirror.get().label);
            ui.println("7. 加载器镜像: " + Mirror.getLoader().label);
            ui.println("8. 恢复日志窗口");
            ui.println("9. 下载缓冲区: " + settings.bufferSizeKb + " KB");
            ui.println("10. 下载线程数: " + settings.downloadThreads);
            ui.println("11. 启动器目录: " + APP_DIR);
            ui.println("12. 游戏目录: " + GAME_DIR);
            ui.println("按 ESC 返回");

            String k = ui.readLine("输入: ");
            if (k == null) return;

            switch (k.trim()) {
                case "1" -> { settings.versionIsolation = !settings.versionIsolation; settings.save(CONFIG_FILE); }
                case "2" -> javaSettings();
                case "3" -> {
                    String v = ui.readLine("最大内存（如 2G / 4096M）: ");
                    if (v != null && !v.isBlank()) {
                        String mem = v.trim().toUpperCase();
                        if (mem.matches("\\d+[GM]")) {
                            settings.maxMemory = mem;
                            settings.save(CONFIG_FILE);
                        } else {
                            ui.println("格式错误，应为如 2G 或 4096M。按 ESC 继续");
                            waitEsc();
                        }
                    }
                }
                case "4" -> {
                    String v = ui.readLine("用户名: ");
                    if (v != null && !v.isBlank()) { settings.username = v.trim(); settings.save(CONFIG_FILE); }
                }
                case "5" -> {
                    String v = ui.readLine("新 UUID（留空恢复自动生成）: ");
                    if (v != null) { settings.uuid = v.trim(); settings.save(CONFIG_FILE); }
                }
                case "6" -> {
                    ui.println("选择原版下载镜像:");
                    Mirror.Type[] all = Mirror.Type.values();
                    for (int i = 0; i < all.length; i++) ui.println((i + 1) + ". " + all[i].label + (all[i] == Mirror.get() ? " (当前)" : ""));
                    String m = ui.readLine("输入: ");
                    if (m != null && !m.isBlank()) {
                        try {
                            int idx = Integer.parseInt(m.trim()) - 1;
                            if (idx >= 0 && idx < all.length) { settings.mirror = all[idx].name(); Mirror.set(all[idx]); settings.save(CONFIG_FILE); }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case "7" -> {
                    ui.println("选择加载器下载镜像:");
                    Mirror.LoaderType[] all = Mirror.LoaderType.values();
                    for (int i = 0; i < all.length; i++) ui.println((i + 1) + ". " + all[i].label + (all[i] == Mirror.getLoader() ? " (当前)" : ""));
                    String m = ui.readLine("输入: ");
                    if (m != null && !m.isBlank()) {
                        try {
                            int idx = Integer.parseInt(m.trim()) - 1;
                            if (idx >= 0 && idx < all.length) { settings.loaderMirror = all[idx].name(); Mirror.setLoader(all[idx]); settings.save(CONFIG_FILE); }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case "8" -> logWindow.restore();
                case "9" -> {
                    String v = ui.readLine("下载缓冲区大小（KB，8-1024，默认 64）: ");
                    if (v != null && !v.isBlank()) {
                        try {
                            int kb = Integer.parseInt(v.trim());
                            if (kb >= 8 && kb <= 1024) { settings.bufferSizeKb = kb; applyTransferSettings(); settings.save(CONFIG_FILE); }
                            else { ui.println("范围 8-1024，已忽略。按 ESC 继续"); waitEsc(); }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case "10" -> {
                    String v = ui.readLine("下载线程数（1-16，默认 4）: ");
                    if (v != null && !v.isBlank()) {
                        try {
                            int n = Integer.parseInt(v.trim());
                            if (n >= 1 && n <= 16) { settings.downloadThreads = n; applyTransferSettings(); settings.save(CONFIG_FILE); }
                            else { ui.println("范围 1-16，已忽略。按 ESC 继续"); waitEsc(); }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                case "11" -> {
                    ui.println("启动器目录: " + APP_DIR);
                    ui.println("按 ESC 返回");
                    waitEsc();
                }
                case "12" -> {
                    ui.println("游戏目录: " + GAME_DIR);
                    ui.println("按 ESC 返回");
                    waitEsc();
                }
            }
        }
    }

    private static void javaSettings() throws Exception {
        while (true) {
            ui.clear();
            ui.println("========== Java 路径配置 ==========");
            ui.println("启动游戏时会根据 Minecraft 实例自动选择对应的 Java。");
            ui.println("");
            ui.println("1. JAVA25 路径: " + displayPath(settings.javaPath25));
            ui.println("2. JAVA21 路径: " + displayPath(settings.javaPath21));
            ui.println("3. JAVA17 路径: " + displayPath(settings.javaPath17));
            ui.println("4. JAVA8  路径: " + displayPath(settings.javaPath8));
            ui.println("5. 全局默认路径: " + (settings.javaPath.isBlank() ? "(使用系统环境变量)" : settings.javaPath));
            ui.println("按 ESC 返回");

            String k = ui.readLine("输入数字选择要修改的项: ");
            if (k == null) return;

            switch (k.trim()) {
                case "1" -> editJavaPath("JAVA25", 25);
                case "2" -> editJavaPath("JAVA21", 21);
                case "3" -> editJavaPath("JAVA17", 17);
                case "4" -> editJavaPath("JAVA8", 8);
                case "5" -> {
                    String v = ui.readLine("新全局 Java 路径（留空使用系统环境变量）: ");
                    if (v != null) { settings.javaPath = v.trim(); settings.save(CONFIG_FILE); }
                }
            }
        }
    }

    private static void editJavaPath(String label, int major) throws Exception {
        String old = switch (major) {
            case 25 -> settings.javaPath25;
            case 21 -> settings.javaPath21;
            case 17 -> settings.javaPath17;
            case 8 -> settings.javaPath8;
            default -> "";
        };
        String prompt = label + " 新路径（留空清除）";
        if (old != null && !old.isBlank()) prompt += "，当前: " + old;
        String v = ui.readLine(prompt + ": ");
        if (v == null) return;
        v = v.trim();
        switch (major) {
            case 25 -> settings.javaPath25 = v;
            case 21 -> settings.javaPath21 = v;
            case 17 -> settings.javaPath17 = v;
            case 8 -> settings.javaPath8 = v;
        }
        settings.save(CONFIG_FILE);
    }

    private static String displayPath(String p) {
        return (p == null || p.isBlank()) ? "(未设置)" : p;
    }

    private static void downloadFlow() throws Exception {
        ui.clear();

        String version = ui.readLine("请输入版本号: ");
        if (version == null || version.isBlank()) return;
        version = version.trim();

        boolean exists;
        try {
            ui.println("正在检查版本 " + version + " ...");
            exists = versionExists(version);
        } catch (Exception e) {
            ui.println("");
            ui.println("检查版本失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            ui.println("当前原版镜像: " + Mirror.get().label + "，可在「启动器设置 → 6」切换");
            ui.println("按 ESC 返回");
            waitEsc();
            return;
        }

        if (!exists) {
            ui.println("该版本不存在，请重新输入版本号: ");
            waitEsc();
            return;
        }

        ui.clear();
        ui.println("安装模组加载器?");
        ui.println("0. 不安装任何加载器");
        ui.println("1. Forge");
        ui.println("2. NeoForge");
        ui.println("3. Fabric");
        ui.println("4. Quilt");

        String lcStr = ui.readLine("输入: ");
        if (lcStr == null) return;

        String loader = switch (lcStr.trim()) {
            case "1" -> "forge";
            case "2" -> "neoforge";
            case "3" -> "fabric";
            case "4" -> "quilt";
            default -> null;
        };

        if (loader != null && !checkJava()) {
            ui.println("警告: 安装模组加载器需要 Java，但您的系统中找不到！");
            ui.println("请在「启动器设置 → 2」中配置 Java 路径。");
            ui.println("按 ESC 返回");
            waitEsc();
            return;
        }

        String loaderVersion = null;
        if (loader != null) {
            ui.clear();
            ui.println("正在获取 " + loader + " 版本列表...");
            List<LoaderResolver.LoaderVersion> versions;
            try {
                versions = LoaderResolver.list(loader, version);
            } catch (Exception e) {
                ui.println("查询失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                ui.println("当前加载器镜像: " + Mirror.getLoader().label + "，可在「启动器设置 → 7」切换");
                waitEsc();
                return;
            }
            if (versions.isEmpty()) {
                ui.println("该加载器不支持 Minecraft " + version + "。按 ESC 返回");
                waitEsc();
                return;
            }

            ui.clear();
            ui.println("选择 " + loader + " 版本:");
            ui.println("1. 最稳定版本 (" + firstStable(versions) + ")");
            ui.println("2. 最新版本 (" + versions.get(0).version() + ")");
            ui.println("3. 自定义版本");

            String vcStr = ui.readLine("输入: ");
            if (vcStr == null) return;

            if (vcStr.trim().equals("1")) {
                loaderVersion = firstStable(versions);
            } else if (vcStr.trim().equals("2")) {
                loaderVersion = versions.get(0).version();
            } else if (vcStr.trim().equals("3")) {
                ui.clear();
                String custom = ui.readLine("请输入版本号: ");
                if (custom == null || custom.isBlank()) return;
                final String cv = custom.trim();
                boolean ok = versions.stream().anyMatch(v -> v.version().equals(cv));
                if (!ok) {
                    ui.println("该版本不存在，请重新输入版本号: ");
                    waitEsc();
                    return;
                }
                loaderVersion = cv;
            } else {
                return;
            }
        }

        ui.clear();
        try {
            installAll(version, loader, loaderVersion);
            ui.clear();
            ui.println("安装成功！按 ESC 返回主菜单");
        } catch (Exception e) {
            ui.finishProgress();
            ui.clearInfo();
            ui.println("");
            ui.println("安装失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            ui.println("如果是网络超时，请尝试去「启动器设置 → 6/7」换一个镜像源。");
            if (e.getCause() != null) {
                ui.println("底层原因: " + e.getCause().getMessage());
            }
        }
        waitEsc();
    }

    private static void installAll(String mcVersion, String loader, String loaderVersion) throws Exception {
        ui.setInfo(0, "版本: " + mcVersion + (loader != null ? "  |  加载器: " + loader + " " + loaderVersion : "  |  无加载器"));
        ui.setInfo(1, "原版镜像: " + Mirror.get().label + "  |  加载器镜像: " + Mirror.getLoader().label);
        ui.setInfo(2, "阶段: 正在下载 (按空格暂停/恢复)");

        final Exception[] dlError = new Exception[1];
        DownloadUtil.paused = false;

        Thread dlThread = new Thread(() -> {
            try {
                VersionInstaller.install(mcVersion, GAME_DIR,
                        (task, pct) -> ui.progress(task, pct * 80 / 100), ui);
                if (loader != null) {
                    ui.setInfo(2, "阶段: 安装 " + loader + " (按空格暂停/恢复)");
                    LoaderInstaller.install(loader, mcVersion, loaderVersion, GAME_DIR, settings,
                            (task, pct) -> ui.progress(task, 80 + pct * 20 / 100), ui);
                }
                ui.progress("安装完成", 100);
            } catch (Exception e) {
                dlError[0] = e;
            }
        });
        dlThread.start();

        while (dlThread.isAlive()) {
            int k = ui.readKey(200);
            if (k == ' ') {
                DownloadUtil.paused = !DownloadUtil.paused;
                ui.setInfo(2, DownloadUtil.paused ? "阶段: 已暂停 (按空格恢复)" : "阶段: 正在下载 (按空格暂停/恢复)");
                if (DownloadUtil.paused) ui.progress("已暂停", 0);
            }
        }

        DownloadUtil.paused = false;
        dlThread.join();
        ui.finishProgress();
        ui.clearInfo();
        if (dlError[0] != null) throw dlError[0];
    }

    private static void installModpackWithPause(Path file) throws Exception {
        final Exception[] err = new Exception[1];
        DownloadUtil.paused = false;

        ui.setInfo(0, "整合包: " + file.getFileName());
        ui.setInfo(1, "阶段: 正在安装 (按空格暂停/恢复)");
        ui.setInfo(2, null);

        Thread t = new Thread(() -> {
            try {
                ModpackInstaller.install(file, GAME_DIR, settings, ui);
            } catch (Exception e) {
                err[0] = e;
            }
        });
        t.start();

        while (t.isAlive()) {
            int k = ui.readKey(200);
            if (k == ' ') {
                DownloadUtil.paused = !DownloadUtil.paused;
                ui.setInfo(1, DownloadUtil.paused ? "阶段: 已暂停 (按空格恢复)" : "阶段: 正在安装 (按空格暂停/恢复)");
            }
        }

        DownloadUtil.paused = false;
        t.join();
        ui.finishProgress();
        ui.clearInfo();
        if (err[0] != null) throw err[0];
    }

    private static String firstStable(List<LoaderResolver.LoaderVersion> versions) {
        for (var v : versions) if (v.stable()) return v.version();
        return versions.get(0).version();
    }

    private static boolean versionExists(String id) throws Exception {
        String json = DownloadUtil.downloadString("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        com.google.gson.JsonArray versions = root.getAsJsonArray("versions");
        for (com.google.gson.JsonElement e : versions) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return true;
        }
        return false;
    }

    private static List<String> listInstalled() {
        Path dir = GAME_DIR.resolve("versions");
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.newDirectoryStream(dir, Files::isDirectory)) {
            List<String> list = new ArrayList<>();
            for (Path p : s) {
                if (VersionInstaller.isInstalling(p)) continue;
                list.add(p.getFileName().toString());
            }
            Collections.sort(list);
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void waitEsc() throws Exception {
        while (true) {
            int k = ui.readKey();
            if (k == 27) return;
        }
    }
}