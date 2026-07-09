/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.asblockwar.ui;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * Controller for WhoisInfoDialog.fxml — displays a raw RPSL block
 * retrieved from the local whois-lite-local database.
 *
 * @author olden
 */
public class WhoisInfoController implements Initializable {

    @FXML private TextArea textArea;
    @FXML private CheckBox wrapText;

    private Stage stage;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        textArea.setWrapText(wrapText.isSelected());
    }

    @FXML
    private void toggleWrap() {
        textArea.setWrapText(wrapText.isSelected());
    }

    public void setStage(Stage dialogStage) {
        this.stage = dialogStage;
    }

    public void setText(String text) {
        textArea.setText(text);
    }

    @FXML
    private void doClose() {
        if (stage != null) stage.hide();
    }

    /**
     * Opens a modal dialog showing the given RPSL text.
     * Must be called on the JavaFX Application Thread.
     */
    public static void show(Stage owner, String title, String text) {
        try {
            URL fxmlUrl = WhoisInfoController.class.getResource("/fxml/WhoisInfoDialog.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();
            WhoisInfoController ctrl = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(owner);
            dialog.setTitle(title);
            dialog.setScene(new Scene(root));

            ctrl.setStage(dialog);
            ctrl.setText(text);
            dialog.showAndWait();
        } catch (IOException e) {
            ASBlockWar.LOGGER.error("GUI: cannot open whois info dialog", e);
        }
    }
}
