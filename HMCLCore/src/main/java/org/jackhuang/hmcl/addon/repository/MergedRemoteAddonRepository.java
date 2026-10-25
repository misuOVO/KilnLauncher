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
package org.jackhuang.hmcl.addon.repository;

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// Presents Modrinth and CurseForge as one searchable repository.
///
/// Searching both sources at once avoids the situation where a mod is missing from the
/// selected source even though the other one has it. The two entries are merged and
/// deduplicated by name, so a project published on both platforms is only listed once.
///
/// CurseForge identifiers are numeric while Modrinth identifiers are not, which allows
/// resolving a project or file without remembering which source it came from.
@NotNullByDefault
public final class MergedRemoteAddonRepository implements RemoteAddonRepository {
    /// Repository that is queried first and provides categories.
    private final RemoteAddonRepository primary;

    /// Repository that is queried second.
    private final RemoteAddonRepository secondary;

    /// Type of add-on offered by this repository.
    private final Type type;

    /// Creates a merged repository.
    ///
    /// @param type add-on type served by this repository
    /// @param primary repository queried first, usually Modrinth
    /// @param secondary repository queried second, usually CurseForge
    public MergedRemoteAddonRepository(Type type, RemoteAddonRepository primary, RemoteAddonRepository secondary) {
        this.type = type;
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public SearchResult search(DownloadProvider downloadProvider, String gameVersion, @Nullable Category category,
                               int pageOffset, int pageSize, String searchFilter, SortType sortType,
                               SortOrder sortOrder) throws IOException {
        // Categories are source specific, so a filtered search only uses the primary source.
        if (category != null) {
            return primary.search(downloadProvider, gameVersion, category, pageOffset, pageSize,
                    searchFilter, sortType, sortOrder);
        }

        SearchResult first = primary.search(downloadProvider, gameVersion, null, pageOffset, pageSize,
                searchFilter, sortType, sortOrder);
        SearchResult second;
        try {
            second = secondary.search(downloadProvider, gameVersion, null, pageOffset, pageSize,
                    searchFilter, sortType, sortOrder);
        } catch (IOException e) {
            // One source being down must not hide the results of the other one.
            return first;
        }

        Set<String> seen = new HashSet<>();
        Stream<RemoteAddon> merged = Stream.concat(first.getResults(), second.getResults())
                .filter(addon -> addon == RemoteAddon.BROKEN || seen.add(key(addon)));

        return new SearchResult(merged, Math.max(first.getTotalPages(), second.getTotalPages()));
    }

    @Override
    public Optional<RemoteAddon.Version> getRemoteVersionByLocalFile(Path file) throws IOException {
        Optional<RemoteAddon.Version> result = primary.getRemoteVersionByLocalFile(file);
        if (result.isPresent()) {
            return result;
        }
        return secondary.getRemoteVersionByLocalFile(file);
    }

    @Override
    public RemoteAddon getModById(DownloadProvider downloadProvider, String id) throws IOException {
        return route(id).getModById(downloadProvider, id);
    }

    @Override
    public RemoteAddon resolveDependency(DownloadProvider downloadProvider, String id) throws IOException {
        return route(id).resolveDependency(downloadProvider, id);
    }

    @Override
    public RemoteAddon.File getModFile(String modId, String fileId) throws IOException {
        return route(modId).getModFile(modId, fileId);
    }

    @Override
    public Stream<RemoteAddon.Version> getRemoteVersionsById(DownloadProvider downloadProvider, String id) throws IOException {
        return route(id).getRemoteVersionsById(downloadProvider, id);
    }

    @Override
    public Stream<Category> getCategories() throws IOException {
        return primary.getCategories();
    }

    /// Chooses the source that owns an identifier.
    ///
    /// CurseForge project and file identifiers are numeric, Modrinth identifiers never are.
    private RemoteAddonRepository route(@Nullable String id) {
        if (id != null && !id.isEmpty() && id.chars().allMatch(Character::isDigit)) {
            return secondary;
        }
        return primary;
    }

    /// Returns a key used to drop duplicates that exist on both platforms.
    private static String key(RemoteAddon addon) {
        String slug = addon.slug() == null ? "" : addon.slug().trim().toLowerCase(Locale.ROOT);
        String title = addon.title() == null ? "" : addon.title().trim().toLowerCase(Locale.ROOT);
        return slug + '|' + title;
    }
}
