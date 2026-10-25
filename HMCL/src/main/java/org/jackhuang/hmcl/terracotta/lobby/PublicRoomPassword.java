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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/// Encrypts the invite code of a password protected public room.
///
/// The room lobby is a public topic, so hiding the invite code in the UI would be
/// pointless: anyone could read it from the raw messages. Therefore the host encrypts
/// the code with a key derived from the room password, and guests can only recover it
/// by typing that password.
///
/// A room password protects against casual strangers, not against a determined
/// attacker: the payload is public and the password is chosen by the host, so a short
/// password can still be brute forced offline.
@NotNullByDefault
public final class PublicRoomPassword {
    /// Marker of the current payload format, written in front of the base64 body.
    private static final String PREFIX = "v1:";

    /// PBKDF2 iteration count, a compromise between resistance and UI latency.
    private static final int ITERATIONS = 100_000;

    /// Length of the random salt in bytes.
    private static final int SALT_BYTES = 16;

    /// Length of the random AES-GCM nonce in bytes.
    private static final int IV_BYTES = 12;

    /// Length of the AES key in bits.
    private static final int KEY_BITS = 256;

    /// Length of the GCM authentication tag in bits.
    private static final int TAG_BITS = 128;

    /// Source of the random salt and nonce.
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Base64 codec used for the payload body.
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /// Base64 codec used for the payload body.
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PublicRoomPassword() {
    }

    /// Encrypts an invite code with the given password.
    ///
    /// @param code invite code to protect
    /// @param password room password chosen by the host
    /// @return the encrypted payload, safe to publish on the public topic
    /// @throws GeneralSecurityException if the platform cannot perform AES-GCM
    public static String encrypt(String code, String password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));

        byte[] payload = new byte[salt.length + iv.length + encrypted.length];
        System.arraycopy(salt, 0, payload, 0, salt.length);
        System.arraycopy(iv, 0, payload, salt.length, iv.length);
        System.arraycopy(encrypted, 0, payload, salt.length + iv.length, encrypted.length);
        return PREFIX + ENCODER.encodeToString(payload);
    }

    /// Decrypts an invite code with the given password.
    ///
    /// @param payload encrypted payload produced by [#encrypt]
    /// @param password password typed by the guest
    /// @return the invite code, or `null` when the password is wrong or the payload is malformed
    public static @Nullable String decrypt(@Nullable String payload, String password) {
        if (payload == null || !payload.startsWith(PREFIX)) {
            return null;
        }

        byte[] raw;
        try {
            raw = DECODER.decode(payload.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }

        // The payload must at least carry the salt, the nonce and the GCM tag.
        if (raw.length <= SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
            return null;
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(raw, 0, salt, 0, SALT_BYTES);
        System.arraycopy(raw, SALT_BYTES, iv, 0, IV_BYTES);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(raw, SALT_BYTES + IV_BYTES, raw.length - SALT_BYTES - IV_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // A wrong password fails the GCM tag check, which is indistinguishable
            // from a corrupted payload; both mean "cannot unlock".
            return null;
        }
    }

    /// Returns whether the payload looks like a value produced by [#encrypt].
    ///
    /// Used when parsing rooms so a malformed payload is rejected early instead of
    /// showing an unlock prompt that can never succeed.
    ///
    /// @param payload value received from the lobby, possibly `null`
    public static boolean isWellFormed(@Nullable String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) {
            return false;
        }
        try {
            return DECODER.decode(payload.substring(PREFIX.length())).length > SALT_BYTES + IV_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /// Derives the AES key from the password and salt.
    private static SecretKey deriveKey(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
