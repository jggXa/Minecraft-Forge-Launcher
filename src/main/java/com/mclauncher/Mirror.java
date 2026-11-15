package com.mclauncher;

public class Mirror {

    public enum Type {
        OFFICIAL("官方"),
        BMCLAPI("BMCLAPI (国内加速)"),
        HKMC("HKMC (备用镜像)");

        public final String label;

        Type(String label) {
            this.label = label;
        }

        public static Type fromId(String id) {
            if (id == null) return OFFICIAL;
            for (Type t : values()) {
                if (t.name().equalsIgnoreCase(id)) return t;
            }
            return OFFICIAL;
        }
    }

    public enum LoaderType {
        OFFICIAL("官方源"),
        MIRROR("国内镜像 (BMCLAPI)"),
        BACKUP("备用源 (ForgeCDN)");

        public final String label;

        LoaderType(String label) {
            this.label = label;
        }

        public static LoaderType fromId(String id) {
            if (id == null) return MIRROR;
            for (LoaderType t : values()) {
                if (t.name().equalsIgnoreCase(id)) return t;
            }
            return MIRROR;
        }
    }

    private static Type currentMc = Type.OFFICIAL;
    private static LoaderType currentLoader = LoaderType.MIRROR;

    public static void set(Type t) {
        currentMc = t;
    }

    public static Type get() {
        return currentMc;
    }

    public static void setLoader(LoaderType t) {
        currentLoader = t;
    }

    public static LoaderType getLoader() {
        return currentLoader;
    }

    /** 当前设置优先，其余按 国内镜像 → 官方 → 备用 的默认顺序。 */
    public static java.util.List<LoaderType> loaderOrder() {
        java.util.List<LoaderType> order = new java.util.ArrayList<>();
        order.add(currentLoader);
        for (LoaderType t : new LoaderType[]{LoaderType.MIRROR, LoaderType.OFFICIAL, LoaderType.BACKUP}) {
            if (!order.contains(t)) order.add(t);
        }
        return order;
    }

    /** 重写 Mojang 官方下载地址。 */
    public static String rewrite(String url) {
        if (url == null || currentMc == Type.OFFICIAL) return url;
        
        if (currentMc == Type.HKMC) {
            return url
                    .replace("https://piston-meta.mojang.com", "https://bsmirror.hkmc.online")
                    .replace("https://piston-data.mojang.com", "https://bsmirror.hkmc.online")
                    .replace("https://launchermeta.mojang.com", "https://bsmirror.hkmc.online")
                    .replace("https://launcher.mojang.com", "https://bsmirror.hkmc.online")
                    .replace("https://resources.download.minecraft.net", "https://bsmirror.hkmc.online/assets")
                    .replace("https://libraries.minecraft.net", "https://bsmirror.hkmc.online/maven");
        }
        
        // BMCLAPI
        return url
                .replace("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://piston-data.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://launcher.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets")
                .replace("https://libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven");
    }

    /** 重写模组加载器下载地址。 */
    public static String rewriteLoader(String url) {
        return rewriteLoader(url, currentLoader);
    }

    public static String rewriteLoader(String url, LoaderType type) {
        if (url == null || type == LoaderType.OFFICIAL) return url;
        if (type == LoaderType.BACKUP) {
            return url
                    .replace("https://maven.neoforged.net/releases", "https://neoforged.forgecdn.net/releases")
                    .replace("https://maven.minecraftforge.net", "https://files.minecraftforge.net/maven");
        }
        return url
                .replace("https://maven.neoforged.net/releases", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://files.minecraftforge.net/maven", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.minecraftforge.net", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.fabricmc.net", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.quiltmc.org/repository/release", "https://bmclapi2.bangbang93.com/maven");
    }
}