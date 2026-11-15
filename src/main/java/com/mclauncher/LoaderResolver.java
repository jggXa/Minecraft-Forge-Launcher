package com.mclauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoaderResolver {

    public record LoaderVersion(String version, boolean stable) {}

    private record CacheEntry(List<LoaderVersion> versions, long time) {}

    private static final long CACHE_TTL = 30 * 60 * 1000L;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public static List<LoaderVersion> list(String loader, String mcVersion) throws Exception {
        String key = loader.toLowerCase() + ":" + mcVersion + ":" + Mirror.getLoader().name();
        CacheEntry cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL) {
            return cached.versions;
        }
        try {
            List<LoaderVersion> versions = switch (loader.toLowerCase()) {
                case "fabric" -> listFabric(mcVersion);
                case "quilt" -> listQuilt(mcVersion);
                case "forge" -> listForge(mcVersion);
                case "neoforge" -> listNeoForge(mcVersion);
                default -> List.of();
            };
            CACHE.put(key, new CacheEntry(List.copyOf(versions), System.currentTimeMillis()));
            return versions;
        } catch (Exception e) {
            if (cached != null) return cached.versions;
            throw e;
        }
    }

    private static String fetchForLoader(String loader, String mcVersion, String expected) throws Exception {
        List<String> tried = new ArrayList<>();
        Exception last = null;
        for (Mirror.LoaderType type : Mirror.loaderOrder()) {
            String url = sourceUrl(type, loader, mcVersion);
            if (url == null || tried.contains(url)) continue;
            tried.add(url);
            try {
                String body = DownloadUtil.downloadStringFast(url, 6, 1);
                if (looksLikeValidBody(loader, body) && body.contains(expected)) return body;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new RuntimeException("加载器元数据查询失败（已尝试 " + tried.size() + " 个源）", last);
    }

    /** 简单校验响应体是否像真正的元数据，避免错误页包含关键字被误判。 */
    private static boolean looksLikeValidBody(String loader, String body) {
        if (body == null || body.isBlank()) return false;
        String trimmed = body.trim();
        if (loader.equals("fabric") || loader.equals("quilt")) {
            return trimmed.startsWith("[") || trimmed.startsWith("{");
        }
        return trimmed.startsWith("<?xml")
                || trimmed.startsWith("<metadata")
                || (trimmed.contains("<metadata>") && trimmed.contains("<versioning>"));
    }

    private static String sourceUrl(Mirror.LoaderType type, String loader, String mcVersion) {
        return switch (loader) {
            case "fabric" -> switch (type) {
                case MIRROR -> "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/" + mcVersion;
                case OFFICIAL, BACKUP -> "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion;
            };
            case "quilt" -> switch (type) {
                case MIRROR -> "https://bmclapi2.bangbang93.com/quilt-meta/v3/versions/loader/" + mcVersion;
                case OFFICIAL, BACKUP -> "https://meta.quiltmc.org/v3/versions/loader/" + mcVersion;
            };
            case "forge" -> switch (type) {
                case MIRROR -> "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/maven-metadata.xml";
                case OFFICIAL -> "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
                case BACKUP -> "https://files.minecraftforge.net/maven/net/minecraftforge/forge/maven-metadata.xml";
            };
            case "neoforge" -> switch (type) {
                case MIRROR -> "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml";
                case OFFICIAL -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
                case BACKUP -> "https://neoforged.forgecdn.net/releases/net/neoforged/neoforge/maven-metadata.xml";
            };
            default -> null;
        };
    }

    private static List<LoaderVersion> listFabric(String mcVersion) throws Exception {
        String json = fetchForLoader("fabric", mcVersion, "version");
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject loader = o.has("loader") && o.get("loader").isJsonObject()
                    ? o.getAsJsonObject("loader") : o;
            if (!loader.has("version")) continue;
            result.add(new LoaderVersion(
                    loader.get("version").getAsString(),
                    loader.has("stable") && loader.get("stable").getAsBoolean()));
        }
        return result;
    }

    private static List<LoaderVersion> listQuilt(String mcVersion) throws Exception {
        String json = fetchForLoader("quilt", mcVersion, "version");
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new RuntimeException("Quilt 镜像返回格式异常");
        }
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject loader = o.has("loader") && o.get("loader").isJsonObject()
                    ? o.getAsJsonObject("loader") : o;
            if (!loader.has("version")) continue;
            String version = loader.get("version").getAsString();
            if (version.isBlank()) continue;
            boolean stable = !version.contains("-beta")
                    && !version.contains("-alpha")
                    && !version.contains("-rc");
            result.add(new LoaderVersion(version, stable));
        }
        return result;
    }

    private static List<LoaderVersion> listForge(String mcVersion) throws Exception {
        String xml = fetchForLoader("forge", mcVersion, "<version>");
        List<String> all = extractVersions(xml);
        String release = extractTag(xml, "release");
        String latest = extractTag(xml, "latest");
        String prefix = mcVersion + "-";
        List<LoaderVersion> result = new ArrayList<>();
        for (String v : all) {
            if (!v.startsWith(prefix)) continue;
            String ver = v.substring(prefix.length());
            if (ver.isEmpty()) continue;
            boolean stable = v.equals(release) || v.equals(latest);
            result.add(new LoaderVersion(ver, stable));
        }
        Collections.reverse(result);
        return result;
    }

    private static List<LoaderVersion> listNeoForge(String mcVersion) throws Exception {
        String xml = fetchForLoader("neoforge", mcVersion, "<version>");
        List<String> all = extractVersions(xml);
        String prefix = neoForgePrefix(mcVersion);
        List<LoaderVersion> result = new ArrayList<>();
        for (String v : all) {
            if (v.startsWith(prefix)) {
                result.add(new LoaderVersion(v,
                        !v.contains("-beta") && !v.contains("-alpha")));
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * NeoForge 版本号与 MC 版本的对应关系：
     *   1.21   -> 21.0.x
     *   1.21.1 -> 21.1.x
     *   1.20.6 -> 20.6.x
     * 之前的 "21." 会把 21.1.x 也匹配进 1.21，导致列出不兼容版本。
     */
    private static String neoForgePrefix(String mcVersion) {
        String stripped = mcVersion.replaceFirst("^1\\.", "");
        String[] parts = stripped.split("\\.");
        String minor = parts.length > 1 ? parts[1] : "0";
        return parts[0] + "." + minor + ".";
    }

    private static List<String> extractVersions(String xml) {
        List<String> list = new ArrayList<>();
        int i = 0;
        while ((i = xml.indexOf("<version>", i)) != -1) {
            int e = xml.indexOf("</version>", i);
            if (e == -1) break;
            list.add(xml.substring(i + 9, e).trim());
            i = e + 10;
        }
        return list;
    }

    private static String extractTag(String xml, String tag) {
        int i = xml.indexOf("<" + tag + ">");
        if (i == -1) return null;
        int e = xml.indexOf("</" + tag + ">", i);
        if (e == -1) return null;
        return xml.substring(i + tag.length() + 2, e).trim();
    }
}