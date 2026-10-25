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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Zero-cost, serverless directory of public Terracotta rooms.
///
/// The directory is built on top of a public [ntfy.sh](https://ntfy.sh) topic.
/// Hosts publish small JSON heartbeats to the topic and every launcher polls the
/// same topic, so no account, server or paid infrastructure is required.
///
/// Only rooms with [PublicRoom#VISIBILITY_PUBLIC] are published, and incoming
/// rooms are validated again so private rooms can never be displayed.
///
/// The server and topic can be overridden with the `kiln.rooms.server` and
/// `kiln.rooms.topic` system properties, which allows a community to move to a
/// self-hosted ntfy instance without changing the launcher.
@NotNullByDefault
public final class PublicRoomDirectory {
    /// Marker written into every message so unrelated traffic is ignored.
    private static final String APP_TAG = "KilnLauncher";

    /// Base URL of the ntfy server.
    private static final String SERVER = stripTrailingSlash(
            System.getProperty("kiln.rooms.server", "https://ntfy.sh"));

    /// Topic that carries public room heartbeats.
    private static final String TOPIC = System.getProperty("kiln.rooms.topic", "kilnlauncher-rooms-v1");

    /// How long a room stays listed without a heartbeat.
    public static final long ROOM_TTL_MILLIS = 4L * 60L * 1000L;

    /// How many rooms are shown at most, protecting the UI from spam.
    private static final int MAX_ROOMS = 100;

    /// Only messages from the last 20 minutes are requested. Active hosts send a
    /// heartbeat every 90 seconds, so this window is more than enough and keeps
    /// the response small even when many rooms were published earlier.
    private static final String POLL_WINDOW = "20m";

    /// Message action that creates or refreshes a room.
    private static final String ACTION_PUBLISH = "publish";

    /// Message action that removes a room immediately.
    private static final String ACTION_CLOSE = "close";

    private PublicRoomDirectory() {
    }

    /// Fetches all currently public rooms from the directory.
    ///
    /// This is a blocking network call and must not run on the JavaFX thread.
    ///
    /// @return an immutable list of public rooms, newest first
    /// @throws IOException if the directory cannot be reached
    public static @Unmodifiable List<PublicRoom> fetch() throws IOException {
        String url = SERVER + "/" + TOPIC + "/json?poll=1&since=" + POLL_WINDOW;
        String response = HttpRequest.GET(url)
                .ignoreHttpErrorCode(404)
                .getString();

        Map<String, PublicRoom> byId = new LinkedHashMap<>();
        long newestObservation = 0L;

        for (String line : response.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }

            // A single malformed message must not hide the rest of the lobby.
            try {
                @Nullable JsonObject envelope = JsonUtils.fromJson(line, JsonObject.class);
                if (envelope == null || !"message".equals(JsonUtils.getString(envelope, "event"))) {
                    continue;
                }

                @Nullable String message = JsonUtils.getString(envelope, "message");
                if (message == null) {
                    continue;
                }

                long observedAt = JsonUtils.getInt(envelope, "time", 0) * 1000L;
                if (observedAt <= 0L) {
                    observedAt = System.currentTimeMillis();
                }
                newestObservation = Math.max(newestObservation, observedAt);

                @Nullable JsonObject payload = JsonUtils.fromJson(message, JsonObject.class);
                if (payload == null
                        || !APP_TAG.equals(JsonUtils.getString(payload, "app"))
                        || JsonUtils.getInt(payload, "v", 0) != PublicRoom.PROTOCOL_VERSION) {
                    continue;
                }

                @Nullable String action = JsonUtils.getString(payload, "action");
                if (ACTION_CLOSE.equals(action)) {
                    @Nullable String roomId = JsonUtils.getString(payload, "roomId");
                    if (roomId != null) {
                        byId.remove(roomId);
                    }
                } else if (ACTION_PUBLISH.equals(action)) {
                    if (payload.has("room") && payload.get("room").isJsonObject()) {
                        @Nullable PublicRoom room = PublicRoom.fromJson(payload.getAsJsonObject("room"));
                        if (room != null) {
                            room.markObservedAt(observedAt);
                            byId.put(room.getId(), room);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed or unrelated traffic on the public topic.
            }
        }

        // Use the server timestamp when the local clock lags behind, but prefer
        // the local clock when every message is old. Without this, a room that
        // stopped minutes ago could still be considered fresh just because it
        // was the newest message in the poll window.
        long now = Math.max(System.currentTimeMillis(), newestObservation);
        List<PublicRoom> rooms = new ArrayList<>(byId.size());
        for (PublicRoom room : byId.values()) {
            if (now - room.getObservedAt() <= ROOM_TTL_MILLIS + 30_000L) {
                rooms.add(room);
            }
        }

        // Sort by creation time so heartbeats do not make rows jump around.
        rooms.sort(Comparator.comparingLong(PublicRoom::getCreatedAt).reversed());

        // A host that restarts a room may use a new identifier for the same code.
        // Keep only the freshest entry per invite code to avoid duplicate rows.
        // Password protected rooms publish no code, so they are merged by identifier.
        Map<String, PublicRoom> byKey = new LinkedHashMap<>();
        for (PublicRoom room : rooms) {
            String key = room.getCode() != null ? "code:" + room.getCode() : "id:" + room.getId();
            byKey.putIfAbsent(key, room);
            if (byKey.size() >= MAX_ROOMS) {
                break;
            }
        }

        return List.copyOf(byKey.values());
    }

    /// Publishes a heartbeat for a public room.
    ///
    /// @param room the room to publish; must be public
    /// @return the measured round-trip time to the directory in milliseconds
    /// @throws IOException if the directory cannot be reached
    public static int publish(PublicRoom room) throws IOException {
        if (!PublicRoom.VISIBILITY_PUBLIC.equals(room.getVisibility())) {
            throw new IllegalArgumentException("Only public rooms can be published");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("app", APP_TAG);
        payload.addProperty("v", PublicRoom.PROTOCOL_VERSION);
        payload.addProperty("action", ACTION_PUBLISH);
        payload.add("room", room.toJson());

        long start = System.nanoTime();
        send(payload);
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000L);
    }

    /// Removes a room from the directory immediately.
    ///
    /// @param roomId identifier of the room to remove
    /// @throws IOException if the directory cannot be reached
    public static void close(String roomId) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("app", APP_TAG);
        payload.addProperty("v", PublicRoom.PROTOCOL_VERSION);
        payload.addProperty("action", ACTION_CLOSE);
        payload.addProperty("roomId", roomId);

        send(payload);
    }

    /// Sends a directory payload to the configured ntfy topic.
    private static void send(JsonObject payload) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("topic", TOPIC);
        envelope.addProperty("message", JsonUtils.GSON.toJson(payload));

        HttpRequest.POST(SERVER + "/")
                .json(envelope)
                .getString();
    }

    /// Removes a trailing slash from a configured server URL.
    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
