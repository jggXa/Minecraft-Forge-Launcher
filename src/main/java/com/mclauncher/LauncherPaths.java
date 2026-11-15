package com.mclauncher;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 启动器文件布局：所有运行时创建的文件都放在启动器所在目录内。
 *
 * <p>目录约定（均在 {@link #root()} 之下）：</p>
 * <pre>
 * &lt;启动器目录&gt;\
 *   MCLILaunder.exe / mc-launcher.jar   ← 启动器本体
 *   launcher-config.json                ← 配置（首次启动自动生成）
 *   gpl-3.0.txt / icons\                ← 首次启动从 jar 释放
 *   errors.log                          ← 崩溃日志（发生错误时生成）
 *   minecraft\                          ← 游戏目录（版本 / 库 / 资源 / 存档）
 * </pre>
 *
 * <p>不再向 %APPDATA%、%LOCALAPPDATA% 或用户主目录写入任何文件；旧版本留下的
 * 配置与游戏目录会在首次以打包形式启动时自动迁移进来（源码运行不迁移，避免把
 * 本机用户数据搬进工程目录）。</p>
 */
public final class LauncherPaths {
    /** 配置文件：放在启动器目录根部。 */
    public static final String CONFIG_NAME = "launcher-config.json";
    /** 游戏目录名。 */
    public static final String GAME_DIR_NAME = "minecraft";
    /** 崩溃日志文件名。 */
    public static final String ERROR_LOG_NAME = "errors.log";
    /** 旧版本使用的应用目录名（%APPDATA% / %LOCALAPPDATA% 下）。 */
    public static final String LEGACY_APP_DIR_NAME = "MCLILaunder";

    /** 首次启动时创建的游戏目录骨架。 */
    private static final String[] GAME_SUBDIRS = {
            "versions", "libraries", "assets", "logs",
            "mods", "saves", "resourcepacks", "shaderpacks", "config"
    };
    /** 从 jar 释放到 <启动器目录>/icons 的图标文件。 */
    private static final String[] INITIAL_ICONS = {
            "anvil.png", "fabric.png", "grass.png", "neoforge.png", "launcher.png"
    };

    private static final List<String> NOTES = Collections.synchronizedList(new ArrayList<>());
    private static boolean migrated;

    private LauncherPaths() {}

    /** 启动器所在目录：jpackage exe 所在目录；java -jar 时为 jar 所在目录；源码运行时为工作目录。 */
    public static Path root() {
        Path exe = appPath();
        if (exe != null && exe.getParent() != null) {
            Path dir = exe.getParent();
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();

            // macOS：Foo.app/Contents/MacOS/Foo → 放回 Foo.app 所在的目录
            if ("MacOS".equals(name) && dir.getParent() != null
                    && dir.getParent().getParent() != null) {
                Path app = dir.getParent().getParent();
                if (app.getParent() != null) return app.getParent();
            }
            // Linux app-image：<image>/bin/launcher → <image>
            if ("bin".equals(name) && dir.getParent() != null
                    && Files.isDirectory(dir.getParent().resolve("lib"))) {
                return dir.getParent();
            }
            return dir;
        }
        Path jarDir = codeSourceJarDir();
        if (jarDir != null) {
            // jpackage 的 app\mc-launcher.jar：数据仍放在 app 的上一级（exe 同目录）
            Path parent = jarDir.getParent();
            if ("app".equals(String.valueOf(jarDir.getFileName()))
                    && parent != null && Files.isDirectory(parent.resolve("runtime"))) {
                return parent;
            }
            return jarDir;
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 以 java -jar 方式运行时，类路径所在的 jar 目录；源码/IDE 运行时为 null。 */
    private static Path codeSourceJarDir() {
        try {
            var source = LauncherPaths.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            Path path = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase().endsWith(".jar")) {
                return path.toAbsolutePath().normalize().getParent();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 配置文件：<启动器目录>/launcher-config.json。 */
    public static Path configFile() {
        return root().resolve(CONFIG_NAME);
    }

    /** 游戏目录：默认 <启动器目录>/minecraft。 */
    public static Path gameDir() {
        String override = System.getProperty("mclauncher.gameDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return root().resolve(GAME_DIR_NAME);
    }

    /** 崩溃日志：<启动器目录>/errors.log。 */
    public static Path errorLog() {
        return root().resolve(ERROR_LOG_NAME);
    }

    /** 确保启动器目录存在；失败时返回 null。 */
    public static Path ensureRoot() {
        try {
            Path root = root();
            Files.createDirectories(root);
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /** 确保游戏目录存在；失败时返回 null。 */
    public static Path ensureGameDir() {
        try {
            Path dir = gameDir();
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否以打包形式运行（jpackage app-image 或 java -jar）。源码运行时不迁移旧数据。 */
    public static boolean isPackaged() {
        return appPath() != null || codeSourceJarDir() != null;
    }

    /**
     * 初始化启动器目录：生成默认配置、游戏目录骨架、许可证与图标等初始文件。
     * 已存在的文件一律保留，因此重复启动不会覆盖用户数据。
     */
    public static void bootstrap() {
        if (ensureRoot() == null) {
            note("[初始化] 启动器目录不可用，无法生成初始文件: " + root());
            return;
        }
        // 源码/IDE 运行不释放初始文件，避免把配置、许可证和图标写进工程目录
        if (!isPackaged()) {
            ensureGameDir();
            return;
        }

        createGameSkeleton();
        ensureDefaultConfig();

        Path root = root();
        materialize("gpl-3.0.txt", root.resolve("gpl-3.0.txt"));
        for (String icon : INITIAL_ICONS) {
            materialize("icons/" + icon, root.resolve("icons").resolve(icon));
        }
    }

    /** 创建游戏目录骨架；已存在的目录保持不动。 */
    private static void createGameSkeleton() {
        Path game = gameDir();
        boolean created = false;
        for (String name : GAME_SUBDIRS) {
            Path dir = game.resolve(name);
            if (Files.isDirectory(dir)) continue;
            try {
                Files.createDirectories(dir);
                created = true;
            } catch (Exception e) {
                note("[初始化] 无法创建目录: " + dir + "（" + e.getMessage() + "）");
            }
        }
        if (created) note("[初始化] 已生成游戏目录骨架: " + game);
    }

    /** 没有配置时写入一份默认配置（与界面默认值一致）。 */
    private static void ensureDefaultConfig() {
        Path config = configFile();
        if (Files.exists(config)) return;
        try {
            Path parent = config.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (new Settings().save(config)) {
                note("[初始化] 已生成默认配置: " + config);
            }
        } catch (Exception e) {
            note("[初始化] 默认配置生成失败: " + config + "（" + e.getMessage() + "）");
        }
    }

    /** 把打包进 jar 的资源释放到目录；目标已存在时不覆盖。 */
    private static void materialize(String resource, Path target) {
        if (Files.exists(target)) return;
        try (java.io.InputStream in = LauncherPaths.class.getResourceAsStream("/" + resource)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            note("[初始化] 已生成 " + target);
        } catch (Exception e) {
            note("[初始化] 资源释放失败: " + target + "（" + e.getMessage() + "）");
        }
    }

    /** 取走迁移日志（界面启动后输出到日志页）。 */
    public static List<String> drainNotes() {
        synchronized (NOTES) {
            List<String> copy = new ArrayList<>(NOTES);
            NOTES.clear();
            return copy;
        }
    }

    /**
     * 首次启动时把旧版本散落在 %APPDATA%/%LOCALAPPDATA% 的配置与游戏目录迁移到
     * 启动器目录。只移动、不覆盖：目标已存在时保留现状并记录日志。
     */
    public static void migrateLegacyData() {
        synchronized (LauncherPaths.class) {
            if (migrated) return;
            migrated = true;
        }
        if (ensureRoot() == null) {
            note("[目录] 启动器目录不可写，配置与游戏数据将无法保存: " + root());
            return;
        }
        if (!isPackaged()) return;

        migrateConfig();
        migrateGameDir();
        cleanupLegacyDirs();
    }

    private static void migrateConfig() {
        Path target = configFile();
        if (Files.exists(target)) return;

        Path source = null;
        long newest = Long.MIN_VALUE;
        for (Path dir : legacyDirs()) {
            Path candidate = dir.resolve(CONFIG_NAME);
            if (!Files.isRegularFile(candidate)) continue;
            try {
                long time = Files.getLastModifiedTime(candidate).toMillis();
                if (time > newest) {
                    newest = time;
                    source = candidate;
                }
            } catch (Exception ignored) {}
        }
        if (source == null) return;

        try {
            Files.createDirectories(target.getParent());
            moveSafely(source, target);
            note("[目录] 已迁移旧配置: " + source + " → " + target);
        } catch (Exception e) {
            note("[目录] 旧配置迁移失败: " + source + "（" + e.getMessage() + "）");
        }
    }

    private static void migrateGameDir() {
        Path target = gameDir();
        for (Path dir : legacyDirs()) {
            Path legacy = dir.resolve(GAME_DIR_NAME);
            if (!Files.isDirectory(legacy) || same(legacy, target)) continue;

            if (isEmptyDir(legacy)) {
                deleteQuietly(legacy);
                continue;
            }
            if (Files.exists(target)) {
                note("[目录] 旧游戏目录与新目录同时存在，未自动迁移: " + legacy
                        + "（可手动合并到 " + target + "）");
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                moveSafely(legacy, target);
                note("[目录] 已将旧游戏目录迁移到: " + target + "（来自 " + legacy + "）");
            } catch (Exception e) {
                note("[目录] 旧游戏目录迁移失败: " + legacy + "（" + e.getMessage() + "）");
            }
        }
    }

    /** 迁移后清掉空的旧目录，避免留下空文件夹。 */
    private static void cleanupLegacyDirs() {
        for (Path dir : legacyDirs()) {
            if (Files.isDirectory(dir) && isEmptyDir(dir)) deleteQuietly(dir);
        }
    }

    /** 旧版本可能写过的目录：%APPDATA%、%LOCALAPPDATA%、~/.mclilaunder。 */
    private static List<Path> legacyDirs() {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        addLegacyDir(dirs, System.getenv("APPDATA"));
        addLegacyDir(dirs, System.getenv("LOCALAPPDATA"));
        dirs.add(Path.of(System.getProperty("user.home", "."), ".mclilaunder")
                .toAbsolutePath().normalize());

        Path root = root();
        dirs.removeIf(p -> same(p, root));
        return new ArrayList<>(dirs);
    }

    private static void addLegacyDir(LinkedHashSet<Path> dirs, String base) {
        if (base == null || base.isBlank()) return;
        dirs.add(Path.of(base, LEGACY_APP_DIR_NAME).toAbsolutePath().normalize());
    }

    private static Path appPath() {
        String value = System.getProperty("jpackage.app-path");
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean same(Path a, Path b) {
        return a.toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(b.toAbsolutePath().normalize().toString());
    }

    private static void moveSafely(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst);
        } catch (IOException moveFailed) {
            // 跨盘符或文件被占用：复制后删旧，保证数据不丢
            copyRecursively(src, dst);
            deleteRecursively(src);
        }
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src, FileVisitOption.FOLLOW_LINKS)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path out = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    private static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
    }

    private static void note(String message) {
        NOTES.add(message);
    }
}
