package com.mclauncher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

public class InstallVersionDialog extends JDialog {
    private final Path gameDir;
    private final Settings settings;
    private boolean installed = false;

    private JTextField versionField;
    private JComboBox<String> loaderCombo;
    private JComboBox<String> loaderVersionCombo;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel fileLabel;
    private JButton startBtn;

    public InstallVersionDialog(Frame owner, Path gameDir, Settings settings) {
        super(owner, "安装新版本", true);
        this.gameDir = gameDir;
        this.settings = settings;
        initComponents();
    }

    public boolean isInstalled() { return installed; }

    private void initComponents() {
        setSize(560, 360);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        form.add(new JLabel("Minecraft 版本:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        versionField = new JTextField("1.20.1");
        form.add(versionField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        form.add(new JLabel("加载器:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        loaderCombo = new JComboBox<>(new String[]{"无", "Forge", "NeoForge", "Fabric", "Quilt"});
        form.add(loaderCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        form.add(new JLabel("加载器版本:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        loaderVersionCombo = new JComboBox<>();
        loaderVersionCombo.setEnabled(false);
        form.add(loaderVersionCombo, gbc);

        add(form, BorderLayout.NORTH);

        JPanel progressPanel = new JPanel(new BorderLayout(4, 4));
        progressPanel.setBorder(new EmptyBorder(8, 12, 8, 12));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        statusLabel = new JLabel("准备中...");
        fileLabel = new JLabel("");
        fileLabel.setForeground(Theme.TEXT_MUTED);

        JPanel statusBox = new JPanel(new BorderLayout());
        statusBox.add(statusLabel, BorderLayout.WEST);
        statusBox.add(fileLabel, BorderLayout.EAST);

        progressPanel.add(progressBar, BorderLayout.NORTH);
        progressPanel.add(statusBox, BorderLayout.SOUTH);
        add(progressPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        startBtn = new JButton("开始");
        JButton cancelBtn = new JButton("取消");
        buttons.add(startBtn);
        buttons.add(cancelBtn);
        add(buttons, BorderLayout.SOUTH);

        loaderCombo.addActionListener(e -> onLoaderChanged());
        startBtn.addActionListener(e -> startInstall());
        cancelBtn.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(startBtn);
    }

    private void onLoaderChanged() {
        String loader = (String) loaderCombo.getSelectedItem();
        if ("无".equals(loader)) {
            loaderVersionCombo.removeAllItems();
            loaderVersionCombo.setEnabled(false);
            return;
        }

        String mcVersion = versionField.getText().trim();
        if (mcVersion.isEmpty()) return;

        String loaderId = switch (loader) {
            case "Forge" -> "forge";
            case "NeoForge" -> "neoforge";
            case "Fabric" -> "fabric";
            case "Quilt" -> "quilt";
            default -> null;
        };
        if (loaderId == null) return;

        loaderVersionCombo.removeAllItems();
        loaderVersionCombo.addItem("加载中...");
        loaderVersionCombo.setEnabled(false);

        new Thread(() -> {
            try {
                List<LoaderResolver.LoaderVersion> versions = LoaderResolver.list(loaderId, mcVersion);
                SwingUtilities.invokeLater(() -> {
                    loaderVersionCombo.removeAllItems();
                    if (versions.isEmpty()) {
                        loaderVersionCombo.addItem("(无可用版本)");
                        loaderVersionCombo.setEnabled(false);
                    } else {
                        for (var v : versions) {
                            loaderVersionCombo.addItem(v.version() + (v.stable() ? " (稳定)" : ""));
                        }
                        loaderVersionCombo.setEnabled(true);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    loaderVersionCombo.removeAllItems();
                    loaderVersionCombo.addItem("(查询失败)");
                    loaderVersionCombo.setEnabled(false);
                });
            }
        }, "Loader-List").start();
    }

    private void startInstall() {
        String mcVersion = versionField.getText().trim();
        if (mcVersion.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入 Minecraft 版本号");
            return;
        }

        String loader = (String) loaderCombo.getSelectedItem();
        String loaderId = null, loaderVer = null;
        if (!"无".equals(loader)) {
            loaderId = switch (loader) {
                case "Forge" -> "forge";
                case "NeoForge" -> "neoforge";
                case "Fabric" -> "fabric";
                case "Quilt" -> "quilt";
                default -> null;
            };
            String sel = (String) loaderVersionCombo.getSelectedItem();
            if (sel == null || sel.startsWith("(")) {
                JOptionPane.showMessageDialog(this, "请等待加载器版本列表加载完成");
                return;
            }
            loaderVer = sel.replace(" (稳定)", "");
        }

        startBtn.setEnabled(false);
        loaderCombo.setEnabled(false);
        loaderVersionCombo.setEnabled(false);
        versionField.setEnabled(false);
        statusLabel.setText("开始安装...");

        GuiUi gui = new GuiUi(statusLabel, progressBar, fileLabel, s -> {});
        final String finalLoaderId = loaderId;
        final String finalLoaderVer = loaderVer;

        new Thread(() -> {
            try {
                VersionInstaller.install(mcVersion, gameDir,
                        (task, pct) -> gui.progress(task, pct * 80 / 100), gui);
                if (finalLoaderId != null) {
                    LoaderInstaller.install(finalLoaderId, mcVersion, finalLoaderVer,
                            gameDir, settings,
                            (task, pct) -> gui.progress(task, 80 + pct * 20 / 100), gui);
                }
                SwingUtilities.invokeLater(() -> {
                    installed = true;
                    statusLabel.setText("安装成功");
                    JOptionPane.showMessageDialog(this, "安装成功！");
                    dispose();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("安装失败");
                    JOptionPane.showMessageDialog(this,
                            "安装失败: " + e.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                    startBtn.setEnabled(true);
                    loaderCombo.setEnabled(true);
                    versionField.setEnabled(true);
                });
            }
        }, "Install-Thread").start();
    }
}