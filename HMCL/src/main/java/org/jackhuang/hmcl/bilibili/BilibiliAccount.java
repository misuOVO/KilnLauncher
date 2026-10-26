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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// A Bilibili account bound to this launcher through QR code login.
///
/// The record keeps the cookies returned by the login endpoint so the launcher can call
/// the Bilibili API on behalf of the user, together with the public profile shown in the
/// room lobby. Instances are persisted by [BilibiliAccountManager] and never leave the
/// local machine.
///
/// @param mid the numeric Bilibili user id
/// @param name the display name shown in the launcher
/// @param avatarUrl the avatar URL returned by the profile API, empty when unknown
/// @param sessData the `SESSDATA` cookie, which is the actual login credential
/// @param biliJct the `bili_jct` cookie used as a CSRF token by Bilibili
/// @param dedeUserId the `DedeUserID` cookie
/// @param savedAt the epoch millisecond timestamp at which the account was stored
@NotNullByDefault
public record BilibiliAccount(
        long mid,
        String name,
        String avatarUrl,
        String sessData,
        String biliJct,
        String dedeUserId,
        long savedAt) {

    /// Validates the required fields of the account.
    public BilibiliAccount {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(avatarUrl, "avatarUrl");
        Objects.requireNonNull(sessData, "sessData");
        Objects.requireNonNull(biliJct, "biliJct");
        Objects.requireNonNull(dedeUserId, "dedeUserId");
    }

    /// Returns the `Cookie` header used for authenticated Bilibili API requests.
    public String cookieHeader() {
        StringBuilder builder = new StringBuilder(128);
        appendCookie(builder, "SESSDATA", sessData);
        appendCookie(builder, "bili_jct", biliJct);
        appendCookie(builder, "DedeUserID", dedeUserId);
        return builder.toString();
    }

    /// Returns whether the account still carries a non-empty login credential.
    public boolean hasCredential() {
        return !sessData.isBlank();
    }

    /// Appends one cookie pair, skipping blank values.
    private static void appendCookie(StringBuilder builder, String key, String value) {
        if (value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(key).append('=').append(value);
    }
}
