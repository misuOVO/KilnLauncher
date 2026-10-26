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
package org.jackhuang.hmcl.ui.terracotta;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXSpinner;
import io.nayuki.qrcodegen.QrCode;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.jackhuang.hmcl.bilibili.BilibiliAccount;
import org.jackhuang.hmcl.bilibili.BilibiliAccountManager;
import org.jackhuang.hmcl.bilibili.BilibiliApi;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.util.QrCodeUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Dialog that binds a Bilibili account through QR code login.
///
/// A QR code is requested as soon as the dialog is shown and polled every two seconds.
/// When the phone confirms the login, the credentials are stored by
/// [BilibiliAccountManager] and the optional success callback runs on the JavaFX thread.
@NotNullByDefault
public final class BilibiliLoginPane extends JFXDialogLayout implements DialogAware {
    /// Interval between two polls of the QR code state.
    private static final Duration POLL_INTERVAL = Duration.seconds(2);

    /// Pixel size of one QR code module.
    private static final int QR_SCALE = 5;

    /// Callback invoked after a successful login.
    private final Runnable onSuccess;

    /// Label showing the current login step or an error.
    private final Label statusLabel = new Label();

    /// Container holding either the QR code or a spinner.
    private final StackPane qrContainer = new StackPane();

    /// Button that requests a fresh QR code.
    private final JFXButton refreshButton = new JFXButton(i18n("bilibili.login.refresh"));

    /// Repeating poll loop, running only while a QR code is valid.
    private final Timeline pollTimer = new Timeline(
            new KeyFrame(POLL_INTERVAL, event -> poll()));

    /// Key of the QR code currently displayed, or `null` while none is available.
    private @Nullable String qrCodeKey;

    /// Whether a poll request is currently in flight.
    private boolean polling;

    /// Whether the login already succeeded, which stops further polling.
    private boolean completed;

    /// Whether the dialog was closed, which discards late responses.
    private boolean closed;

    /// Number of consecutive failed poll requests for the current QR code.
    private int consecutiveFailures;

    /// Whether the current failure was already written to the log.
    private boolean failureLogged;

    /// Creates the login dialog.
    ///
    /// @param onSuccess callback invoked after the account was stored successfully
    public BilibiliLoginPane(Runnable onSuccess) {
        this.onSuccess = onSuccess;
        pollTimer.setCycleCount(Timeline.INDEFINITE);

        setHeading(new Label(i18n("bilibili.login.title")));
        setPrefWidth(420);

        Label description = new Label(i18n("bilibili.login.desc"));
        description.setWrapText(true);

        qrContainer.setAlignment(Pos.CENTER);
        qrContainer.setMinSize(240, 240);
        qrContainer.setPrefSize(240, 240);
        qrContainer.setMaxSize(240, 240);
        qrContainer.setStyle("-fx-background-color: white; -fx-background-radius: 4;");
        qrContainer.setPadding(new Insets(6));

        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(12, description, qrContainer, statusLabel);
        body.setAlignment(Pos.CENTER);
        setBody(body);

        refreshButton.getStyleClass().add("dialog-accept");
        refreshButton.setOnAction(event -> startLogin());

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        setActions(refreshButton, cancelButton);

        onEscPressed(this, cancelButton::fire);
    }

    /// Requests a fresh QR code and starts polling.
    @Override
    public void onDialogShown() {
        startLogin();
    }

    /// Stops polling when the dialog closes for any reason.
    @Override
    public void onDialogClosed() {
        closed = true;
        pollTimer.stop();
    }

    /// Requests a new QR code from Bilibili.
    private void startLogin() {
        pollTimer.stop();
        polling = false;
        completed = false;
        qrCodeKey = null;
        consecutiveFailures = 0;
        failureLogged = false;

        refreshButton.setDisable(true);
        qrContainer.getChildren().setAll(new JFXSpinner());
        statusLabel.setText(i18n("bilibili.login.waiting"));

        Task.supplyAsync(Schedulers.io(), BilibiliApi::generateQrCode)
                .whenComplete(Schedulers.javafx(), (qrCode, exception) -> {
                    if (completed || closed) {
                        return;
                    }
                    refreshButton.setDisable(false);

                    if (exception != null || qrCode == null) {
                        LOG.warning("Failed to create Bilibili QR code", exception);
                        showError();
                        return;
                    }

                    qrCodeKey = qrCode.key();
                    showQrCode(qrCode.url());
                    statusLabel.setText(i18n("bilibili.login.scan"));
                    pollTimer.play();
                })
                .start();
    }

    /// Renders the QR code content.
    private void showQrCode(String content) {
        SVGPath path = new SVGPath();
        path.setContent(QrCodeUtils.toSVGPath(QrCode.encodeText(content, QrCode.Ecc.MEDIUM), 2));
        path.setFill(Color.BLACK);
        path.setScaleX(QR_SCALE);
        path.setScaleY(QR_SCALE);
        qrContainer.getChildren().setAll(new Group(path));
    }

    /// Polls the state of the displayed QR code once.
    private void poll() {
        @Nullable String key = qrCodeKey;
        if (polling || completed || key == null) {
            return;
        }

        polling = true;
        Task.supplyAsync(Schedulers.io(), () -> BilibiliApi.poll(key))
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    polling = false;
                    if (completed || closed) {
                        return;
                    }
                    if (exception != null || result == null) {
                        consecutiveFailures++;
                        if (exception != null && !failureLogged) {
                            failureLogged = true;
                            LOG.warning("Failed to poll Bilibili QR code", exception);
                        }

                        if (consecutiveFailures >= 5) {
                            pollTimer.stop();
                            statusLabel.setText(i18n("bilibili.login.failed"));
                        } else {
                            statusLabel.setText(i18n("bilibili.login.retry"));
                        }
                        return;
                    }

                    consecutiveFailures = 0;
                    failureLogged = false;

                    switch (result.status()) {
                        case WAITING -> statusLabel.setText(i18n("bilibili.login.scan"));
                        case SCANNED -> statusLabel.setText(i18n("bilibili.login.scanned"));
                        case EXPIRED -> {
                            // Refresh automatically: waiting for a manual click after the
                            // three minute lifetime is the main reason logins failed before.
                            pollTimer.stop();
                            qrCodeKey = null;
                            statusLabel.setText(i18n("bilibili.login.expired"));

                            PauseTransition delay = new PauseTransition(Duration.seconds(1.5));
                            delay.setOnFinished(event -> {
                                if (!closed && !completed) {
                                    startLogin();
                                }
                            });
                            delay.play();
                        }
                        case SUCCESS -> completeLogin(result.account());
                    }
                })
                .start();
    }

    /// Stores the account and closes the dialog.
    private void completeLogin(@Nullable BilibiliAccount account) {
        if (account == null) {
            showError();
            return;
        }

        try {
            BilibiliAccountManager.login(account);
        } catch (IOException e) {
            LOG.warning("Failed to save Bilibili account", e);
            showError();
            return;
        }

        completed = true;
        pollTimer.stop();
        statusLabel.setText(i18n("bilibili.login.success"));
        fireEvent(new DialogCloseEvent());
        onSuccess.run();
    }

    /// Shows the generic failure state and stops polling.
    private void showError() {
        pollTimer.stop();
        qrCodeKey = null;
        qrContainer.getChildren().setAll();
        statusLabel.setText(i18n("bilibili.login.failed"));
    }
}
