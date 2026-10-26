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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Client for the Bilibili TV QR code login API.
///
/// The TV flow is used instead of the web flow because Bilibili split the web login into
/// a crossDomain ticket exchange that does not always return `SESSDATA`. The TV poll
/// endpoint returns the cookies directly in `data.cookie_info.cookies`, which makes the
/// login reliable without following extra redirects.
@NotNullByDefault
public final class BilibiliApi {
    /// User agent sent with every request, because the login endpoints reject unknown clients.
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /// Referer required by the login endpoints.
    private static final String REFERER = "https://www.bilibili.com/";

    /// Bilibili TV (云视听小电视) app key.
    private static final String APP_KEY = "4409e2ce8ffd12b8";

    /// App secret belonging to [#APP_KEY], used to sign the requests.
    private static final String APP_SECRET = "59b43e04ad6965f34319062b478f83dd";

    /// Device id expected by the TV endpoints. Zero is accepted.
    private static final String LOCAL_ID = "0";

    /// Timestamp used in the signature. The TV endpoints accept a fixed zero.
    private static final String TIMESTAMP = "0";

    /// Endpoint that creates a new TV login QR code.
    private static final String GENERATE_URL =
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code";

    /// Endpoint that reports the state of a TV login QR code.
    private static final String POLL_URL =
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll";

    /// Endpoint that returns the profile of the logged-in user.
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";

    /// Poll code: the QR code expired.
    private static final int STATE_EXPIRED = 86038;

    /// Poll code: the code was scanned and waits for confirmation on the phone.
    private static final int STATE_SCANNED = 86090;

    /// Poll code used by the TV endpoint for "scanned, not confirmed yet".
    private static final int STATE_SCANNED_TV = 86039;

    /// Poll code: the code has not been scanned yet.
    private static final int STATE_WAITING = 86101;

    /// A newly created QR code.
    ///
    /// @param url the content encoded in the QR code, which the mobile app understands
    /// @param key the opaque key used to poll the login state
    public record QrCode(String url, String key) {
    }

    /// The state of a QR code login attempt.
    public enum PollStatus {
        /// The code has not been scanned yet.
        WAITING,

        /// The code was scanned and waits for confirmation on the phone.
        SCANNED,

        /// The code expired and a new one must be requested.
        EXPIRED,

        /// The login succeeded and [#account] holds the credentials.
        SUCCESS
    }

    /// The result of a poll request.
    ///
    /// @param status the current state
    /// @param account the logged-in account, only present when the status is [PollStatus#SUCCESS]
    public record PollResult(PollStatus status, @Nullable BilibiliAccount account) {
    }

    /// A public profile returned by the nav endpoint.
    ///
    /// @param mid the numeric user id
    /// @param name the display name
    /// @param avatarUrl the avatar URL, empty when Bilibili did not return one
    private record Profile(long mid, String name, String avatarUrl) {
    }

    /// Creates a new QR code that can be scanned by the Bilibili mobile application.
    ///
    /// @return the QR code content and polling key
    /// @throws IOException if the endpoint cannot be reached or returns an error
    public static QrCode generateQrCode() throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appkey", APP_KEY);
        params.put("local_id", LOCAL_ID);
        params.put("ts", TIMESTAMP);
        params.put("sign", sign(params));

        JsonObject root = postForm(GENERATE_URL, params);
        @Nullable JsonObject data = getObject(root, "data");
        if (JsonUtils.getInt(root, "code", -1) != 0 || data == null) {
            throw new IOException("Bilibili rejected the TV QR code request: "
                    + JsonUtils.getString(root, "message", "unknown error"));
        }

