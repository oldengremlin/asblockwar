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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * FXML Controller class for the main ASBlockWar window.
 *
 * @author olden
 */
public class MainWindowsController implements Initializable {

    @FXML private Button runButton;
    @FXML private Button propertiesButton;
    @FXML private ListView<String> listAs;
    @FXML private ListView<String> listMntBy;
    @FXML private ListView<String> listAsSet;
    @FXML private TextArea textWarJuniper;
    @FXML private TextArea textWarBlackbgp;
    @FXML private Label statusLabel;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        refreshUi();
    }

    @FXML
    private void doRun() {
        runButton.setDisable(true);
        propertiesButton.setDisable(true);
        statusLabel.setText("Running...");
        try {
            URL fxmlUrl = getClass().getResource("/fxml/RunProgressDialog.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();
            RunProgressController ctrl = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(runButton.getScene().getWindow());
            dialog.setTitle("ASBlockWar — Processing");
            dialog.setScene(new Scene(root));

            ctrl.startProcessing(dialog);
            dialog.showAndWait();

            refreshUi();
            statusLabel.setText("Done.");
        } catch (IOException e) {
            ASBlockWar.LOGGER.error("GUI: cannot open progress dialog", e);
            statusLabel.setText("Error: " + e.getMessage());
        } finally {
            runButton.setDisable(false);
            propertiesButton.setDisable(false);
        }
    }

    @FXML
    private void doPropertiesDialog() {
        try {
            URL fxmlUrl = getClass().getResource("/fxml/PropertiesDialog.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();
            PropertiesController ctrl = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(propertiesButton.getScene().getWindow());
            dialog.setTitle("Properties");
            dialog.setScene(new Scene(root));

            ctrl.setStage(dialog);
            dialog.showAndWait();

            refreshUi();
        } catch (IOException e) {
            ASBlockWar.LOGGER.error("GUI: cannot open properties dialog", e);
            statusLabel.setText("Error: " + e.getMessage());
        }
    }

    private void refreshUi() {
        if (ASBlockWar.config == null) {
            return;
        }
        loadListFile(listAs,     Path.of(ASBlockWar.config.getListFile()));
        loadListFile(listMntBy,  Path.of(ASBlockWar.config.getListMntbyFile()));
        loadListFile(listAsSet,  Path.of(ASBlockWar.config.getListAssetFile()));
        loadTextFile(textWarJuniper,  Path.of(ASBlockWar.config.getWarFile()));
        loadTextFile(textWarBlackbgp, Path.of(ASBlockWar.config.getBlackbgpFile()));
    }

    private void loadListFile(ListView<String> lv, Path path) {
        try {
            if (Files.exists(path)) {
                List<String> items = Files.lines(path)
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                        .collect(Collectors.toList());
                Platform.runLater(() -> lv.setItems(FXCollections.observableList(items)));
            }
        } catch (IOException e) {
            ASBlockWar.LOGGER.warn("GUI: cannot read {}", path, e);
        }
    }

    private void loadTextFile(TextArea ta, Path path) {
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path);
                Platform.runLater(() -> ta.setText(content));
            }
        } catch (IOException e) {
            ASBlockWar.LOGGER.warn("GUI: cannot read {}", path, e);
        }
    }
}
