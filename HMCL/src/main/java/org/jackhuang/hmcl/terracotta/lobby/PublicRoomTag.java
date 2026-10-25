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
package org.jackhuang.hmcl.terracotta.lobby;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Gameplay tags that a host can attach to a public room.
///
/// Tags travel over the wire as short ASCII identifiers, so rooms published by a
/// newer launcher stay readable by older clients: unknown identifiers are simply
/// dropped while parsing. The visible name is always translated locally instead
/// of being sent by the host.
@NotNullByDefault
public enum PublicRoomTag {
    /// Unmodified vanilla gameplay.
    VANILLA("vanilla"),

    /// Vanilla survival mode.
    SURVIVAL("survival"),

    /// Creative building oriented room.
    CREATIVE("creative"),

    /// Adventure or story map.
    ADVENTURE("adventure"),

    /// Puzzle or escape-room style map.
    PUZZLE("puzzle"),

    /// Horror themed room.
    HORROR("horror"),

    /// Parkour focused room.
    PARKOUR("parkour"),

    /// Player versus player focused room.
    PVP("pvp"),

    /// Player versus environment focused room.
    PVE("pve"),

    /// Hunter game, where hunters chase a runner.
    HUNTER("hunter"),

    /// Any other modded gameplay.
    MOD("mod");

    /// All tags in display order.
    private static final @Unmodifiable List<PublicRoomTag> ALL = List.of(values());

    /// Stable wire identifier, never translated.
    private final String id;

    /// Translation key of the visible name.
    private final String translationKey;

    PublicRoomTag(String id) {
        this.id = id;
        this.translationKey = "terracotta.tag." + id;
    }

    /// Returns every tag in display order.
    public static @Unmodifiable List<PublicRoomTag> all() {
        return ALL;
    }

    /// Resolves a wire identifier.
    ///
    /// @param id identifier received from the directory, possibly `null`
    /// @return the matching tag, or `null` when the identifier is unknown
    public static @Nullable PublicRoomTag byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (PublicRoomTag tag : ALL) {
            if (tag.id.equals(id)) {
                return tag;
            }
        }
        return null;
    }

    /// Returns the stable wire identifier.
    public String getId() {
        return id;
    }

    /// Returns the translation key of the visible name.
    public String getTranslationKey() {
        return translationKey;
    }

    /// Returns the visible name in the current launcher language.
    public String getDisplayName() {
        return i18n(translationKey);
    }
}
