package com.mclauncher;

@FunctionalInterface
public interface ProgressCallback {
    void update(String task, int percent);

    /**
     * 批量下载进度更新：除了 task / percent 之外，还提供已完成数 / 总数，
     * 用于"剩余文件数"显示。默认实现仅调用 update，CLI 等只实现 update 的 lambda 不受影响。
     */
    default void updateFiles(String task, int percent, int done, int total) {
        update(task, percent);
    }
}