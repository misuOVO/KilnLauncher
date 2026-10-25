/*
 * KilnLauncher
 * Copyright (C) 2026  KilnLauncher contributors
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
package org.jackhuang.hmcl.ui.download;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves the required dependencies of a mod before it is installed.
///
/// Modrinth and CurseForge both describe which projects a mod version needs, and HMCL already
/// listed them, but every dependency still had to be added by hand. This resolver walks the
/// required dependencies transitively, picks a version that fits the target instance and drops
/// the ones that are already installed.
///
/// Only [RemoteAddon.DependencyType#REQUIRED] entries are installed automatically. Optional
/// dependencies stay untouched because forcing them can change the game in unwanted ways.
@NotNullByDefault
public final class ModDependencyResolver {
    /// Maximum dependency depth that is followed, protecting against cycles in the metadata.
    private static final int MAX_DEPTH = 4;

    /// Outcome of a dependency resolution.
    ///
    /// @param pending versions of required dependencies that still have to be downloaded
    /// @param installed required dependencies that are already present in the instance
    /// @param unresolved required dependencies without a version matching the instance
    public record Resolution(
            @Unmodifiable List<RemoteAddon.Version> pending,
            @Unmodifiable List<RemoteAddon> installed,
            @Unmodifiable List<RemoteAddon> unresolved) {
        /// Returns whether nothing has to be downloaded.
        public boolean isEmpty() {
            return pending.isEmpty();
        }
    }

    /// Resolves the required dependencies of a mod version.
    ///
    /// Network access happens through [DownloadProvider]; the method is blocking and must not
    /// run on the JavaFX thread.
    ///
    /// @param downloadProvider provider used to reach the mod repository
    /// @param repository game repository that owns the target instance
    /// @param instanceId instance the mod will be installed into, `null` when unknown
    /// @param root version that the user selected for installation
    /// @return the dependencies to install, already installed and unresolvable ones
    public static Resolution resolve(
            DownloadProvider downloadProvider,
            HMCLGameRepository repository,
            @Nullable String instanceId,
            RemoteAddon.Version root) {
        if (instanceId == null || !repository.hasVersion(instanceId)) {
            return new Resolution(List.of(), List.of(), List.of());
        }

        Target target = Target.load(repository, instanceId);

        List<RemoteAddon.Version> pending = new ArrayList<>();
        List<RemoteAddon> installed = new ArrayList<>();
        List<RemoteAddon> unresolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Deque<RemoteAddon.Version> queue = new ArrayDeque<>();
        queue.add(root);

        for (int depth = 0; depth < MAX_DEPTH && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                RemoteAddon.Version version = queue.removeFirst();
                for (RemoteAddon.Dependency dependency : version.dependencies()) {
                    if (dependency.getType() != RemoteAddon.DependencyType.REQUIRED) {
                        continue;
                    }

                    RemoteAddon addon;
                    try {
                        addon = dependency.load(downloadProvider);
                    } catch (IOException e) {
                        LOG.warning("Failed to resolve mod dependency " + dependency.getId(), e);
                        continue;
                    }
                    if (addon == RemoteAddon.BROKEN || !seen.add(key(addon))) {
                        continue;
                    }

                    if (target.isInstalled(addon)) {
                        installed.add(addon);
                        continue;
                    }

                    RemoteAddon.Version picked;
                    try {
                        picked = pickVersion(downloadProvider, dependency.getRemoteModRepository(), addon, target);
                    } catch (IOException e) {
                        LOG.warning("Failed to list versions of " + addon.slug(), e);
                        unresolved.add(addon);
                        continue;
                    }
                    if (picked == null) {
                        unresolved.add(addon);
                        continue;
                    }

                    pending.add(picked);
                    queue.add(picked);
                }
            }
        }

        return new Resolution(List.copyOf(pending), List.copyOf(installed), List.copyOf(unresolved));
    }

    /// Chooses the newest version that supports the target game version and mod loader.
    ///
    /// Versions without any game version or loader information are treated as compatible,
    /// because several CurseForge files leave those lists empty.
    private static @Nullable RemoteAddon.Version pickVersion(
            DownloadProvider downloadProvider,
            RemoteAddonRepository repository,
            RemoteAddon addon,
            Target target) throws IOException {
        List<RemoteAddon.Version> versions = addon.data().loadVersions(repository, downloadProvider).toList();

        return versions.stream()
                .filter(version -> target.accepts(version))
                .max(Comparator.comparing(ModDependencyResolver::publishedAt))
                .orElse(null);
    }

    /// Returns the publish time, treating a missing timestamp as the oldest possible value.
    private static Instant publishedAt(RemoteAddon.Version version) {
        return version.datePublished() != null ? version.datePublished() : Instant.EPOCH;
    }

    /// Returns a stable key used to deduplicate dependencies across repositories.
    private static String key(RemoteAddon addon) {
        return normalize(addon.slug()) + '|' + normalize(addon.title());
    }

    /// Normalizes a mod name for case-insensitive comparison.
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /// Description of the instance a mod is installed into.
    ///
    /// @param gameVersion game version of the instance, `null` when it cannot be determined
    /// @param loaders mod loaders installed in the instance
    /// @param ids lower-cased mod ids of the installed mods
    /// @param names lower-cased display names of the installed mods
    private record Target(
            @Nullable String gameVersion,
            @Unmodifiable Set<ModLoaderType> loaders,
            @Unmodifiable Set<String> ids,
            @Unmodifiable Set<String> names) {

        /// Builds the target description from the instance contents.
        static Target load(HMCLGameRepository repository, String instanceId) {
            Set<String> ids = new HashSet<>();
            Set<String> names = new HashSet<>();
            Set<ModLoaderType> loaders = Set.of();

            ModManager manager = new ModManager(repository, instanceId);
            try {
                manager.refresh();
                LibraryAnalyzer analyzer = manager.getLibraryAnalyzer();
                if (analyzer != null) {
                    loaders = Set.copyOf(analyzer.getModLoaders());
                }
                for (LocalModFile file : manager.getLocalFiles()) {
                    ids.add(normalize(file.getId()));
                    names.add(normalize(file.getName()));
                }
            } catch (IOException e) {
                LOG.warning("Failed to scan installed mods of " + instanceId, e);
            }

            String gameVersion = null;
            try {
                Version version = repository.getResolvedPreservingPatchesVersion(instanceId);
                gameVersion = repository.getGameVersion(version).orElse(null);
            } catch (RuntimeException e) {
                LOG.warning("Failed to determine the game version of " + instanceId, e);
            }

            return new Target(gameVersion, loaders, Set.copyOf(ids), Set.copyOf(names));
        }

        /// Returns whether the mod is already installed.
        ///
        /// Modrinth slugs usually match the mod id, while CurseForge dependencies are project
        /// ids that never match. Comparing the display name covers those cases.
        boolean isInstalled(RemoteAddon addon) {
            return ids.contains(normalize(addon.slug())) || names.contains(normalize(addon.title()));
        }

        /// Returns whether a version supports this instance.
        boolean accepts(RemoteAddon.Version version) {
            if (gameVersion != null
                    && !version.gameVersions().isEmpty()
                    && !version.gameVersions().contains(gameVersion)) {
                return false;
            }
            if (loaders.isEmpty() || version.loaders().isEmpty()) {
                return true;
            }
            return version.loaders().stream().anyMatch(loaders::contains);
        }
    }

    private ModDependencyResolver() {
    }
}
