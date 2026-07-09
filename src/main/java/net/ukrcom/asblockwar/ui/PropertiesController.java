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

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * Controller for PropertiesDialog.fxml — editable view of the current Config.
 *
 * @author olden
 */
public class PropertiesController implements Initializable {

    @FXML private TextField fieldListFile;
    @FXML private TextField fieldListMntby;
    @FXML private TextField fieldListAsset;
    @FXML private TextField fieldWhoisUri;
    @FXML private TextField fieldStoreDir;
    @FXML private TextField fieldWarFile;
    @FXML private TextField fieldBlackbgpFile;
    @FXML private TextField fieldGetBlackhole;
    @FXML private TextField fieldGetBlackholeIpv6;
    @FXML private CheckBox  fieldIpv6;
    @FXML private TextField fieldRecursiveAsset;

    private Stage stage;

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
    }

    public void setStage(Stage dialogStage) {
        this.stage = dialogStage;
    }

    // --- Browse actions ---

    @FXML private void browseListFile()     { browseFile(fieldListFile,      "Select List file"); }
    @FXML private void browseListMntby()    { browseFile(fieldListMntby,     "Select MNT-BY file"); }
    @FXML private void browseListAsset()    { browseFile(fieldListAsset,     "Select AS-SET file"); }
    @FXML private void browseStoreDir()     { browseDir(fieldStoreDir,       "Select Store directory"); }
    @FXML private void browseWarFile()      { browseFile(fieldWarFile,       "Select WAR file"); }
    @FXML private void browseBlackbgpFile() { browseFile(fieldBlackbgpFile,  "Select Blackbgp file"); }

    private void browseFile(TextField field, String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        String current = field.getText().trim();
        if (!current.isEmpty()) {
            Path p = Path.of(current).toAbsolutePath();
            Path parent = p.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                fc.setInitialDirectory(parent.toFile());
                if (Files.isRegularFile(p)) {
                    fc.setInitialFileName(p.getFileName().toString());
                }
            }
        }
        File selected = fc.showOpenDialog(stage);
        if (selected != null) {
            field.setText(selected.getAbsolutePath());
        }
    }

    private void browseDir(TextField field, String title) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(title);
        String current = field.getText().trim();
        if (!current.isEmpty()) {
            Path p = Path.of(current).toAbsolutePath();
            if (Files.isDirectory(p)) {
                dc.setInitialDirectory(p.toFile());
            } else {
                Path parent = p.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    dc.setInitialDirectory(parent.toFile());
                }
            }
        }
        File selected = dc.showDialog(stage);
        if (selected != null) {
            field.setText(selected.getAbsolutePath());
        }
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
