package com.hafij.apphub;

import java.util.ArrayList;
import java.util.List;

/** One app in the catalog plus what the last check found out about it. */
final class AppEntry {
    String id;
    String name;
    String repo;
    /** Null for user-added apps until their first APK is downloaded. */
    String pkg;
    String icon;
    boolean prerelease;
    boolean self;
    boolean custom;

    // Filled by Updates.check().
    Release release;
    Asset asset;
    Asset sums;
    String installedVersion;
    String error;
    boolean stale;

    boolean installed() {
        return installedVersion != null;
    }

    boolean hasUpdate() {
        return release != null && asset != null && installed()
                && Versions.newer(release.version, installedVersion);
    }

    static final class Release {
        String tag;
        String name;
        String body;
        String htmlUrl;
        String version;
        String published;
        final List<Asset> assets = new ArrayList<>();
    }

    static final class Asset {
        String name;
        String url;
        long size;
    }
}
