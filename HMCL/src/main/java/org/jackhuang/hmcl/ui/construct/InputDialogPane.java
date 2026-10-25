/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.validation.base.ValidatorBase;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.FutureCallback;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class InputDialogPane extends JFXDialogLayout implements DialogAware {
    private final CompletableFuture<String> future = new CompletableFuture<>();

    private final TextField textField;
    private final Label lblCreationWarning;
    private final SpinnerPane acceptPane;
    private final JFXButton acceptButton;

    public InputDialogPane(String text, String initialValue, FutureCallback<String> onResult, ValidatorBase... validators) {
        this(text, initialValue, onResult);
        if (textField instanceof JFXTextField jfxTextField && validators != null && validators.length > 0) {
            jfxTextField.getValidators().addAll(validators);
            FXUtils.setValidateWhileTextChanged(jfxTextField, true);
            acceptButton.disableProperty().bind(jfxTextField.activeValidatorProperty().isNotNull());
        }
    }

    public InputDialogPane(String text, String initialValue, FutureCallback<String> onResult) {
        this(text, initialValue, onResult, new JFXTextField(initialValue));
    }

    /// Creates a dialog whose input is masked, for entering passwords.
    ///
    /// @param text heading of the dialog
    /// @param onResult callback validating the entered value
    /// @param masked whether the input is masked
    public InputDialogPane(String text, FutureCallback<String> onResult, boolean masked) {
        this(text, "", onResult, masked ? new PasswordField() : new JFXTextField());
    }

    private InputDialogPane(String text, String initialValue, FutureCallback<String> onResult, TextField textField) {
        this.textField = textField;

        this.setHeading(new HBox(new Label(text)));
        this.setBody(new VBox(textField));

        lblCreationWarning = new Label();
        lblCreationWarning.setPadding(new Insets(0, 5, 0, 0));

        acceptPane = new SpinnerPane();
        acceptPane.getStyleClass().add("small-spinner-pane");
        acceptButton = new JFXButton(i18n("button.ok"));
        acceptButton.getStyleClass().add("dialog-accept");
        acceptPane.setContent(acceptButton);

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");

        this.setActions(lblCreationWarning, acceptPane, cancelButton);

        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        acceptButton.setOnAction(e -> {
            acceptPane.showSpinner();

            onResult.call(textField.getText(), new FutureCallback.ResultHandler() {
                @Override
                public void resolve() {
                    acceptPane.hideSpinner();
                    future.complete(textField.getText());
                    fireEvent(new DialogCloseEvent());
                }

                @Override
                public void reject(String reason) {
                    acceptPane.hideSpinner();
                    lblCreationWarning.setText(reason);
                }
            });
        });
        textField.setOnAction(event -> acceptButton.fire());
        onEscPressed(this, cancelButton::fire);
    }

    @Override
    public void onDialogShown() {
        textField.requestFocus();
    }

    public CompletableFuture<String> getCompletableFuture() {
        return future;
    }
}
