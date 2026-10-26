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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Minimal client for the Bilibili web login API.
///
/// Only the endpoints needed by the room lobby are implemented: creating a QR code,
/// polling its state and reading the profile of the logged-in user. The client uses the
/// official web endpoints, so the QR code can be scanned with the regular Bilibili mobile
/// application.
@NotNullByDefault
public final class BilibiliApi {
    /// User agent sent with every request, because the login endpoints reject unknown clients.
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /// Referer required by the login endpoints.
    private static final String REFERER = "https://passport.bilibili.com/";

    /// Endpoint that creates a new QR code.
    private static final String GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header";

    /// Endpoint that reports the state of a QR code.
    private static final String POLL_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";

    /// Endpoint that returns the profile of the logged-in user.
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";

    /// QR code state reported by the poll endpoint: the code has not been scanned yet.
    private static final int STATE_WAITING = 86101;

    /// QR code state reported by the poll endpoint: the code was scanned but not confirmed.
    private static final int STATE_SCANNED = 86090;

    /// QR code state reported by the poll endpoint: the code expired.
    private static final int STATE_EXPIRED = 86038;

    /// Matches absolute URLs embedded in an HTML or JSON response body.
    ///
    /// Backslashes are allowed because JSON may escape `&` as `\u0026`; the escape is
    /// decoded after the match so parameters are not cut off.
    private static final Pattern LOGIN_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

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
        JsonObject root = getJson(GENERATE_URL, null);
        @Nullable JsonObject data = getObject(root, "data");
        if (JsonUtils.getInt(root, "code", -1) != 0 || data == null) {
            throw new IOException("Bilibili rejected the QR code request: "
                    + JsonUtils.getString(root, "message", "unknown error"));
        }

