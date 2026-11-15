package com.mclauncher;

public interface FileProgressListener {
    void onStart(String url, long total);
    void onProgress(String url, long downloaded, long total);
    void onEnd(String url);
}