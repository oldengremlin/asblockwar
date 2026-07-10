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
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * FXML-контролер діалогу налаштувань PropertiesDialog.fxml.
 *
 * <p>Відображає поточний {@link net.ukrcom.asblockwar.Config} у редагованих полях
 * і зберігає зміни назад до конфігурації при натисканні «Save».
 *
 * @author olden
 */
@Slf4j
public class PropertiesController implements Initializable {

    @FXML
    private TextField fieldListFile;
    @FXML
    private TextField fieldListMntby;
    @FXML
    private TextField fieldListAsset;
    @FXML
    private TextField fieldWhoisUri;
    @FXML
    private TextField fieldStoreDir;
    @FXML
    private TextField fieldWarFile;
    @FXML
    private TextField fieldBlackbgpFile;
    @FXML
    private TextField fieldGetBlackhole;
    @FXML
    private TextField fieldGetBlackholeIpv6;
    @FXML
    private CheckBox fieldIpv6;
    @FXML
    private TextField fieldRecursiveAsset;
    @FXML
    private CheckBox fieldBatch;
    @FXML
    private TextField fieldAfterCommand;

    private Stage stage;

    /**
     * Заповнює поля діалогу поточними значеннями конфігурації після завантаження FXML.
     *
     * @param url URL FXML-ресурсу (не використовується)
     * @param rb  ResourceBundle локалізації (не використовується)
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (ASBlockWar.config == null) {
            return;
        }
        fieldListFile.setText(ASBlockWar.config.getListFile());
        fieldListMntby.setText(ASBlockWar.config.getListMntbyFile());
        fieldListAsset.setText(ASBlockWar.config.getListAssetFile());
        fieldWhoisUri.setText(ASBlockWar.config.getWhoisLiteLocalURI());
        fieldStoreDir.setText(ASBlockWar.config.getStoreDir());
        fieldWarFile.setText(ASBlockWar.config.getWarFile());
        fieldBlackbgpFile.setText(ASBlockWar.config.getBlackbgpFile());
        fieldGetBlackhole.setText(ASBlockWar.config.getGetBlackhole());
        fieldGetBlackholeIpv6.setText(ASBlockWar.config.getGetBlackholeIpv6());
        fieldIpv6.setSelected(ASBlockWar.config.isBlackbgpIpv6());
        fieldRecursiveAsset.setText(String.valueOf(ASBlockWar.config.getRecursiveAsset()));
        fieldBatch.setSelected(ASBlockWar.config.isBatchMode());
        fieldAfterCommand.setText(ASBlockWar.config.getAfterCommand());
    }

    /**
     * Встановлює посилання на вікно діалогу для подальшого приховання при закритті.
     *
     * @param dialogStage вікно Stage цього діалогу
     */
    public void setStage(Stage dialogStage) {
        this.stage = dialogStage;
    }

    // --- Browse actions (delegate to pure-JavaFX FilePickerController) ---
    @FXML
    private void browseListFile() {
        pick(false, fieldListFile, "Select List file");
    }

    @FXML
    private void browseListMntby() {
        pick(false, fieldListMntby, "Select MNT-BY file");
    }

    @FXML
    private void browseListAsset() {
        pick(false, fieldListAsset, "Select AS-SET file");
    }

    @FXML
    private void browseStoreDir() {
        pick(true, fieldStoreDir, "Select Store directory");
    }

    @FXML
    private void browseWarFile() {
        pick(false, fieldWarFile, "Select WAR file");
    }

    @FXML
    private void browseBlackbgpFile() {
        pick(false, fieldBlackbgpFile, "Select Blackbgp file");
    }

    @FXML
    private void browseAfterCommand() {
        pick(false, fieldAfterCommand, "Select After command script");
    }

    private void pick(boolean dirOnly, TextField field, String title) {
        FilePickerController.showFilePicker(stage, title, field.getText().trim(), dirOnly)
                .ifPresent(field::setText);
    }

    // --- Save / Cancel ---
    @FXML
    private void doSave() {
        if (ASBlockWar.config != null) {
            ASBlockWar.config.setListFile(fieldListFile.getText().trim());
            ASBlockWar.config.setListMntbyFile(fieldListMntby.getText().trim());
            ASBlockWar.config.setListAssetFile(fieldListAsset.getText().trim());
            ASBlockWar.config.setWhoisLiteLocalURI(fieldWhoisUri.getText().trim());
            ASBlockWar.config.setStoreDir(fieldStoreDir.getText().trim());
            ASBlockWar.config.setWarFile(fieldWarFile.getText().trim());
            ASBlockWar.config.setBlackbgpFile(fieldBlackbgpFile.getText().trim());
            ASBlockWar.config.setGetBlackhole(fieldGetBlackhole.getText().trim());
            ASBlockWar.config.setGetBlackholeIpv6(fieldGetBlackholeIpv6.getText().trim());
            ASBlockWar.config.setBlackbgpIpv6(fieldIpv6.isSelected());
            try {
                ASBlockWar.config.setRecursiveAsset(
                        Integer.parseInt(fieldRecursiveAsset.getText().trim()));
            } catch (NumberFormatException ignored) {
            }
            ASBlockWar.config.setBatchMode(fieldBatch.isSelected());
            ASBlockWar.config.setAfterCommand(fieldAfterCommand.getText().trim());
            try {
                ASBlockWar.config.save();
            } catch (IOException e) {
                log.error("GUI: не вдалося зберегти конфігурацію", e);
            }
        }
        if (stage != null) {
            stage.hide();
        }
    }

    @FXML
    private void doCancel() {
        if (stage != null) {
            stage.hide();
        }
    }
}
