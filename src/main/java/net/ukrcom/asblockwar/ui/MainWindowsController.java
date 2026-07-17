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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.application.Platform;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAsSet;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveAutNumFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveMntnerFull;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveRouteFull;

/**
 * FXML-контролер головного вікна ASBlockWar.
 *
 * <p>Керує чотирма списками (MNT-BY, AS-SET, AS, Prefixes) зі спільним полем фільтру,
 * відображає вихідні файли (WAR Juniper і blackbgp), запускає обробку та відкриває
 * діалоги налаштувань.
 *
 * @author olden
 */
@Slf4j
public class MainWindowsController implements Initializable {

    @FXML
    private Button runButton;
    @FXML
    private Button propertiesButton;
    @FXML
    private Button dependencyButton;
    @FXML
    private Accordion accordion;
    @FXML
    private TitledPane paneListMntBy;
    @FXML
    private TitledPane paneListAsSet;
    @FXML
    private TitledPane paneListAs;
    @FXML
    private TitledPane paneListPrefixes;
    @FXML
    private ListView<String> listMntBy;
    @FXML
    private ListView<String> listAsSet;
    @FXML
    private ListView<String> listAs;
    @FXML
    private ListView<String> listPrefixes;
    @FXML
    private TextArea textWarJuniper;
    @FXML
    private TextArea textWarBlackbgp;
    @FXML
    private CheckBox wrapJuniper;
    @FXML
    private Label statusLabel;
    @FXML
    private TextField filterField;

    // Full (unfiltered) backing lists; replaced atomically on each load
    private List<String> allItemsMntBy = List.of();
    private List<String> allItemsAsSet = List.of();
    private List<String> allItemsAs = List.of();
    private List<String> allItemsPrefixes = List.of();

    // Saved filter text per tab: [0]=MntBy, [1]=AsSet, [2]=As, [3]=Prefixes
    private final String[] tabFilters = {"", "", "", ""};

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        textWarJuniper.setWrapText(wrapJuniper.isSelected());
        accordion.setExpandedPane(paneListAs);
        Platform.runLater(paneListAs::requestFocus);

        accordion.expandedPaneProperty().addListener((obs, oldPane, newPane)
                -> onTabSwitch(oldPane, newPane));
        filterField.textProperty().addListener((obs, old, text)
                -> onFilterChanged(text));

