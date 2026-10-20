/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.download;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.cleanroom.CleanroomRemoteVersion;
import org.jackhuang.hmcl.download.fabric.FabricRemoteVersion;
import org.jackhuang.hmcl.download.forge.ForgeRemoteVersion;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.download.neoforge.NeoForgeRemoteVersion;
import org.jackhuang.hmcl.download.optifine.OptiFineRemoteVersion;
import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.HMCLCacheRepository;
import org.jackhuang.hmcl.setting.DownloadSource;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/// Download provider used by the launcher.
///
/// Only the official upstream servers are contacted. Mirrored sources are used exclusively when the
/// user explicitly selects [DownloadSource#MIRROR]; no mirror is injected automatically, so the
/// behaviour does not depend on the region the launcher runs in, nor on third-party metadata hosts.
///
/// @author Glavo
@NotNullByDefault
public final class HMCLDownloadProvider extends DownloadProvider {
    /// Source preference used for version lists.
    private volatile DownloadSource versionListSource = DownloadSource.DEFAULT;

    /// Source preference used for downloaded files.
    private volatile DownloadSource fileSource = DownloadSource.DEFAULT;

    /// Returns the candidate list for one URL pair.
    ///
    /// @param source      the source preference; anything but [DownloadSource#MIRROR] yields the official URL only
    /// @param defaultUrl  the official URL
    /// @param bmclapiUrl  the mirrored URL, only used when `source` is [DownloadSource#MIRROR]
    /// @return the ordered candidates
    private static DownloadCandidates getCandidates(
            DownloadSource source,
            WebURL defaultUrl, WebURL bmclapiUrl
    ) {
        return switch (source) {
            case DEFAULT, OFFICIAL -> DownloadCandidates.of(defaultUrl);
            case MIRROR -> DownloadCandidates.of(bmclapiUrl, defaultUrl);
        };
    }

