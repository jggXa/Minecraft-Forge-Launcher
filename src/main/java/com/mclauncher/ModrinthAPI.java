package com.mclauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ModrinthAPI {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String UA = BuildInfo.APP_NAME + "/" + BuildInfo.VERSION
            + " (github.com/jggXa/mc-launcher)";

    public static class ModProject {
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String iconUrl;
        public long downloads;
        public List<String> categories = new ArrayList<>();
        public List<String> gameVersions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
    }

    public static class ModVersion {
        public String id;
        public String name;
        public String versionNumber;
        public List<String> gameVersions = new ArrayList<>();
        public List<String> loaders = new ArrayList<>();
        public String downloadUrl;
        public String filename;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!loaders.isEmpty()) {
                sb.append("[").append(String.join("/", loaders)).append("] ");
            }
            sb.append(versionNumber);
            if (!gameVersions.isEmpty()) {
                sb.append("  for ").append(String.join(", ", gameVersions));
            }
            return sb.toString();
        }
    }

    public static List<ModProject> search(String query, int offset, int limit) throws Exception {
        String q = query == null ? "" : query;
        String facets = "[[\"project_type:mod\"]]";
        String url = BASE + "/search?query=" + enc(q)
                + "&limit=" + limit + "&offset=" + offset
                + "&facets=" + enc(facets);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Modrinth API " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray hits = root.getAsJsonArray("hits");
        List<ModProject> out = new ArrayList<>();
        if (hits != null) {
            for (JsonElement e : hits) {
                JsonObject o = e.getAsJsonObject();
                ModProject p = new ModProject();
                p.projectId = getStr(o, "project_id");
                p.slug = getStr(o, "slug");
                p.title = getStr(o, "title");
                p.description = getStr(o, "description");
                p.iconUrl = getStr(o, "icon_url");
                p.downloads = o.has("downloads") && !o.get("downloads").isJsonNull()
                        ? o.get("downloads").getAsLong() : 0;
                p.categories = getList(o, "categories");
                p.gameVersions = getList(o, "versions");
                p.loaders = getList(o, "loaders");
                out.add(p);
            }
        }
        return out;
    }

    public static List<ModVersion> getVersions(String projectId) throws Exception {
        String url = BASE + "/project/" + projectId + "/version";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Modrinth API " + resp.statusCode());
        }

        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        List<ModVersion> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            ModVersion v = new ModVersion();
            v.id = getStr(o, "id");
            v.name = getStr(o, "name");
            v.versionNumber = getStr(o, "version_number");
            v.gameVersions = getList(o, "game_versions");
            v.loaders = getList(o, "loaders");

            if (o.has("files") && o.get("files").isJsonArray()) {
                JsonArray files = o.getAsJsonArray("files");
                String fallbackUrl = null, fallbackName = null;
                for (JsonElement fe : files) {
                    JsonObject fo = fe.getAsJsonObject();
                    String u = getStr(fo, "url");
                    String n = getStr(fo, "filename");
                    boolean primary = fo.has("primary") && !fo.get("primary").isJsonNull()
                            && fo.get("primary").getAsBoolean();
                    if (primary) {
                        v.downloadUrl = u;
                        v.filename = n;
                        break;
                    }
                    if (fallbackUrl == null) {
                        fallbackUrl = u;
                        fallbackName = n;
                    }
                }
                if (v.downloadUrl == null) {
                    v.downloadUrl = fallbackUrl;
                    v.filename = fallbackName;
                }
            }
            if (v.downloadUrl != null) out.add(v);
        }
        return out;
    }

    /** 通过 HttpClient 拉取图片字节（带 UA），再用 ImageIO 解码。修复 WebP 格式无法解析的问题 */
    public static BufferedImage loadImage(String url) throws Exception {
        if (url == null || url.isBlank()) return null;

        // 如果图片是 .webp 后缀，尝试替换为 .png 获取，因为 JDK 原生 ImageIO 不支持 webp
        if (url.endsWith(".webp")) {
            url = url.substring(0, url.length() - 5) + ".png";
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            // 如果 png 失败，降级尝试原 url
            if (url.endsWith(".png")) {
                String fallbackUrl = url.substring(0, url.length() - 4) + ".webp";
                HttpRequest fallbackReq = HttpRequest.newBuilder(URI.create(fallbackUrl))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();
                HttpResponse<byte[]> fallbackResp = CLIENT.send(fallbackReq, HttpResponse.BodyHandlers.ofByteArray());
                if (fallbackResp.statusCode() == 200) {
                    return ImageIO.read(new ByteArrayInputStream(fallbackResp.body()));
                }
            }
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(resp.body()));
    }

    private static String getStr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static List<String> getList(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(k)) {
                if (!e.isJsonNull()) out.add(e.getAsString());
            }
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}