        @Nullable String url = JsonUtils.getString(data, "url");
        @Nullable String key = JsonUtils.getString(data, "auth_code");
        if (url == null || key == null) {
            throw new IOException("Bilibili returned an incomplete TV QR code");
        }
        return new QrCode(url, key);
    }

    /// Polls the state of a TV login QR code.
    ///
    /// On success the returned account contains the cookies from `cookie_info.cookies`
    /// and the profile fetched from the nav endpoint. The profile request is best effort:
    /// a failure only leaves the display name empty and never discards a valid login.
    ///
    /// @param authCode the key returned by [#generateQrCode()]
    /// @return the current state of the login attempt
    /// @throws IOException if the endpoint cannot be reached or returns an error
    public static PollResult poll(String authCode) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appkey", APP_KEY);
        params.put("auth_code", authCode);
        params.put("local_id", LOCAL_ID);
        params.put("ts", TIMESTAMP);
        params.put("sign", sign(params));

        JsonObject root = postForm(POLL_URL, params);
        int code = JsonUtils.getInt(root, "code", -1);
        @Nullable JsonObject data = getObject(root, "data");

        switch (code) {
            case 0:
                if (data == null || getObject(data, "cookie_info") == null) {
                    return new PollResult(PollStatus.WAITING, null);
                }
                return new PollResult(PollStatus.SUCCESS, accountFrom(data));
            case STATE_WAITING:
            case STATE_SCANNED_TV:
                // The TV endpoint reports "not confirmed" for both the unscanned and the
                // scanned state, so both map to waiting.
                return new PollResult(PollStatus.WAITING, null);
            case STATE_SCANNED:
                return new PollResult(PollStatus.SCANNED, null);
            case STATE_EXPIRED:
                return new PollResult(PollStatus.EXPIRED, null);
            default:
                throw new IOException("Bilibili TV login failed: code=" + code
                        + ", message=" + JsonUtils.getString(root, "message", "unknown error"));
        }
    }

    /// Builds the account from a successful TV poll response.
    private static BilibiliAccount accountFrom(JsonObject data) throws IOException {
        Map<String, String> cookies = new LinkedHashMap<>();
        @Nullable JsonObject cookieInfo = getObject(data, "cookie_info");
        if (cookieInfo != null) {
            JsonElement cookiesElement = cookieInfo.get("cookies");
            if (cookiesElement != null && cookiesElement.isJsonArray()) {
                JsonArray array = cookiesElement.getAsJsonArray();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject cookie = element.getAsJsonObject();
                    @Nullable String name = JsonUtils.getString(cookie, "name");
                    @Nullable String value = JsonUtils.getString(cookie, "value");
                    if (name != null && value != null) {
                        cookies.put(name, value);
                    }
                }
            }
        }

        @Nullable String sessData = cookies.get("SESSDATA");
        if (sessData == null || sessData.isBlank()) {
            throw new IOException("Bilibili confirmed the TV login but returned no SESSDATA"
                    + " (cookies=" + cookies.keySet() + ")");
        }

        String biliJct = cookies.getOrDefault("bili_jct", "");
        String dedeUserId = cookies.getOrDefault("DedeUserID", "");
        long mid = getLong(data, "mid", parseLong(dedeUserId));

        @Nullable Profile profile;
        try {
            profile = fetchProfile(sessData, biliJct, dedeUserId);
        } catch (IOException e) {
            profile = null;
        }

        return new BilibiliAccount(
                profile != null ? profile.mid() : mid,
                profile != null ? profile.name() : "",
                profile != null ? profile.avatarUrl() : "",
                sessData,
                biliJct,
                dedeUserId,
                System.currentTimeMillis());
    }

    /// Fetches the public profile belonging to the given cookies.
    ///
    /// @return the profile, or `null` when the session is not valid
    /// @throws IOException if the endpoint cannot be reached
    private static @Nullable Profile fetchProfile(String sessData, String biliJct, String dedeUserId) throws IOException {
        String cookie = "SESSDATA=" + sessData
                + (biliJct.isBlank() ? "" : "; bili_jct=" + biliJct)
                + (dedeUserId.isBlank() ? "" : "; DedeUserID=" + dedeUserId);

        JsonObject root = getJson(NAV_URL, cookie);
        @Nullable JsonObject data = getObject(root, "data");
        if (data == null || !getBoolean(data, "isLogin")) {
            return null;
        }

        return new Profile(
                JsonUtils.getInt(data, "mid", 0),
                JsonUtils.getString(data, "uname", ""),
                JsonUtils.getString(data, "face", ""));
    }

    /// Sends a form-encoded POST request and parses the JSON body.
    private static JsonObject postForm(String url, Map<String, String> params) throws IOException {
        StringBuilder body = new StringBuilder(128);
        for (var entry : params.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", REFERER);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (var output = connection.getOutputStream()) {
                output.write(payload);
            }

            @Nullable JsonObject root = JsonUtils.fromJson(
                    NetworkUtils.readFullyAsString(connection), JsonObject.class);
            if (root == null) {
                throw new IOException("Bilibili returned an empty response");
            }
            return root;
        } finally {
            connection.disconnect();
        }
    }

    /// Sends a GET request and parses the JSON body.
    private static JsonObject getJson(String url, @Nullable String cookie) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", REFERER);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        try {
            @Nullable JsonObject root = JsonUtils.fromJson(
                    NetworkUtils.readFullyAsString(connection), JsonObject.class);
            if (root == null) {
                throw new IOException("Bilibili returned an empty response");
            }
            return root;
        } finally {
            connection.disconnect();
        }
    }

    /// Returns the named object member, or `null` when the member is missing or not an object.
    private static @Nullable JsonObject getObject(JsonObject root, String key) {
        var element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /// Returns the named boolean member, defaulting to `false`.
    private static boolean getBoolean(JsonObject root, String key) {
        var element = root.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    /// Returns the named long member, defaulting to `defaultValue` when missing or malformed.
    private static long getLong(JsonObject root, String key, long defaultValue) {
        var element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /// Parses a decimal id, returning zero for malformed values.
    private static long parseLong(@Nullable String value) {        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /// Signs the parameters as required by the TV endpoints.
    ///
    /// The signature is the MD5 of the alphabetically sorted `key=value` pairs joined with
    /// `&`, followed by the app secret.
    private static String sign(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        StringBuilder builder = new StringBuilder(128);
        for (String key : keys) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(key).append('=').append(params.get(key));
        }
        builder.append(APP_SECRET);

        return md5Hex(builder.toString());
    }

    /// Returns the lower-case hexadecimal MD5 digest of the given text.
    private static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 is required by the Java platform", e);
        }
    }

    private BilibiliApi() {
    }
}
