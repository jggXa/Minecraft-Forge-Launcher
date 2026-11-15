package com.mclauncher;

/**
 * UI 桥接接口。CLI 的 Ui 和 GUI 的 GuiUi 都实现它，
 * 使 ModpackInstaller 等业务类不依赖具体 UI 实现。
 */
public interface ProgressUi extends FileProgressListener {
    void println(String s);
    void setInfo(int line, String text);
    void clearInfo();
    void progress(String task, int percent);
    void finishProgress();
}