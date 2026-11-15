package com.mclauncher;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public class DownloadUtil {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_2)
            .build();

    private static final HttpClient FAST_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .version(HttpClient.Version.HTTP_2)
            .build();

    private static final int MAX_RETRIES = 4;
    private static volatile int bufferSize = 64 * 1024;
    public static volatile boolean paused = false;
    /** 关闭启动器时置 true，所有下载循环会尽快抛出 InterruptedException。 */
    public static volatile boolean cancelled = false;

    public static void setBufferSize(int bytes) {
        if (bytes >= 1024) bufferSize = bytes;
    }

    public static int getBufferSize() {
        return bufferSize;
    }

    public static String downloadString(String url) throws Exception {
        Exception lastException = null;
        boolean retryable = true;

        for (int i = 1; i <= MAX_RETRIES; i++) {
            if (cancelled) throw new RuntimeException("下载已取消");
            boolean useMirror = (i % 2 == 0);
            String realUrl = useMirror ? Mirror.rewrite(url) : url;

            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(realUrl))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                int code = resp.statusCode();
                retryable = isRetryableHttp(code);
                throw new RuntimeException("HTTP " + code);
            } catch (Exception e) {
                lastException = e;
                if (e instanceof IllegalArgumentException || e instanceof java.nio.file.InvalidPathException) {
                    retryable = false;
                }
                if (!retryable) break;
                if (i < MAX_RETRIES) sleep(1000L * i);
            }
        }
        throw new RuntimeException("网络连接失败: " + readableError(lastException), lastException);
    }

    /** 版本列表这类小请求：短连接超时、少重试，避免卡几分钟。 */
    public static String downloadStringFast(String url, int timeoutSeconds, int attempts) throws Exception {
        Exception lastException = null;
        for (int i = 1; i <= Math.max(1, attempts); i++) {
            if (cancelled) throw new InterruptedException("下载已取消");
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .GET().build();
                HttpResponse<String> resp = FAST_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
                lastException = new RuntimeException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                lastException = e;
            }
            if (i < attempts) sleep(250);
        }
        throw new RuntimeException("网络连接失败: " + readableError(lastException), lastException);
    }

    public static void downloadFile(String url, Path target, String expectedSha1, FileProgressListener listener) throws Exception {
        if (Files.exists(target) && expectedSha1 != null && !expectedSha1.isBlank()) {
            if (expectedSha1.equalsIgnoreCase(sha1(target))) {
                if (listener != null) {
                    long size = Files.size(target);
                    listener.onStart(url, size);
                    listener.onProgress(url, size, size);
                    listener.onEnd(url);
                }
                return;
            }
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        Exception lastException = null;
        boolean retryable = true;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) throw new InterruptedException("下载已取消");
            boolean useMirror = (attempt % 2 == 0);
            String realUrl = useMirror ? Mirror.rewrite(url) : url;

            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(realUrl))
                        .timeout(Duration.ofSeconds(45))
                        .GET().build();

                HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    int code = resp.statusCode();
                    retryable = isRetryableHttp(code);
                    throw new RuntimeException("HTTP " + code);
                }

                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (listener != null) listener.onStart(realUrl, contentLength);

                long downloaded = 0;
                byte[] buf = new byte[bufferSize];
                int n;
                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(tmp)) {
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled) throw new InterruptedException("下载已取消");
                        while (paused) {
                            if (cancelled || Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException("下载已中断");
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw e;
                            }
                        }
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (listener != null) listener.onProgress(realUrl, downloaded, contentLength);
                    }
                }

                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    String actual = sha1(tmp);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                        retryable = true;
                        throw new RuntimeException("SHA-1 不匹配 (期望: " + expectedSha1 + ", 实际: " + actual + ")");
                    }
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                if (listener != null) listener.onEnd(realUrl);
                return;

            } catch (Exception e) {
                lastException = e;
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                if (cancelled || Thread.currentThread().isInterrupted()) break;
                if (!retryable) break;
                if (attempt < MAX_RETRIES) sleep(2000L * attempt);
            }
        }
        throw new RuntimeException("下载失败: " + readableError(lastException) + " (文件: " + url + ")", lastException);
    }

    private static boolean isRetryableHttp(int code) {
        return code >= 500 || code == 408 || code == 429;
    }

    private static String readableError(Exception e) {
        if (e == null) return "未知错误";
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof HttpTimeoutException) return "连接超时";
        if (cause instanceof ConnectException) return "连接被拒绝/重置";
        if (cause.getMessage() != null && cause.getMessage().contains("HTTP")) {
            return cause.getMessage();
        }
        return cause.getClass().getSimpleName() + ": " + (cause.getMessage() != null ? cause.getMessage() : "无详细信息");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}