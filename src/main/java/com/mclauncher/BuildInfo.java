package com.mclauncher;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/**
 * 启动器构建信息：版本号、构建时间、运行形态与发布链接。
 *
 * <p>版本号的唯一来源是 {@code pom.xml} 的 {@code project.version}；Maven 打包时
 * 通过资源过滤把版本号和构建时间写进 {@code /build-info.properties}，这里只负责读取。
 * 版本号遵循 SemVer 2.0.0（MAJOR.MINOR.PATCH），发布前由
 * {@code scripts/bump-version.ps1} 按 {@code CHANGELOG.md} 里的改动类型自动递增：
 * 破坏性改动 → MAJOR，新功能 → MINOR，其余（修复/优化等）→ PATCH。</p>
 *
 * <p>直接以源码运行时没有该属性文件，此时回退到下面声明的默认值。</p>
 */
public final class BuildInfo {
    private BuildInfo() {}

    /** 读不到构建属性时的兜底版本号。 */
    private static final String FALLBACK_VERSION = "1.10.1";
    /** 读不到构建属性时的兜底构建时间（Unix 微秒时间戳）。 */
    private static final String FALLBACK_BUILD_DATE = "1794734886134915";

    private static final Properties PROPS = loadBuildProperties();

    /** 界面、日志与进程使用的短名称。 */
    public static final String APP_NAME = "MCFL";
    /** 开源协议页与版权声明使用的完整软件名。 */
    public static final String APP_FULL_NAME = "Minecraft Forge Launcher";
    /** 启动器版本号，SemVer 2.0.0，例如 1.8.5。 */
    public static final String VERSION = resolveVersion();
    /** 构建时间：Unix 微秒时间戳，与发布页/发布包记录的构建号一致。 */
    public static final String BUILD_DATE = resolveBuildDate();
    /** 构建时间的人类可读形式，用于悬浮提示；无法解析时为空串。 */
    public static final String BUILD_DATE_TEXT = formatBuildDate(BUILD_DATE);

    /** 安装类型：jpackage app-image 为便携版，源码/JAR 运行为开发运行。 */
    public static final String INSTALL_TYPE = detectInstallType();
    /** 支持的分发平台（发布页有更细的版本与架构列表）。 */
    public static final String PLATFORM = "Windows / Linux / macOS x64";
    /** 平台说明。 */
    public static final String PLATFORM_NOTE = "详细支持列表见发布页";
    /** 当前实际运行所在的系统与架构，用于悬浮提示。 */
    public static final String PLATFORM_CURRENT = currentPlatform();
    /** 运行环境要求。 */
    public static final String RUNTIME = "Java 17";
    /** 当前实际使用的 Java 版本，用于悬浮提示。 */
    public static final String RUNTIME_CURRENT =
            "Java " + System.getProperty("java.version", String.valueOf(Runtime.version().feature()));

    /** 发布渠道：GitHub Releases（同时会在 QQ 群内同步）。 */
    public static final String RELEASE_URL = "https://github.com/jggXa/mc-launcher/releases";
    /** 发布渠道补充说明。 */
    public static final String RELEASE_CHANNEL_NOTE = "（或 QQ 群内）";
    /** 问题反馈：QQ 群邀请链接。 */
    public static final String FEEDBACK_URL = "https://qm.qq.com/q/m4cG1oz7m8";
    /** 许可证显示名。 */
    public static final String LICENSE_NAME = "GNU GPL V3.0";
    /** 许可证 SPDX 标识。 */
    public static final String LICENSE_ID = "GPL-3.0-only";

    private static Properties loadBuildProperties() {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 缺失或损坏都回退到上面的默认值
        }
        return props;
    }

    private static String resolveVersion() {
        String value = PROPS.getProperty("version");
        if (value == null || value.isBlank() || value.contains("${")) return FALLBACK_VERSION;
        return value.trim();
    }

    private static String resolveBuildDate() {
        String value = PROPS.getProperty("buildTimestamp");
        if (value == null || value.isBlank() || value.contains("${")) return FALLBACK_BUILD_DATE;
        try {
            Instant instant = Instant.parse(value.trim());
            return Long.toString(instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L);
        } catch (Exception ignored) {
            return FALLBACK_BUILD_DATE;
        }
    }

    private static String formatBuildDate(String raw) {
        try {
            long micros = Long.parseLong(raw);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(Instant.ofEpochSecond(micros / 1_000_000L)
                            .atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String detectInstallType() {
        String appPath = System.getProperty("jpackage.app-path", "");
        if (!appPath.isBlank()) return "便携版";
        if (LauncherPaths.isPackaged()) return "便携版";
        String userDir = System.getProperty("user.dir", "").toLowerCase(Locale.ROOT);
        if (userDir.contains("mcfl") || userDir.contains("mclilaunder")
                || userDir.contains("mcli launcher")) return "便携版";
        return "开发运行";
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "未知系统").trim();
        String arch = System.getProperty("os.arch", "unknown").trim();
        return os + " " + arch;
    }
}
