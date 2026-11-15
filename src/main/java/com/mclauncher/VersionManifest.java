package com.mclauncher;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

public class VersionManifest {
    public Latest latest;
    public List<VersionEntry> versions;

    public static class Latest {
        public String release;
        public String snapshot;
    }

    public static class VersionEntry {
        public String id;
        public String type;
        public String url;
        public String sha1;
        public String releaseTime;
    }

    public static class VersionDetail {
        public String id;
        public String inheritsFrom;
        public String mainClass;
        public String assets;
        public AssetIndex assetIndex;
        public String minecraftArguments;
        public Arguments arguments;
        public List<Library> libraries;
        public VersionDownloads downloads;
        public JavaVersion javaVersion;
        public String type;
        public String jar;
    }

    public static class AssetIndex {
        public String id;
        public String sha1;
        public long size;
        public long totalSize;
        public String url;
    }

    public static class Arguments {
        public List<Object> game;
        public List<Object> jvm;
    }

    public static class Library {
        public String name;
        public Downloads downloads;
        public Map<String, String> natives;
        public Extract extract;
        public List<Rule> rules;
    }

    public static class Downloads {
        public Artifact artifact;
        public Map<String, Artifact> classifiers;
    }

    public static class Artifact {
        public String path;
        public String sha1;
        public long size;
        public String url;
    }

    public static class Extract {
        public List<String> exclude;
    }

    public static class Rule {
        public String action;
        public Os os;
        public Map<String, Boolean> features;
    }

    public static class Os {
        public String name;
        public String version;
        public String arch;
    }

    public static class JavaVersion {
        public String component;
        public int majorVersion;
    }

    public static class VersionDownloads {
        public VersionArtifact client;
        public VersionArtifact server;
        @SerializedName("client_mappings") public VersionArtifact clientMappings;
        @SerializedName("server_mappings") public VersionArtifact serverMappings;
    }

    public static class VersionArtifact {
        public String sha1;
        public long size;
        public String url;
    }
}