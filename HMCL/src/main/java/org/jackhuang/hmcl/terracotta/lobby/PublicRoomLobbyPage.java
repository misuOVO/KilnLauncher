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

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import com.jfoenix.controls.JFXTextField;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.terracotta.TerracottaManager;
import org.jackhuang.hmcl.terracotta.TerracottaState;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.InputDialogPane;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Lists public Terracotta rooms published by other KilnLauncher users.
///
/// Private rooms are never sent to the directory and are rejected when received,
/// so this page can only ever show rooms whose host explicitly made them public.
/// The list refreshes every 15 seconds while the page is visible.
///
/// Rooms can be filtered by [PublicRoomTag], and every row shows the tags chosen by
/// the host together with the latency the host measured against the lobby server.
@NotNullByDefault
public final class PublicRoomLobbyPage extends StackPane implements PageAware {
    /// Interval between automatic refreshes while the page is visible.
    private static final Duration REFRESH_INTERVAL = Duration.seconds(15);

    /// Maximum number of tag chips shown in a row before collapsing them into "+N".
    private static final int MAX_VISIBLE_TAGS = 4;

    /// Latency threshold below which a room is shown as fast, in milliseconds.
    private static final int LATENCY_GOOD_MILLIS = 120;

    /// Latency threshold below which a room is shown as acceptable, in milliseconds.
    private static final int LATENCY_FAIR_MILLIS = 300;

    /// Heartbeat age after which a room is considered possibly offline.
    ///
    /// Hosts publish a heartbeat every 60 seconds, so this tolerates three missed
    /// heartbeats before showing a warning.
    private static final long STALE_AFTER_MILLIS = 180_000L;

    /// Pseudo-class applied to latency labels of fast rooms.
    private static final PseudoClass LATENCY_GOOD = PseudoClass.getPseudoClass("good");

    /// Pseudo-class applied to latency labels of acceptable rooms.
    private static final PseudoClass LATENCY_FAIR = PseudoClass.getPseudoClass("fair");

    /// Pseudo-class applied to latency labels of slow rooms.
    private static final PseudoClass LATENCY_POOR = PseudoClass.getPseudoClass("poor");

    /// Runs the automatic refresh loop on the JavaFX thread.
    private final Timeline refreshTimer = new Timeline(
            new KeyFrame(REFRESH_INTERVAL, event -> refresh()));

    /// Callback used to switch to the multiplayer status tab after joining.
    private final Runnable onJoined;

    /// Container holding one row per public room.
    private final ComponentList roomList = new ComponentList();

    /// Short status line shown below the description.
    private final Label statusLabel = new Label();

    /// Free text search over the room name and the verified host name.
    private final JFXTextField searchField = new JFXTextField();

    /// Chips used to filter the room list by tag.
    private final FlowPane filterBar = new FlowPane(6, 6);

    /// Tags currently used as a filter; an empty set shows every room.
    private final Set<PublicRoomTag> activeFilters = new LinkedHashSet<>();

    /// Rooms returned by the last successful fetch, used to re-render without refetching.
    private @Nullable List<PublicRoom> lastRooms;

    /// Whether a directory request is currently in flight.
    private boolean loading;

    /// Creates the lobby page.
    ///
    /// @param onJoined callback invoked after successfully joining a room
    public PublicRoomLobbyPage(Runnable onJoined) {
        this.onJoined = onJoined;
        refreshTimer.setCycleCount(Timeline.INDEFINITE);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setFillWidth(true);

        content.getChildren().add(ComponentList.createComponentListTitle(i18n("terracotta.lobby")));

        HintPane hint = new HintPane(MessageDialogPane.MessageType.INFO);
        hint.setText(i18n("terracotta.lobby.desc"));
        content.getChildren().add(hint);

        statusLabel.getStyleClass().add("terracotta-hint");
        statusLabel.setText(i18n("terracotta.lobby.loading"));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Label status = statusLabel;
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setMaxWidth(Double.MAX_VALUE);

        LineButton refreshButton = new LineButton();
        refreshButton.setLeading(SVG.REFRESH, 20);
        refreshButton.setTitle(i18n("terracotta.lobby.refresh"));
        refreshButton.setOnAction(event -> refresh());
        FXUtils.setLimitWidth(refreshButton, 180);
        toolbar.getChildren().setAll(status, refreshButton);
        content.getChildren().add(toolbar);

        filterBar.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText(i18n("terracotta.lobby.search.prompt"));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> renderRooms());
        HBox searchRow = new HBox(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        content.getChildren().add(searchRow);

        content.getChildren().add(createFilterRow());

        content.getChildren().add(roomList);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        getChildren().setAll(scrollPane);

        // The tab is initialized lazily on first selection, so loading here is
        // safe and guarantees data even if the tab is selected directly.
        refresh();
        refreshTimer.play();
    }

