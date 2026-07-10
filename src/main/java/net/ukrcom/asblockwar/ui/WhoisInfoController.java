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
import lombok.extern.slf4j.Slf4j;

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
 * FXML-контролер діалогу WhoisInfoDialog.fxml — відображає сирий RPSL-блок,
 * отриманий з локальної бази даних whois-lite-local.
 *
 * <p>Підтримує перемикання перенесення тексту через чекбокс у діалозі.
 *
 * @author olden
 */
@Slf4j
public class WhoisInfoController implements Initializable {

    @FXML
    private TextArea textArea;
    @FXML
    private CheckBox wrapText;

    private Stage stage;

    /**
     * Ініціалізує текстову область відповідно до стану чекбоксу перенесення
     * після завантаження FXML.
     *
     * @param url URL FXML-ресурсу (не використовується)
     * @param rb  ResourceBundle локалізації (не використовується)
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        textArea.setWrapText(wrapText.isSelected());
    }

    @FXML
    private void toggleWrap() {
        textArea.setWrapText(wrapText.isSelected());
    }

    /**
     * Встановлює посилання на вікно діалогу для подальшого приховання при закритті.
     *
     * @param dialogStage вікно Stage цього діалогу
     */
    public void setStage(Stage dialogStage) {
        this.stage = dialogStage;
    }

    /**
     * Встановлює текст RPSL для відображення у текстовій області.
     *
     * @param text рядок RPSL-тексту
     */
    public void setText(String text) {
        textArea.setText(text);
    }

    @FXML
    private void doClose() {
        if (stage != null) {
            stage.hide();
        }
    }

    /**
     * Відкриває модальний діалог для перегляду заданого RPSL-тексту.
     * Має викликатися з JavaFX Application Thread.
     *
     * @param owner вікно-власник для встановлення модальності
     * @param title заголовок діалогового вікна (як правило, позначення RPSL-об'єкта)
     * @param text  RPSL-текст для відображення
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
            log.error("GUI: cannot open whois info dialog", e);
        }
    }
}
