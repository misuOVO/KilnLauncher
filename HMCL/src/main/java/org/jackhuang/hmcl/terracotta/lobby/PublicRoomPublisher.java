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

import javafx.application.Platform;
import org.jackhuang.hmcl.terracotta.TerracottaManager;
import org.jackhuang.hmcl.terracotta.TerracottaState;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Keeps the host's public room visible in [PublicRoomDirectory].
///
/// A room is published only after the host explicitly enables it. While it is
/// published, a heartbeat is sent every 60 seconds; the lobby hides the room as
/// soon as the heartbeats stop, so a crash cannot leave a stale room listed for
/// longer than [PublicRoomDirectory#ROOM_TTL_MILLIS]. Leaving the hosted room
/// also sends an immediate close message.
///
/// All network work runs on a private daemon executor; callbacks are invoked on
/// the JavaFX application thread.
@NotNullByDefault
public final class PublicRoomPublisher {
    /// Seconds between two heartbeats of a public room.
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60L;

    /// Shared instance used by the launcher UI.
    private static final PublicRoomPublisher INSTANCE = new PublicRoomPublisher();

    /// Returns the shared publisher instance.
    public static PublicRoomPublisher instance() {
        return INSTANCE;
    }

    /// Executes directory requests without blocking the UI.
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "KilnLauncher-PublicRoom");
                thread.setDaemon(true);
                return thread;
            });

    /// Whether the Terracotta state listener has been installed.
    private boolean initialized;

    /// Incremented whenever a publication is started or stopped, invalidating
    /// callbacks from requests that are still in flight.
    private long generation;

    /// The room currently published by this launcher, or `null` when private.
    private @Nullable PublicRoom currentRoom;

    /// Plain invite code of the currently hosted room, or `null` when nothing is published.
    ///
    /// The published payload may only contain an encrypted code, but republishing after a
    /// tag or password change still needs the original code.
    private @Nullable String plainCode;

    /// Password chosen by the host for the public room. Empty means no password is set.
    private String password = "";

    /// Custom room title typed by the host, empty to use [#baseTitle].
    private String roomTitle = "";

    /// Default room title derived from the hosted game instance.
    private String baseTitle = "";

    /// Whether the host enabled the password option.
    ///
    /// The room is only locked when this is enabled **and** a password was typed; an
    /// empty password cannot protect anything and would make the room impossible to
    /// unlock for a guest who does not know it is empty.
    private boolean passwordEnabled;

    /// Tags the host selected for the next publication.
    ///
    /// The selection is kept outside [PublicRoom] so that it survives closing and
    /// reopening the hosted room, and so the tag chips can be restored when the
    /// controller page is rebuilt after every Terracotta state change.
    private @Unmodifiable List<PublicRoomTag> selectedTags = List.of();

    /// Pending heartbeat task, or `null` when nothing is published.
    private @Nullable ScheduledFuture<?> heartbeat;

    private PublicRoomPublisher() {
    }

    /// Returns the tags the host selected for the next publication.
    public synchronized @Unmodifiable List<PublicRoomTag> getSelectedTags() {
        return selectedTags;
    }

    /// Returns the password chosen for the next publication, empty when none is set.
    public synchronized String getPassword() {
        return password;
    }

    /// Returns the custom room title typed by the host, empty when the instance name is used.
    public synchronized String getRoomTitle() {
        return roomTitle;
    }

    /// Replaces the custom room title and republishes immediately when a room is live.
    ///
    /// @param title the new title, empty to fall back to the hosted instance name
    public void setRoomTitle(@Nullable String title) {
        final @Nullable PublicRoom room;
        synchronized (this) {
            String normalized = title == null ? "" : title.trim();
            if (normalized.equals(roomTitle)) {
                return;
            }
            roomTitle = normalized;
            room = currentRoom;
        }

        republishQuietly(room);
    }

    /// Records the default title used when the host did not type a custom one.
    ///
    /// @param title title derived from the hosted game instance
    public synchronized void setBaseTitle(@Nullable String title) {
        baseTitle = title == null ? "" : title.trim();
    }

    /// Returns whether the host enabled the room password option.
    public synchronized boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    /// Enables or disables the room password and republishes immediately.
    ///
    /// Disabling the option clears the stored password, so the invite code becomes
    /// visible in the lobby again.
    ///
    /// @param enabled whether the room should be protected by a password
    public void setPasswordEnabled(boolean enabled) {
        final @Nullable PublicRoom room;
        synchronized (this) {
            passwordEnabled = enabled;
            if (!enabled) {
                password = "";
            }
            room = currentRoom;
        }

        republishQuietly(room);
    }

    /// Replaces the room password and republishes immediately when a room is live.
    ///
    /// The invite code is encrypted with the new password before it leaves the launcher,
    /// so guests must type the password to see or use the code.
    ///
    /// @param newPassword the new password, `null` or empty to clear it
    public void setPassword(@Nullable String newPassword) {
        final @Nullable PublicRoom room;
        synchronized (this) {
            password = newPassword == null ? "" : newPassword;
            room = currentRoom;
        }

        if (passwordEnabled) {
            republishQuietly(room);
        }
    }

    /// Republishes a live room after a publish option changed, logging failures.
    private void republishQuietly(@Nullable PublicRoom room) {
        if (room == null) {
            return;
        }
        publish(room, error -> {
            if (error != null) {
                LOG.warning("Failed to update public room options", error);
            }
        });
    }

    /// Adds or removes a single tag and republishes the room when it is live.
    ///
    /// @param tag the tag to toggle
    /// @param selected whether the tag should be selected
    public void toggleTag(PublicRoomTag tag, boolean selected) {
        List<PublicRoomTag> tags;
        synchronized (this) {
            tags = new ArrayList<>(selectedTags);
            if (selected) {
                if (!tags.contains(tag)) {
                    tags.add(tag);
                }
            } else {
                tags.remove(tag);
            }
        }
        setTags(tags);
    }

    /// Replaces the tag selection and republishes immediately when a room is live.
    ///
    /// A tag change is cheap, so the room is republished instead of waiting for the
    /// next heartbeat; otherwise the lobby would keep showing the old tags for up to
    /// a minute.
    ///
    /// @param tags the new selection, may be empty
    public void setTags(@Unmodifiable List<PublicRoomTag> tags) {
        final @Nullable PublicRoom room;
        synchronized (this) {
            selectedTags = normalizeTags(tags);
            room = currentRoom;
        }

        if (room != null) {
            publish(room.withTags(selectedTags), error -> {
                if (error != null) {
                    LOG.warning("Failed to update public room tags", error);
                }
            });
        }
    }

    /// Deduplicates and copies a tag selection, preserving the given order.
    private static @Unmodifiable List<PublicRoomTag> normalizeTags(@Unmodifiable List<PublicRoomTag> tags) {
        Set<PublicRoomTag> distinct = new LinkedHashSet<>(tags);
        return List.copyOf(new ArrayList<>(distinct));
    }

    /// Installs the Terracotta state listener once.
    ///
    /// Must be called on the JavaFX application thread.
    public void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Stop exposing the room as soon as the host leaves it or the room fails.
        FXUtils.onChangeAndOperate(TerracottaManager.stateProperty(), this::onStateChanged);
    }

    /// Returns whether the room with the given invite code is currently published.
    ///
    /// @param code Terracotta invite code
    public synchronized boolean isPublished(String code) {
        return currentRoom != null && plainCode != null && plainCode.equalsIgnoreCase(code);
    }

    /// Returns whether the given lobby row is the room published by this launcher.
    ///
    /// Rows of password protected rooms do not carry a plain invite code, so the room
    /// identifier is compared as well.
    ///
    /// @param room lobby row to test
    public synchronized boolean isPublished(PublicRoom room) {
        if (currentRoom == null) {
            return false;
        }
        if (currentRoom.getId().equals(room.getId())) {
            return true;
        }
        @Nullable String code = room.getCode();
        return code != null && plainCode != null && plainCode.equalsIgnoreCase(code);
    }

    /// Publishes a room and starts its heartbeat.
    ///
    /// The callback runs on the JavaFX application thread. It receives `null` on
    /// success and the failure cause otherwise. On failure the room stays unpublished.
    ///
    /// When a password is set, the invite code is encrypted before the first message is
    /// sent, so the plain code never reaches the public topic.
    ///
    /// @param room room to publish; a locked room reuses the code of the previous publication
    /// @param callback completion callback
    public void publish(PublicRoom room, Consumer<@Nullable Throwable> callback) {
        ensureInitialized();

        final String code = room.getCode() != null
                ? room.getCode()
                : plainCode;
        if (code == null) {
            // A locked room without a remembered plain code can never be republished.
            notifyCallback(callback, new IllegalStateException("No invite code to publish"));
            return;
        }

        final PublicRoom encoded;
        try {
            encoded = encode(applyTitle(room), code);
        } catch (GeneralSecurityException e) {
            notifyCallback(callback, e);
            return;
        }

        final long publishGeneration;
        synchronized (this) {
            generation++;
            publishGeneration = generation;
            cancelHeartbeat();
            plainCode = code;
            currentRoom = encoded;
        }

        executor.execute(() -> {
            final int latencyMs;
            try {
                // The first request of a run pays for DNS resolution and the TLS
                // handshake, which would be reported as several hundred milliseconds
                // of latency. Publish once to register the room, then measure again.
                PublicRoomDirectory.publish(encoded);
                latencyMs = PublicRoomDirectory.publish(encoded);

                // The measurement cannot be part of the requests it measures, so send a
                // final message carrying it. Otherwise the lobby would show "latency
                // unknown" until the next heartbeat, up to a minute later.
                if (latencyMs > 0) {
                    PublicRoomDirectory.publish(encoded.withLatency(latencyMs));
                }
            } catch (Throwable e) {
                synchronized (this) {
                    if (generation == publishGeneration) {
                        currentRoom = null;
                        plainCode = null;
                    }
                }
                notifyCallback(callback, e);
                return;
            }

            synchronized (this) {
                if (generation != publishGeneration || currentRoom == null) {
                    // The host turned the switch off while the first heartbeat was
                    // in flight. Remove the room again so it does not linger.
                    closeQuietly(encoded.getId());
                    return;
                }
                // Report the latency of the request that just succeeded, so guests
                // see a real measured value instead of a placeholder.
                currentRoom = currentRoom.withLatency(latencyMs);
                heartbeat = executor.scheduleWithFixedDelay(
                        () -> sendHeartbeat(publishGeneration),
                        HEARTBEAT_INTERVAL_SECONDS,
                        HEARTBEAT_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
            }

            notifyCallback(callback, null);
        });
    }

    /// Applies the custom or default title to a room payload.
    ///
    /// @param room room whose title should be published
    /// @return the payload with the effective title
    private PublicRoom applyTitle(PublicRoom room) {
        String title = roomTitle;
        if (title.isEmpty()) {
            title = baseTitle.isEmpty() ? room.getTitle() : baseTitle;
        }
        return room.withTitle(title);
    }

    /// Applies the current password to a room payload.
    ///
    /// @param room room whose invite code should be published
    /// @param code plain invite code of the hosted room
    /// @return the payload to send to the lobby
    /// @throws GeneralSecurityException if the platform cannot encrypt the code
    private PublicRoom encode(PublicRoom room, String code) throws GeneralSecurityException {
        return passwordEnabled && !password.isEmpty()
                ? room.withCipher(PublicRoomPassword.encrypt(code, password))
                : room.withCode(code);
    }

    /// Stops publishing the current room and removes it from the lobby.
    ///
    /// This method returns immediately; the removal request is sent in the background.
    public void stop() {
        final @Nullable PublicRoom room;
        synchronized (this) {
            room = currentRoom;
            if (room == null) {
                return;
            }
            currentRoom = null;
            plainCode = null;
            generation++;
            cancelHeartbeat();
        }

        executor.execute(() -> closeQuietly(room.getId()));
    }

    /// Sends a heartbeat for the current generation.
    private void sendHeartbeat(long expectedGeneration) {
        final @Nullable PublicRoom room;
        synchronized (this) {
            if (generation != expectedGeneration) {
                return;
            }
            room = currentRoom;
        }
        if (room == null) {
            return;
        }

        try {
            int latencyMs = PublicRoomDirectory.publish(room);
            synchronized (this) {
                if (generation == expectedGeneration && currentRoom != null) {
                    currentRoom = currentRoom.withLatency(latencyMs);
                }
            }
        } catch (Exception e) {
            // Keep trying: a short network outage should not silently hide a room.
            LOG.warning("Failed to publish public room heartbeat", e);
        }
    }

    /// Reacts to Terracotta state changes while a room is published.
    private void onStateChanged(@Nullable TerracottaState state) {
        final @Nullable PublicRoom room;
        final @Nullable String code;
        synchronized (this) {
            room = currentRoom;
            code = plainCode;
        }
        if (room == null || code == null) {
            return;
        }

        if (state instanceof TerracottaState.HostOK hostOK
                && hostOK.getCode().equalsIgnoreCase(code)) {
            int players = hostOK.getProfiles().size();
            synchronized (this) {
                if (currentRoom != null && currentRoom.getPlayers() != players) {
                    currentRoom = currentRoom.withPlayers(players);
                }
            }
        } else {
            // The host closed the room, switched to guest mode, or Terracotta failed.
            stop();
        }
    }

    /// Cancels the pending heartbeat. Callers must hold this object's monitor.
    private void cancelHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    /// Sends a close message, ignoring failures because the room will expire anyway.
    private static void closeQuietly(String roomId) {
        try {
            PublicRoomDirectory.close(roomId);
        } catch (IOException e) {
            // The room disappears automatically once its heartbeat expires.
            LOG.info("Failed to remove public room " + roomId + ": " + e);
        }
    }

    /// Runs a completion callback on the JavaFX thread.
    private static void notifyCallback(Consumer<@Nullable Throwable> callback, @Nullable Throwable error) {
        Platform.runLater(() -> callback.accept(error));
    }
}
