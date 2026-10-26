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
package org.jackhuang.hmcl.bilibili;

import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads, stores and clears the Bilibili account bound to this launcher.
///
/// The credentials live in `private/bilibili-account.json` inside the per-workspace data
/// directory. They are only read locally, never uploaded anywhere, and are deleted when the
/// user logs out. The file is created with owner-only permissions where the file system
/// supports them.
@NotNullByDefault
public final class BilibiliAccountManager {
    /// Name of the credential file inside the private directory.
    private static final String FILE_NAME = "bilibili-account.json";

    /// The currently bound account, or `null` when the user is logged out.
    private static volatile @Nullable BilibiliAccount account;

    /// Whether the credential file has already been probed.
    private static volatile boolean loaded;

    /// Returns the currently bound account, loading it from disk on first use.
    public static @Nullable BilibiliAccount account() {
        if (!loaded) {
            load();
        }
        return account;
    }

    /// Returns whether a usable Bilibili login is available.
    public static boolean isLoggedIn() {
        @Nullable BilibiliAccount current = account();
        return current != null && current.hasCredential();
    }

    /// Binds an account and persists it for the next start.
    ///
    /// @param newAccount the account returned by a successful QR code login
    /// @throws IOException if the credential file cannot be written
    public static synchronized void login(BilibiliAccount newAccount) throws IOException {
        save(newAccount);
        account = newAccount;
        loaded = true;
    }

    /// Forgets the bound account and removes the credential file.
    public static synchronized void logout() {
        account = null;
        loaded = true;
        Path location = location();
        try {
            Files.deleteIfExists(location);
        } catch (IOException e) {
            LOG.warning("Failed to delete " + location, e);
        }
    }

    /// Returns the path of the credential file.
    public static Path location() {
        return SettingsManager.localPrivateDirectory().resolve(FILE_NAME);
    }

    /// Reads the credential file once.
    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path location = location();
        if (!Files.isRegularFile(location)) {
            return;
        }

        try {
            @Nullable BilibiliAccount stored = JsonUtils.fromJsonFile(location, BilibiliAccount.class);
            if (stored != null && stored.hasCredential()) {
                account = stored;
            }
        } catch (Exception e) {
            LOG.warning("Failed to read " + location, e);
        }
    }

    /// Writes the account atomically, restricting the file permissions when supported.
    private static void save(BilibiliAccount newAccount) throws IOException {
        Path location = location();
        Path directory = location.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }

        Path temporary = location.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, JsonUtils.GSON.toJson(newAccount), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, location,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, location, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.setPosixFilePermissions(location, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows and other non-POSIX file systems rely on the user profile ACL instead.
        }
    }

    private BilibiliAccountManager() {
    }
}