    /// Returns the candidate list for arbitrary URLs.
    ///
    /// @param source the source preference; anything but [DownloadSource#MIRROR] yields the URLs unchanged
    /// @param rules  the mirror rules applied when `source` is [DownloadSource#MIRROR]
    /// @param urls   the official URLs
    /// @return the ordered candidates
    private static DownloadCandidates getCandidates(DownloadSource source, List<MirrorRule> rules, List<String> urls) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("urls cannot be empty");
        }

        if (source != DownloadSource.MIRROR) {
            return DownloadCandidates.of(urls.stream().map(DownloadCandidate::of).toArray(DownloadCandidate[]::new));
        }

        var candidates = new ArrayList<DownloadCandidate>(urls.size() * 2);
        for (String url : urls) {
            DownloadCandidate candidate = DownloadCandidate.of(url);
            if (candidate.url() == null) {
                // Invalid URL, just add it to the list and let the download task handle it.
                candidates.add(candidate);
                continue;
            }

            @Nullable DownloadCandidate mirrorCandidate = null;
            for (MirrorRule rule : rules) {
                if (url.startsWith(rule.source)) {
                    mirrorCandidate = DownloadCandidate.of(rule.target + url.substring(rule.source.length()));
                    break;
                }
            }

            candidates.add(Objects.requireNonNullElse(mirrorCandidate, candidate));
            if (mirrorCandidate != null) {
                candidates.add(candidate);
            }
        }

        return DownloadCandidates.of(List.copyOf(candidates));
    }

    /// Root of the mirror used when the user explicitly selects [DownloadSource#MIRROR].
    private final String bmclRoot;

    /// Mirror rules mapping official URLs onto [#bmclRoot].
    private final List<MirrorRule> rules;

    /// Creates a provider.
    ///
    /// @param bmclRoot the root of the mirror used when the user explicitly selects [DownloadSource#MIRROR]
    public HMCLDownloadProvider(String bmclRoot) {
        this.bmclRoot = bmclRoot;
        this.rules = List.of(
                new MirrorRule("https://bmclapi2.bangbang93.com", bmclRoot),
                new MirrorRule("https://launchermeta.mojang.com", bmclRoot),
                new MirrorRule("https://piston-meta.mojang.com", bmclRoot),
                new MirrorRule("https://piston-data.mojang.com", bmclRoot),
                new MirrorRule("https://launcher.mojang.com", bmclRoot),
                new MirrorRule("https://libraries.minecraft.net", bmclRoot + "/libraries"),
                new MirrorRule("http://files.minecraftforge.net/maven", bmclRoot + "/maven"),
                new MirrorRule("https://files.minecraftforge.net/maven", bmclRoot + "/maven"),
                new MirrorRule("https://maven.minecraftforge.net", bmclRoot + "/maven"),
                new MirrorRule("https://maven.neoforged.net/releases/", bmclRoot + "/maven/"),
                new MirrorRule("http://dl.liteloader.com/versions", bmclRoot + "/maven"),
                new MirrorRule("https://dl.liteloader.com/versions", bmclRoot + "/maven"),
                new MirrorRule("https://meta.fabricmc.net", bmclRoot + "/fabric-meta"),
                new MirrorRule("https://maven.fabricmc.net", bmclRoot + "/maven"),
                new MirrorRule("https://authlib-injector.yushi.moe", bmclRoot + "/mirrors/authlib-injector")
        );
    }

    @Override
    protected Task<? extends ComponentRemoteVersionList<?>> fetchVersionsAsync(GameComponentType type, @Nullable GameVersionNumber gameVersion) {
        assert (gameVersion == null) == (type == GameComponentType.GAME);
        switch (type) {
            case FORGE -> {
                return ForgeRemoteVersion.fetchAsync(this, gameVersion);
            }
            case NEO_FORGE -> {
                return NeoForgeRemoteVersion.fetchAsync(this, gameVersion);
            }
            case OPTIFINE -> {
                // OptiFine publishes no machine-readable version list; BMCLAPI is the only source.
                return OptiFineRemoteVersion.fetchBMCLAsync(this, bmclRoot, gameVersion);
            }
        }

        return super.fetchVersionsAsync(type, gameVersion);
    }

    /// Sets the source preference for downloaded files.
    ///
    /// @param fileSource the new preference
    public void setFileSource(DownloadSource fileSource) {
        this.fileSource = Objects.requireNonNull(fileSource);
    }

    /// Sets the source preference for version lists.
    ///
    /// @param versionListSource the new preference
    public void setVersionListSource(DownloadSource versionListSource) {
        this.versionListSource = Objects.requireNonNull(versionListSource);
    }

    @Override
    public HMCLCacheRepository getCacheRepository() {
        return HMCLCacheRepository.REPOSITORY;
    }

    @Override
    public DownloadCandidates getGameVersionListCandidates() {
        return getCandidates(
                versionListSource,
                GameRemoteVersion.VERSION_MANIFEST_URL,
                WebURL.parse(bmclRoot + "/mc/game/version_manifest.json")
        );
    }

    @Override
    public FabricLikeVersionListCandidates getFabricVersionListCandidates() {
        DownloadSource source = versionListSource;
        return new FabricLikeVersionListCandidates(
                getCandidates(source, FabricRemoteVersion.LOADER_META_URL, WebURL.parse(bmclRoot + "/fabric-meta/v2/versions/loader")),
                getCandidates(source, FabricRemoteVersion.GAME_META_URL, WebURL.parse(bmclRoot + "/fabric-meta/v2/versions/game"))
        );
    }

    @Override
    public DownloadCandidates getCleanroomVersionListCandidates() {
        return DownloadCandidates.of(CleanroomRemoteVersion.LOADER_LIST_URL);
    }

    @Override
    public DownloadCandidates getDownloadCandidates(List<String> urls) {
        return getCandidates(fileSource, rules, urls);
    }

    @Override
    public DownloadCandidates getVersionListCandidates(List<String> urls) {
        return getCandidates(versionListSource, rules, urls);
    }

    @Override
    public DownloadCandidates getAssetObjectCandidates(AssetObject assetObject) {
        return getCandidates(
                fileSource,
                WebURL.parse("https://resources.download.minecraft.net/" + assetObject.getLocation()),
                WebURL.parse(bmclRoot + "/assets/" + assetObject.getLocation())
        );
    }

    /// Prefix replacement that maps an official URL onto the mirror root.
    ///
    /// @param source the official URL prefix
    /// @param target the mirror prefix replacing it
    private record MirrorRule(String source, String target) {
    }
}
