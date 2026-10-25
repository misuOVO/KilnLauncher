/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.versions;

import org.jackhuang.hmcl.game.LocalizedRemoteAddonRepository;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.MergedRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.i18n.I18n;
import javafx.application.Platform;
import org.jetbrains.annotations.Nullable;

import java.util.MissingResourceException;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class HMCLLocalizedDownloadListPage extends DownloadListPage {
    /// Source identifier of Modrinth.
    private static final String SOURCE_MODRINTH = "addon.modrinth";

    /// Source identifier of CurseForge.
    private static final String SOURCE_CURSEFORGE = "addon.curseforge";

    /// Source identifier of the merged search over both platforms.
    private static final String SOURCE_MERGED = "addon.merged";

    public static DownloadListPage ofMod(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.MOD, CurseForgeRemoteAddonRepository.MODS, ModrinthRemoteAddonRepository.MODS);
    }

    public static DownloadListPage ofCurseForgeMod(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.MOD, CurseForgeRemoteAddonRepository.MODS, null);
    }

    public static DownloadListPage ofModrinthMod(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.MOD, null, ModrinthRemoteAddonRepository.MODS);
    }

    public static DownloadListPage ofModPack(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.MODPACK, CurseForgeRemoteAddonRepository.MODPACKS, ModrinthRemoteAddonRepository.MODPACKS);
    }

    public static DownloadListPage ofResourcePack(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.RESOURCE_PACK, CurseForgeRemoteAddonRepository.RESOURCE_PACKS, ModrinthRemoteAddonRepository.RESOURCE_PACKS);
    }

    public static DownloadListPage ofCurseForgeResourcePack(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.RESOURCE_PACK, CurseForgeRemoteAddonRepository.RESOURCE_PACKS, null);
    }

    public static DownloadListPage ofModrinthResourcePack(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        return new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.RESOURCE_PACK, null, ModrinthRemoteAddonRepository.RESOURCE_PACKS);
    }

    public static DownloadListPage ofShaderPack(DownloadPage.DownloadCallback callback, boolean versionSelection) {
        var page = new HMCLLocalizedDownloadListPage(callback, versionSelection, RemoteAddonRepository.Type.SHADER_PACK, CurseForgeRemoteAddonRepository.SHADERS, ModrinthRemoteAddonRepository.SHADER_PACKS);
        page.supportChinese.set(false);
        return page;
    }

    private HMCLLocalizedDownloadListPage(DownloadPage.DownloadCallback callback, boolean versionSelection, RemoteAddonRepository.Type type, CurseForgeRemoteAddonRepository curseForge, ModrinthRemoteAddonRepository modrinth) {
        super(null, callback, versionSelection);

        boolean supportedCurseForge = CurseForgeRemoteAddonRepository.isAvailable() && curseForge != null;
        if (modrinth == null && !supportedCurseForge) {
            throw new AssertionError("No add-on source is available");
        }

        RemoteAddonRepository curseForgeRepository = supportedCurseForge ? curseForge : null;
        MergedRemoteAddonRepository merged = modrinth != null && curseForgeRepository != null
                ? new MergedRemoteAddonRepository(type, modrinth, curseForgeRepository)
                : null;

        repository = new Repository(type, modrinth, curseForgeRepository, merged);

        supportChinese.set(true);

        // CurseForge is always listed so the source selector stays visible. Selecting it
        // without a configured API key explains what is missing instead of silently
        // falling back to Modrinth.
        downloadSources.clear();
        if (modrinth != null) {
            downloadSources.add(SOURCE_MODRINTH);
        }
        downloadSources.add(SOURCE_CURSEFORGE);
        if (merged != null) {
            downloadSources.add(SOURCE_MERGED);
        }

        String defaultSource = settings().defaultAddonSourceProperty().get();
        if (SOURCE_CURSEFORGE.equalsIgnoreCase(defaultSource) && curseForgeRepository != null) {
            downloadSource.set(SOURCE_CURSEFORGE);
        } else if (SOURCE_MERGED.equalsIgnoreCase(defaultSource) && merged != null) {
            downloadSource.set(SOURCE_MERGED);
        } else if (modrinth != null) {
            downloadSource.set(SOURCE_MODRINTH);
        } else {
            downloadSource.set(SOURCE_CURSEFORGE);
        }

        downloadSource.addListener((observable, oldValue, newValue) -> {
            // Only react to a choice made in the combo box. The initial default may be
            // CurseForge while no key is configured, which must not pop a dialog every time
            // the page opens.
            if (!sourceSelectionReady) {
                return;
            }
            if (SOURCE_CURSEFORGE.equals(newValue) && curseForgeRepository == null) {
                Platform.runLater(() -> {
                    Controllers.dialog(
                            i18n("download.curseforge.unavailable"),
                            i18n("message.warning"),
                            MessageDialogPane.MessageType.WARNING);
                    downloadSource.set(modrinth != null ? SOURCE_MODRINTH : SOURCE_CURSEFORGE);
                });
            }
        });

        Platform.runLater(() -> sourceSelectionReady = true);
    }

    /// Whether the source combo box is ready for user interaction.
    private boolean sourceSelectionReady;

    private class Repository extends LocalizedRemoteAddonRepository {
        private final RemoteAddonRepository.Type type;
        private final @Nullable RemoteAddonRepository modrinth;
        private final @Nullable RemoteAddonRepository curseForge;
        private final @Nullable MergedRemoteAddonRepository merged;

        public Repository(RemoteAddonRepository.Type type,
                          @Nullable RemoteAddonRepository modrinth,
                          @Nullable RemoteAddonRepository curseForge,
                          @Nullable MergedRemoteAddonRepository merged) {
            this.type = type;
            this.modrinth = modrinth;
            this.curseForge = curseForge;
            this.merged = merged;
        }

        @Override
        protected RemoteAddonRepository getBackedRemoteModRepository() {
            String source = downloadSource.get();
            if (SOURCE_MERGED.equals(source) && merged != null) {
                return merged;
            }
            if (SOURCE_CURSEFORGE.equals(source) && curseForge != null) {
                return curseForge;
            }
            return modrinth != null ? modrinth : curseForge;
        }

        @Override
        protected SortType getBackedRemoteModRepositorySortOrder() {
            return SOURCE_CURSEFORGE.equals(downloadSource.get()) ? SortType.POPULARITY : SortType.NAME;
        }

        @Override
        public Type getType() {
            return type;
        }
    }

    @Override
    protected String getLocalizedCategory(String category, Object self) {
        if (category.isEmpty()) {
            return "";
        }

        String key = (SOURCE_CURSEFORGE.equals(downloadSource.get()) ? "curse" : "modrinth") + ".category." + category;
        try {
            return I18n.getResourceBundle().getString(key);
        } catch (MissingResourceException e) {
            LOG.warning("Cannot find key " + key + " in resource bundle");
            if (self instanceof CurseForgeRemoteAddonRepository.Category curseCategory) {
                return curseCategory.getName();
            }
            return category;
        }
    }

    @Override
    protected String getLocalizedOfficialPage() {
        String source = downloadSource.get();
        if (SOURCE_CURSEFORGE.equals(source)) {
            return i18n("addon.curseforge");
        } else if (SOURCE_MERGED.equals(source)) {
            return i18n("addon.merged");
        } else {
            return i18n("addon.modrinth");
        }
    }
}