    /// Builds the tag filter row shown above the room list.
    ///
    /// The "all" button clears the selection; every tag chip toggles itself and triggers
    /// a local re-render, so filtering never hits the network.
    private HBox createFilterRow() {
        Label caption = new Label(i18n("terracotta.lobby.filter"));
        caption.getStyleClass().add("terracotta-hint");

        Button all = new Button(i18n("terracotta.lobby.filter.all"));
        all.getStyleClass().add("kiln-tag-chip");
        all.setOnAction(event -> {
            activeFilters.clear();
            updateFilterSelection();
            renderRooms();
        });

        filterBar.getChildren().add(all);
        for (PublicRoomTag tag : PublicRoomTag.all()) {
            ToggleButton chip = new ToggleButton(tag.getDisplayName());
            chip.getStyleClass().add("kiln-tag-chip");
            chip.setUserData(tag);
            chip.selectedProperty().addListener((observable, oldValue, selected) -> {
                if (selected) {
                    activeFilters.add(tag);
                } else {
                    activeFilters.remove(tag);
                }
                renderRooms();
            });
            filterBar.getChildren().add(chip);
        }

        HBox row = new HBox(8, caption, filterBar);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filterBar, Priority.ALWAYS);
        return row;
    }

    /// Synchronizes the chip selection with [#activeFilters] without firing listeners twice.
    private void updateFilterSelection() {
        for (var node : filterBar.getChildren()) {
            if (node instanceof ToggleButton chip
                    && chip.getUserData() instanceof PublicRoomTag tag) {
                chip.setSelected(activeFilters.contains(tag));
            }
        }
    }

    /// Fetches the current room list from the public directory.
    ///
    /// Concurrent requests are ignored. This method must be called on the
    /// JavaFX application thread.
    public void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        statusLabel.setText(i18n("terracotta.lobby.loading"));

        Task.supplyAsync(Schedulers.io(), PublicRoomDirectory::fetch)
                .whenComplete(Schedulers.javafx(), (rooms, exception) -> {
                    loading = false;
                    if (exception != null) {
                        statusLabel.setText(i18n("terracotta.lobby.error"));
                        return;
                    }
                    showRooms(rooms);
                })
                .setSignificance(Task.TaskSignificance.MINOR)
                .start();
    }

    /// Stores the fetched rooms and renders them.
    private void showRooms(@Nullable List<PublicRoom> rooms) {
        lastRooms = rooms;
        renderRooms();
    }

    /// Applies the active tag filter and rebuilds the visible rows.
    private void renderRooms() {
        @Nullable List<PublicRoom> rooms = lastRooms;
        if (rooms == null || rooms.isEmpty()) {
            statusLabel.setText(i18n("terracotta.lobby.empty"));
            roomList.getContent().setAll();
            return;
        }

        List<PublicRoom> visible = new ArrayList<>();
        for (PublicRoom room : rooms) {
            if (matchesFilter(room) && matchesSearch(room)) {
                visible.add(room);
            }
        }

        if (visible.isEmpty()) {
            statusLabel.setText(i18n("terracotta.lobby.filter.empty", rooms.size()));
            roomList.getContent().setAll();
            return;
        }

        boolean filtering = !activeFilters.isEmpty() || !getSearchQuery().isEmpty();
        statusLabel.setText(filtering
                ? i18n("terracotta.lobby.filter.updated", visible.size(), rooms.size())
                : i18n("terracotta.lobby.updated", visible.size()));

        roomList.getContent().clear();
        for (PublicRoom room : visible) {
            roomList.getContent().add(createRoomRow(room));
        }
    }

    /// Returns the trimmed, lower-cased search query, empty when the search box is blank.
    private String getSearchQuery() {
        String text = searchField.getText();
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /// Returns whether a room matches the free text search.
    ///
    /// The matching rules live in [PublicRoom#matchesQuery] so they can be exercised
    /// without starting the UI.
    private boolean matchesSearch(PublicRoom room) {
        return room.matchesQuery(getSearchQuery());
    }

    /// Returns whether a room matches the active filter.
    ///
    /// A room matches when it carries at least one selected tag, which keeps the filter
    /// useful for broad selections such as "survival" plus "mod survival".
    private boolean matchesFilter(PublicRoom room) {
        if (activeFilters.isEmpty()) {
            return true;
        }
        for (PublicRoomTag tag : activeFilters) {
            if (room.hasTag(tag)) {
                return true;
            }
        }
        return false;
    }

    /// Builds one clickable room row.
    private LineButton createRoomRow(PublicRoom room) {
        boolean locked = room.isLocked();
        LineButton row = new LineButton();
        row.setLeading(locked ? SVG.LOCK : SVG.PUBLIC, 24);
        row.setTitle(room.getTitle());
        row.setTitleTrailing(createRowBadges(room));
        row.setSubtitle(i18n(
                "terracotta.lobby.room.desc",
                room.getHost(),
                room.getPlayers(),
                locked ? i18n("terracotta.lobby.room.locked") : room.getCode())
                + " · "
                + (isStale(room)
                ? i18n("terracotta.lobby.room.stale")
                : i18n("terracotta.lobby.room.online"))
                + " · "
                + i18n("terracotta.lobby.room.heartbeat", formatAge(getAgeMillis(room))));
        row.setTrailingText(locked
                ? i18n("terracotta.lobby.join.locked")
                : i18n("terracotta.lobby.join"));

        // A dedicated copy button lets players share the invite code without
        // joining. Consuming the click keeps the row's join action from firing.
        StackPane copyButton = new StackPane(SVG.CONTENT_COPY.createIcon(18));
        copyButton.setPadding(new Insets(4));
        copyButton.setPickOnBounds(true);
        copyButton.setCursor(Cursor.HAND);
        FXUtils.installFastTooltip(copyButton, locked
                ? i18n("terracotta.lobby.copy.locked")
                : i18n("terracotta.lobby.copy"));
        copyButton.setOnMouseClicked(event -> {
            event.consume();
            copyCode(room);
        });
        row.setTrailingIcon(copyButton);

        row.setOnAction(event -> confirmJoin(room));
        return row;
    }

    /// Builds the tag and latency badges displayed next to the room title.
    private HBox createRowBadges(PublicRoom room) {
        HBox badges = new HBox(4);
        badges.setAlignment(Pos.CENTER_LEFT);

        List<PublicRoomTag> tags = room.getTags();
        int shown = Math.min(tags.size(), MAX_VISIBLE_TAGS);
        for (int i = 0; i < shown; i++) {
            badges.getChildren().add(createChip(tags.get(i).getDisplayName(), null));
        }
        if (tags.size() > shown) {
            badges.getChildren().add(createChip("+" + (tags.size() - shown), null));
        }

        badges.getChildren().add(createLatencyChip(room.getLatencyMs()));
        return badges;
    }

    /// Creates a static, non-interactive chip label.
    private static Label createChip(String text, @Nullable String tooltip) {
        Label chip = new Label(text);
        chip.getStyleClass().add("kiln-room-tag");
        if (tooltip != null) {
            FXUtils.installFastTooltip(chip, tooltip);
        }
        return chip;
    }

    /// Creates the latency badge shown for a room.
    ///
    /// The value is measured by the host against the lobby server, so it describes the
    /// host's connection quality rather than the guest's ping to the host.
    private static Label createLatencyChip(int latencyMs) {
        Label chip = new Label(latencyMs > 0
                ? i18n("terracotta.lobby.room.latency", latencyMs)
                : i18n("terracotta.lobby.room.latency_unknown"));
        chip.getStyleClass().add("kiln-room-latency");
        FXUtils.installFastTooltip(chip, i18n("terracotta.lobby.room.latency_tip"));

        PseudoClass quality = latencyMs <= 0 || latencyMs <= LATENCY_GOOD_MILLIS
                ? LATENCY_GOOD
                : latencyMs <= LATENCY_FAIR_MILLIS ? LATENCY_FAIR : LATENCY_POOR;
        // An unknown latency is drawn with the neutral "good" style; the tooltip explains it.
        chip.pseudoClassStateChanged(quality, true);
        return chip;
    }

    /// Returns how long ago the room's last heartbeat reached the server.
    private static long getAgeMillis(PublicRoom room) {
        return Math.max(0L, System.currentTimeMillis() - room.getObservedAt());
    }

    /// Returns whether the host probably stopped heartbeating.
    ///
    /// Hosts send a heartbeat every 60 seconds, so a room is only treated as stale after
    /// three missed heartbeats. The previous two-minute threshold marked healthy rooms as
    /// offline as soon as a single heartbeat was delayed by the network or the poll interval.
    private static boolean isStale(PublicRoom room) {
        return getAgeMillis(room) > STALE_AFTER_MILLIS;
    }

    /// Formats a heartbeat age for display, e.g. `12 秒` or `4 分钟`.
    private static String formatAge(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return seconds < 60L
                ? i18n("terracotta.lobby.age.seconds", seconds)
                : i18n("terracotta.lobby.age.minutes", seconds / 60L);
    }

    /// Copies the invite code, asking for the room password when the room is locked.
    private void copyCode(PublicRoom room) {
        @Nullable String code = room.getCode();
        if (code != null) {
            FXUtils.copyText(code, i18n("terracotta.lobby.copy.toast"));
            return;
        }

        promptPassword(room, unlocked ->
                FXUtils.copyText(unlocked, i18n("terracotta.lobby.copy.toast")));
    }

    /// Asks the user to confirm joining a public room.
    ///
    /// Password protected rooms first ask for the password, because the invite code is
    /// not part of the published payload.
    private void confirmJoin(PublicRoom room) {
        // Joining a room published by this launcher would switch this instance to
        // guest mode and take the host offline, so refuse it explicitly.
        if (PublicRoomPublisher.instance().isPublished(room)) {
            Controllers.dialog(
                    i18n("terracotta.lobby.join.self"),
                    i18n("message.warning"),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }

        @Nullable String code = room.getCode();
        if (code == null) {
            promptPassword(room, unlocked -> confirmJoin(room, unlocked));
        } else {
            confirmJoin(room, code);
        }
    }

    /// Asks the user to confirm joining a room whose invite code is already known.
    private void confirmJoin(PublicRoom room, String code) {
        String message = i18n("terracotta.lobby.join.confirm", room.getTitle(), code);
        long age = getAgeMillis(room);
        if (age > STALE_AFTER_MILLIS) {
            message += "\n" + i18n("terracotta.lobby.join.stale", formatAge(age));
        }

        Account account = Accounts.getSelectedAccount();
        if (account != null && room.getHost().equalsIgnoreCase(account.getProfileName())) {
            message += "\n" + i18n("terracotta.lobby.join.same_account");
        }

        Controllers.confirm(
                message,
                i18n("terracotta.lobby.join.title"),
                () -> join(code),
                () -> {
                });
    }

    /// Shows a masked password prompt and runs the action with the decrypted invite code.
    ///
    /// A wrong password is reported inside the dialog instead of closing it, so the
    /// guest can retry without reopening the lobby row.
    private void promptPassword(PublicRoom room, Consumer<String> onUnlocked) {
        AtomicReference<@Nullable String> unlocked = new AtomicReference<>();
        InputDialogPane pane = new InputDialogPane(
                i18n("terracotta.lobby.password.title"),
                (value, handler) -> {
                    @Nullable String code = PublicRoomPassword.decrypt(room.getCodeCipher(), value);
                    if (code == null) {
                        handler.reject(i18n("terracotta.lobby.password.wrong"));
                    } else {
                        unlocked.set(code);
                        handler.resolve();
                    }
                },
                true);

        pane.getCompletableFuture().thenAccept(value -> {
            @Nullable String code = unlocked.get();
            if (code != null) {
                onUnlocked.accept(code);
            }
        });
        Controllers.dialog(pane);
    }

    /// Starts the guest connection to the selected room.
    private void join(String code) {
        @Nullable Task<TerracottaState.GuestConnecting> task =
                TerracottaManager.setGuesting(code);
        if (task == null) {
            Controllers.dialog(
                    i18n("terracotta.lobby.not_ready"),
                    i18n("message.error"),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }

        task.whenComplete(Schedulers.javafx(), (state, exception) -> {
            if (exception != null) {
                Controllers.dialog(
                        i18n("terracotta.lobby.join.failed"),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR);
            } else {
                onJoined.run();
            }
        }).setSignificance(Task.TaskSignificance.MINOR).start();
    }

    /// Starts the refresh loop when the tab becomes visible.
    @Override
    public void onPageShown() {
        refreshTimer.play();
        refresh();
    }

    /// Stops the refresh loop when the tab is hidden.
    @Override
    public void onPageHidden() {
        refreshTimer.stop();
    }
}
