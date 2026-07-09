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
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
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
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        refreshUi();
    }

    @FXML
    private void doRun() {
        runButton.setDisable(true);
        propertiesButton.setDisable(true);
        progressBar.setVisible(true);
        statusLabel.setText("Running...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ASBlockWar.runProcessing();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            refreshUi();
            progressBar.setVisible(false);
            statusLabel.setText("Done.");
            runButton.setDisable(false);
            propertiesButton.setDisable(false);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            progressBar.setVisible(false);
            statusLabel.setText("Error: " + (ex != null ? ex.getMessage() : "unknown"));
            ASBlockWar.LOGGER.error("GUI: runProcessing failed", ex);
            runButton.setDisable(false);
            propertiesButton.setDisable(false);
        });

        Thread thread = new Thread(task, "asblockwar-run");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void doPropertiesDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Properties");
        alert.setHeaderText("Current configuration");
        if (ASBlockWar.config != null) {
            alert.setContentText(
                "List file:    " + ASBlockWar.config.getListFile() + "\n"
                + "MNT-BY file:  " + ASBlockWar.config.getListMntbyFile() + "\n"
                + "AS-SET file:  " + ASBlockWar.config.getListAssetFile() + "\n"
                + "DB URI:       " + ASBlockWar.config.getWhoisLiteLocalURI() + "\n"
                + "Store dir:    " + ASBlockWar.config.getStoreDir() + "\n"
                + "WAR file:     " + ASBlockWar.config.getWarFile() + "\n"
                + "Blackbgp file:" + ASBlockWar.config.getBlackbgpFile()
            );
        } else {
            alert.setContentText("Config not loaded.");
        }
        alert.showAndWait();
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