        @Nullable String url = JsonUtils.getString(data, "url");
        @Nullable String key = JsonUtils.getString(data, "qrcode_key");
        if (url == null || key == null) {
            throw new IOException("Bilibili returned an incomplete QR code");
        }
        return new QrCode(url, key);
    }

    /// Polls the state of a QR code.
    ///
    /// On success the returned account contains the cookies set by the login response and
    /// the profile fetched from the nav endpoint. The profile request is best effort: a
    /// failure only leaves the display name empty and never discards a valid login.
    ///
    /// @param qrCodeKey the key returned by [ #generateQrCode() ]
    /// @return the current state of the login attempt
    /// @throws IOException if the endpoint cannot be reached or returns an error
    public static PollResult poll(String qrCodeKey) throws IOException {
        HttpURLConnection connection = open(POLL_URL
                + "?qrcode_key=" + URLEncoder.encode(qrCodeKey, StandardCharsets.UTF_8)
                + "&source=main-fe-header", null);
        String body;
        Map<String, String> cookies;
        try {
            body = NetworkUtils.readFullyAsString(connection);
            cookies = collectCookies(connection.getHeaderFields());
        } finally {
            connection.disconnect();
        }

        @Nullable JsonObject root = JsonUtils.fromJson(body, JsonObject.class);
        if (root == null) {
            throw new IOException("Bilibili returned an empty response");
        }

        @Nullable JsonObject data = getObject(root, "data");
        int state = data != null ? JsonUtils.getInt(data, "code", -1) : -1;
        if (JsonUtils.getInt(root, "code", -1) != 0) {
            throw new IOException("Bilibili login failed: "
                    + JsonUtils.getString(root, "message", "unknown error"));
        }

        switch (state) {
            case STATE_WAITING:
                return new PollResult(PollStatus.WAITING, null);
            case STATE_SCANNED:
                return new PollResult(PollStatus.SCANNED, null);
            case STATE_EXPIRED:
                return new PollResult(PollStatus.EXPIRED, null);
            default:
                break;
        }

        // Any other value means the login succeeded; older responses used 0 explicitly.
        //
        // Since 2026-08 Bilibili no longer sets the cookies on the poll response itself.
        // Instead data.url is a crossDomain ticket link that has to be visited before the
        // SESSDATA / bili_jct / DedeUserID cookies are returned through Set-Cookie.
        @Nullable String successUrl = data != null ? JsonUtils.getString(data, "url") : null;
        if (successUrl != null && !successUrl.isBlank()) {
            cookies.putAll(parseCookiesFromQuery(successUrl));
            exchangeLoginTicket(successUrl, cookies);
        }

        @Nullable String sessData = cookies.get("SESSDATA");
        if (sessData == null || sessData.isBlank()) {
            throw new IOException("Bilibili confirmed the login but returned no session cookie"
                    + " (successUrl=" + (successUrl == null || successUrl.isBlank() ? "none" : "present")
                    + ", cookies=" + cookies.keySet() + ")");
        }

        String biliJct = cookies.getOrDefault("bili_jct", "");
        String dedeUserId = cookies.getOrDefault("DedeUserID", "");
        long mid = parseLong(dedeUserId);

        @Nullable Profile profile;
        try {
            profile = fetchProfile(sessData, biliJct, dedeUserId);
        } catch (IOException e) {
            profile = null;
        }

        return new PollResult(PollStatus.SUCCESS, new BilibiliAccount(
                profile != null ? profile.mid() : mid,
                profile != null ? profile.name() : "",
                profile != null ? profile.avatarUrl() : "",
                sessData,
                biliJct,
                dedeUserId,
                System.currentTimeMillis()));
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

    /// Sends a GET request and parses the JSON body.
    private static JsonObject getJson(String url, @Nullable String cookie) throws IOException {
        HttpURLConnection connection = open(url, cookie);
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

    /// Opens a connection with the headers expected by Bilibili and follows redirects.
    ///
    /// The headers must be set before [NetworkUtils#resolveConnection] runs, because that
    /// method already connects and copies the current request properties to redirects.
    private static HttpURLConnection open(String url, @Nullable String cookie) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", REFERER);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return NetworkUtils.resolveConnection(connection);
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

    /// Builds a `Cookie` header from the collected cookie pairs.
    private static String buildCookieHeader(Map<String, String> cookies) {
        StringBuilder builder = new StringBuilder();
        for (var entry : cookies.entrySet()) {
            if (entry.getValue().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    /// Parses a decimal id, returning zero for malformed values.
    private static long parseLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /// Extracts the `Set-Cookie` pairs from response headers.
    private static Map<String, String> collectCookies(Map<String, List<String>> headers) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (var entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                continue;
            }
            for (String header : entry.getValue()) {
                int semicolon = header.indexOf(';');
                String pair = semicolon >= 0 ? header.substring(0, semicolon) : header;
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return cookies;
    }

    /// Reads cookie pairs embedded in the query string of a legacy success URL.
    ///
    /// The values stay URL-encoded on purpose: this is exactly how Bilibili stores them
    /// inside the cookie itself, so decoding would corrupt `SESSDATA`.
    private static Map<String, String> parseCookiesFromQuery(String url) {
        Map<String, String> cookies = new LinkedHashMap<>();
        int question = url.indexOf('?');
        if (question < 0) {
            return cookies;
        }

        String query = url.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }

        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = pair.substring(0, equals);
            if ("SESSDATA".equals(name) || "bili_jct".equals(name) || "DedeUserID".equals(name)) {
                cookies.put(name, pair.substring(equals + 1));
            }
        }
        return cookies;
    }

    /// Exchanges the crossDomain ticket for the real login cookies.
    ///
    /// The ticket response sets most cookies but not `SESSDATA`: its body contains an
    /// `sso` link that has to be visited as well, exactly like a browser would do.
    private static void exchangeLoginTicket(String successUrl, Map<String, String> cookies) {
        // First try exactly like the reference implementation: a bare request without the
        // poll session cookies, which is what a fresh browser-like client would send.
        @Nullable String body = fetchAndCollect(successUrl, cookies, false);
        if (cookies.containsKey("SESSDATA") || followLoginLinks(body, cookies)) {
            return;
        }

        // Retry with the cookies collected from the poll response. Some accounts only
        // complete the exchange when the buvid3/sid of the same session is present.
        body = fetchAndCollect(successUrl, cookies, true);
        if (cookies.containsKey("SESSDATA") || followLoginLinks(body, cookies)) {
            return;
        }

        // The ticket is tied to one passport host. Retry on the other host and its
        // crossDomain path before giving up.
        Map<String, String> query = parseQuery(successUrl);
        @Nullable String ticket = query.get("ticket");
        if (ticket == null || ticket.isBlank()) {
            return;
        }

        String gourl = query.getOrDefault("gourl", "https://www.bilibili.com");
        String[] candidates = {
                "https://passport.biligame.com/x/passport-login/web/crossDomain",
                "https://passport.bilibili.com/x/passport-login/web/crossDomain",
                "https://passport.biligame.com/crossDomain"
        };
        for (String candidate : candidates) {
            @Nullable String retryBody = fetchAndCollect(candidate
                            + "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)
                            + "&gourl=" + URLEncoder.encode(gourl, StandardCharsets.UTF_8), cookies, true);
            if (cookies.containsKey("SESSDATA") || followLoginLinks(retryBody, cookies)) {
                return;
            }
        }
    }

    /// Follows the `sso` links embedded in a crossDomain response.
    ///
    /// @return whether the session cookie was obtained
    private static boolean followLoginLinks(@Nullable String body, Map<String, String> cookies) {
        if (body == null) {
            return false;
        }

        Matcher matcher = LOGIN_URL_PATTERN.matcher(body.replace("\\/", "/"));
        int followed = 0;
        while (matcher.find() && followed < 5) {
            String url = matcher.group()
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&");
            while (!url.isEmpty() && ",;)]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.toLowerCase(Locale.ROOT).contains("sso")) {
                continue;
            }

            followed++;
            cookies.putAll(parseCookiesFromQuery(url));
            fetchAndCollect(url, cookies, true);
            if (cookies.containsKey("SESSDATA")) {
                return true;
            }
        }
        return false;
    }

    /// Fetches a URL, collecting `Set-Cookie` headers from every redirect hop.
    ///
    /// @param sendCookies whether the cookies collected so far are sent with the request
    /// @return the final response body, or `null` when it could not be read
    private static @Nullable String fetchAndCollect(String url, Map<String, String> cookies, boolean sendCookies) {
        String current = url;
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection connection = null;
            try {
                connection = NetworkUtils.createHttpConnection(current);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Referer", "https://passport.bilibili.com/");
                connection.setRequestProperty("Accept", "text/html,application/json,*/*");
                connection.setInstanceFollowRedirects(false);

                // Carry over cookies set by earlier hops (buvid3, sid, ...), because the
                // ticket exchange validates the same browser session.
                if (sendCookies) {
                    String cookieHeader = buildCookieHeader(cookies);
                    if (!cookieHeader.isEmpty()) {
                        connection.setRequestProperty("Cookie", cookieHeader);
                    }
                }

                int code = connection.getResponseCode();
                cookies.putAll(collectCookies(connection.getHeaderFields()));

                @Nullable String location = connection.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null && !location.isBlank()) {
                    current = new URL(connection.getURL(), location).toString();
                    continue;
                }

                if (code / 100 == 2) {
                    return NetworkUtils.readFullyAsString(connection);
                }
                return null;
            } catch (IOException e) {
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return null;
    }

    /// Parses a query string into URL-decoded values.
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        int question = url.indexOf('?');
        if (question < 0) {
            return query;
        }

        String raw = url.substring(question + 1);
        int fragment = raw.indexOf('#');
        if (fragment >= 0) {
            raw = raw.substring(0, fragment);
        }

        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            query.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    private BilibiliApi() {
    }
}