        refreshUi();
        setupDoubleClick();
    }

    // ── tab / filter helpers ─────────────────────────────────────────────────
    private int tabIndex(TitledPane pane) {
        if (pane == paneListMntBy) {
            return 0;
        }
        if (pane == paneListAsSet) {
            return 1;
        }
        if (pane == paneListAs) {
            return 2;
        }
        if (pane == paneListPrefixes) {
            return 3;
        }
        return -1;
    }

    private List<String> backingFor(int idx) {
        return switch (idx) {
            case 0 ->
                allItemsMntBy;
            case 1 ->
                allItemsAsSet;
            case 2 ->
                allItemsAs;
            case 3 ->
                allItemsPrefixes;
            default ->
                List.of();
        };
    }

    private ListView<String> listFor(int idx) {
        return switch (idx) {
            case 0 ->
                listMntBy;
            case 1 ->
                listAsSet;
            case 2 ->
                listAs;
            case 3 ->
                listPrefixes;
            default ->
                null;
        };
    }

    private List<String> filtered(List<String> all, String text) {
        if (text == null || text.isBlank()) {
            return all;
        }
        String lower = text.toLowerCase();
        return all.stream().filter(s -> s.toLowerCase().contains(lower)).collect(Collectors.toList());
    }

    private void applyFilterToList(int idx, String text) {
        ListView<String> lv = listFor(idx);
        if (lv == null) {
            return;
        }
        List<String> items = filtered(backingFor(idx), text);
        Platform.runLater(() -> lv.setItems(FXCollections.observableList(items)));
    }

    private void onFilterChanged(String text) {
        TitledPane active = accordion.getExpandedPane();
        int idx = tabIndex(active);
        if (idx < 0) {
            return;
        }
        tabFilters[idx] = text != null ? text : "";
        applyFilterToList(idx, tabFilters[idx]);
    }

    private void onTabSwitch(TitledPane oldPane, TitledPane newPane) {
        if (oldPane != null) {
            int idx = tabIndex(oldPane);
            if (idx >= 0) {
                tabFilters[idx] = filterField.getText();
            }
        }
        if (newPane != null) {
            int idx = tabIndex(newPane);
            filterField.setText(idx >= 0 ? tabFilters[idx] : "");
        }
    }

    // ── double-click ─────────────────────────────────────────────────────────
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
        listPrefixes.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                String sel = listPrefixes.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    showWhoisInfo("route: " + sel, () -> new retrieveRouteFull(sel).get());
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

    // ── FXML actions ─────────────────────────────────────────────────────────
    @FXML
    private void clearFilter() {
        filterField.clear();
    }

    @FXML
    private void toggleWrapJuniper() {
        textWarJuniper.setWrapText(wrapJuniper.isSelected());
    }

    @FXML
    private void doRun() {
        runButton.setDisable(true);
        propertiesButton.setDisable(true);
        dependencyButton.setDisable(true);
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
            log.error("GUI: cannot open progress dialog", e);
            statusLabel.setText("Error: " + e.getMessage());
        } finally {
            runButton.setDisable(false);
            propertiesButton.setDisable(false);
            dependencyButton.setDisable(false);
        }
    }

    @FXML
    private void doDependencyGraph() {
        try {
            Stage owner = (Stage) runButton.getScene().getWindow();
            DependencyGraphController.show(owner);
        } catch (IOException e) {
            log.error("GUI: cannot open dependency graph", e);
            statusLabel.setText("Error: " + e.getMessage());
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
            log.error("GUI: cannot open properties dialog", e);
            statusLabel.setText("Error: " + e.getMessage());
        }
    }

    // ── highlight (called from RunProgressController) ─────────────────────────
    /**
     * Розгортає панель ASN і виділяє в списку вказаний ASN.
     * Виклик безпечний з будь-якого потоку.
     */
    void highlightAsn(String asn) {
        String bare = asn.startsWith("AS") ? asn.substring(2) : asn;
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListAs);
            findInList(listAs, bare);
        });
    }

    /**
     * Розгортає панель AS-SET і виділяє в списку вказаний AS-SET.
     * Виклик безпечний з будь-якого потоку.
     */
    void highlightAsSet(String asSet) {
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListAsSet);
            findInList(listAsSet, asSet);
        });
    }

    /**
     * Розгортає панель MNT-BY і виділяє в списку вказаний мантейнер.
     * Виклик безпечний з будь-якого потоку.
     */
    void highlightMntBy(String mntBy) {
        Platform.runLater(() -> {
            accordion.setExpandedPane(paneListMntBy);
            findInList(listMntBy, mntBy);
        });
    }

    /**
     * Знімає виділення з усіх чотирьох списків після завершення обробки.
     * Виклик безпечний з будь-якого потоку.
     */
    void clearHighlight() {
        Platform.runLater(() -> {
            listMntBy.getSelectionModel().clearSelection();
            listAsSet.getSelectionModel().clearSelection();
            listAs.getSelectionModel().clearSelection();
            listPrefixes.getSelectionModel().clearSelection();
        });
    }

    private void findInList(ListView<String> lv, String value) {
        int idx = lv.getItems().indexOf(value);
        if (idx >= 0) {
            lv.getSelectionModel().select(idx);
            lv.scrollTo(idx);
        }
    }

    // ── data loading ─────────────────────────────────────────────────────────
    private void refreshUi() {
        if (ASBlockWar.config == null) {
            return;
        }
        loadListFile(listMntBy, Path.of(ASBlockWar.config.getListMntbyFile()),
                items -> allItemsMntBy = items, 0);
        loadListFile(listAsSet, Path.of(ASBlockWar.config.getListAssetFile()),
                items -> allItemsAsSet = items, 1);
        loadListFile(listAs, Path.of(ASBlockWar.config.getListFile()),
                items -> allItemsAs = items, 2);
        loadPrefixes();
        loadTextFile(textWarJuniper, Path.of(ASBlockWar.config.getWarFile()));
        loadTextFile(textWarBlackbgp, Path.of(ASBlockWar.config.getBlackbgpFile()));
    }

    private void loadListFile(ListView<String> lv, Path path,
                              Consumer<List<String>> store, int tabIdx) {
        try {
            if (Files.exists(path)) {
                List<String> items = Files.lines(path)
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                        .collect(Collectors.toList());
                store.accept(items);
                List<String> toShow = filtered(items, tabFilters[tabIdx]);
                Platform.runLater(() -> lv.setItems(FXCollections.observableList(toShow)));
            }
        } catch (IOException e) {
            log.warn("GUI: cannot read {}", path, e);
        }
    }

    private void loadPrefixes() {
        if (ASBlockWar.config == null) {
            return;
        }
        Path networksFile = Path.of(ASBlockWar.config.getStoreDir()).resolve("networks.list");
        try {
            if (Files.exists(networksFile)) {
                List<String> prefixes = Files.lines(networksFile)
                        .map(String::trim)
                        .filter(l -> !l.isEmpty())
                        .map(l -> l.split("\\s+")[0])
                        .collect(Collectors.toList());
                allItemsPrefixes = prefixes;
                List<String> toShow = filtered(prefixes, tabFilters[3]);
                Platform.runLater(() -> listPrefixes.setItems(FXCollections.observableList(toShow)));
            }
        } catch (IOException e) {
            log.warn("GUI: cannot read {}", networksFile, e);
        }
    }

    private void loadTextFile(TextArea ta, Path path) {
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path);
                Platform.runLater(() -> ta.setText(content));
            }
        } catch (IOException e) {
            log.warn("GUI: cannot read {}", path, e);
        }
    }
}
