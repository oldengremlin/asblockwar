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
import java.util.function.Supplier;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAutNumFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;

/**
 * FXML Controller class for the main ASBlockWar window.
 *
 * @author olden
 */
public class MainWindowsController implements Initializable {

    @FXML private Button runButton;
    @FXML private Button propertiesButton;
    @FXML private Accordion accordion;
    @FXML private TitledPane paneListMntBy;
    @FXML private TitledPane paneListAsSet;
    @FXML private TitledPane paneListAs;
    @FXML private ListView<String> listAs;
    @FXML private ListView<String> listMntBy;
    @FXML private ListView<String> listAsSet;
    @FXML private TextArea textWarJuniper;
    @FXML private TextArea textWarBlackbgp;
    @FXML private CheckBox wrapJuniper;
    @FXML private Label statusLabel;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        textWarJuniper.setWrapText(wrapJuniper.isSelected());
        refreshUi();
        setupDoubleClick();
    }

    private void setupDoubleClick() {
        listMntBy.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = listMntBy.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    showWhoisInfo("MNT-BY: " + sel, () -> new retrieveMntnerFull(sel).get());
                }
            }
        });
        listAsSet.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = listAsSet.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    showWhoisInfo("AS-SET: " + sel, () -> new retrieveAsSet(sel).get());
                }
            }
        });
        listAs.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = listAs.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    String asn = sel.startsWith("AS") ? sel : "AS" + sel;
                    showWhoisInfo(asn, () -> new retrieveAutNumFull(asn).get());
                }
            }
        });
    }

    private void showWhoisInfo(String title, Supplier<String> supplier) {
        Thread.ofVirtual().start(() -> {
            String text = supplier.get();
            Platform.runLater(() -> {
                Stage owner = (Stage) runButton.getScene().getWindow();
                WhoisInfoController.show(owner, title, text.isBlank() ? "(no data)" : text);
            });
        });
    }

    @FXML
    private void toggleWrapJuniper() {
        textWarJuniper.setWrapText(wrapJuniper.isSelected());
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

            ctrl.startProcessing(dialog, this);
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

    void highlightAsn(String asn) {
        String bare = asn.startsWith("AS") ? asn.substring(2) : asn;
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListAs);
            findInList(listAs, bare);
        });
    }

    void highlightAsSet(String asSet) {
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListAsSet);
            findInList(listAsSet, asSet);
        });
    }

    void highlightMntBy(String mntBy) {
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListMntBy);
            findInList(listMntBy, mntBy);
        });
    }

    void clearHighlight() {
        Platform.runLater(() -> {
            listAs.getSelectionModel().clearSelection();
            listAsSet.getSelectionModel().clearSelection();
            listMntBy.getSelectionModel().clearSelection();
        });
    }

    private void findInList(ListView<String> lv, String value) {
        int idx = lv.getItems().indexOf(value);
        if (idx >= 0) {
            lv.getSelectionModel().select(idx);
            lv.scrollTo(idx);
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
