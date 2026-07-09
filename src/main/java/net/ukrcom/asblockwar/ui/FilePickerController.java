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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * Controller for FilePickerDialog.fxml — pure-JavaFX file/directory browser
 * that avoids the native GTK FileChooser (which crashes on some Linux setups).
 *
 * @author olden
 */
public class FilePickerController implements Initializable {

    @FXML private SplitPane contentSplit;
    @FXML private TreeView<Path> dirTree;
    @FXML private ListView<Path> fileList;
    @FXML private TextField selectedPath;
    @FXML private Button okButton;

    private boolean directoryOnly;
    private Stage stage;
    private String result;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        dirTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    Path name = item.getFileName();
                    setText(name != null ? name.toString() : item.toString());
                }
            }
        });

        fileList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    Path name = item.getFileName();
                    setText(name != null ? name.toString() : item.toString());
                }
            }
        });

        // Tree selection → update file list (file mode) or selectedPath (dir mode)
        dirTree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getValue() != null) {
                if (directoryOnly) {
                    selectedPath.setText(sel.getValue().toString());
                } else {
                    populateFileList(sel.getValue());
                }
            }
        });

        // File list selection → update selectedPath
        fileList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                selectedPath.setText(sel.toString());
            }
        });

        // Double-click on file → confirm
        fileList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && fileList.getSelectionModel().getSelectedItem() != null) {
                doSelect();
            }
        });

        // Build tree from filesystem root
        TreeItem<Path> rootItem = createDirItem(Path.of("/"));
        rootItem.setExpanded(true);
        dirTree.setRoot(rootItem);
        dirTree.setShowRoot(true);
    }

    /**
     * Called by the factory method after the FXML is loaded and before
     * showAndWait(). Sets the mode and pre-navigates to the initial path.
     */
    public void configure(String initialPath, boolean directoryOnly, Stage dialogStage) {
        this.directoryOnly = directoryOnly;
        this.stage = dialogStage;

        if (directoryOnly) {
            contentSplit.getItems().remove(fileList);
        }

        if (initialPath != null && !initialPath.isEmpty()) {
            Path p = Path.of(initialPath).toAbsolutePath().normalize();
            Path targetDir = Files.isDirectory(p) ? p : p.getParent();
            if (targetDir != null && Files.isDirectory(targetDir)) {
                // expandTo triggers the tree selection listener which populates fileList
                expandTo(targetDir);
                if (!directoryOnly && Files.isRegularFile(p)) {
                    selectedPath.setText(p.toString());
                    // Select the file in the list (which was just populated by the listener)
                    fileList.getItems().stream()
                        .filter(f -> f.equals(p))
                        .findFirst()
                        .ifPresent(f -> {
                            fileList.getSelectionModel().select(f);
                            fileList.scrollTo(f);
                        });
                } else {
                    selectedPath.setText(targetDir.toString());
                }
            }
        }
    }

    /** Scroll the tree to the currently selected item (called after the Stage is shown). */
    void scrollToSelection() {
        TreeItem<Path> sel = dirTree.getSelectionModel().getSelectedItem();
        if (sel != null) {
            int idx = dirTree.getRow(sel);
            if (idx >= 0) {
                dirTree.scrollTo(idx);
            }
        }
    }

    private void expandTo(Path targetDir) {
        // Collect ancestors from root down to targetDir
        List<Path> components = new ArrayList<>();
        Path p = targetDir;
        while (p != null) {
            components.add(0, p);
            Path parent = p.getParent();
            if (parent == null || parent.equals(p)) break;
            p = parent;
        }

        TreeItem<Path> current = dirTree.getRoot();
        // Skip first component if it is the tree root itself
        int start = (!components.isEmpty() && current != null
                && components.get(0).equals(current.getValue())) ? 1 : 0;

        if (!current.isExpanded()) {
            current.setExpanded(true);
        }

        for (int i = start; i < components.size(); i++) {
            if (current == null) break;
            if (!current.isExpanded()) {
                current.setExpanded(true); // triggers lazy load synchronously
            }
            Path component = components.get(i);
            TreeItem<Path> found = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child.getValue() != null && child.getValue().equals(component)) {
                    found = child;
                    break;
                }
            }
            current = found;
        }

        if (current != null) {
            dirTree.getSelectionModel().select(current);
        }
    }

    private void populateFileList(Path dir) {
        fileList.getItems().clear();
        try {
            Files.list(dir)
                .filter(Files::isRegularFile)
                .filter(p -> !hidden(p))
                .sorted()
                .forEach(fileList.getItems()::add);
        } catch (IOException ignored) {
        }
    }

    private TreeItem<Path> createDirItem(Path path) {
        TreeItem<Path> item = new TreeItem<>(path);
        // Placeholder child makes the expand arrow visible before actual loading
        item.getChildren().add(new TreeItem<>(null));
        item.expandedProperty().addListener((obs, was, is) -> {
            // Only load once: placeholder is still the sole child with null value
            if (is && !item.getChildren().isEmpty()
                    && item.getChildren().get(0).getValue() == null) {
                item.getChildren().clear();
                try {
                    Files.list(path)
                        .filter(Files::isDirectory)
                        .filter(p -> !hidden(p))
                        .sorted()
                        .map(this::createDirItem)
                        .forEach(item.getChildren()::add);
                } catch (IOException ignored) {
                }
            }
        });
        return item;
    }

    private static boolean hidden(Path p) {
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }

    public String getResult() { return result; }

    @FXML
    private void doSelect() {
        String path = selectedPath.getText().trim();
        if (!path.isEmpty()) {
            result = path;
        }
        if (stage != null) stage.hide();
    }

    @FXML
    private void doCancel() {
        result = null;
        if (stage != null) stage.hide();
    }

    /**
     * Opens a modal file/directory picker dialog and returns the selected path,
     * or empty if the user cancelled.
     *
     * @param directoryOnly true = only directories can be selected
     */
    public static Optional<String> showFilePicker(Stage owner, String title,
                                                   String initialPath, boolean directoryOnly) {
        try {
            URL fxmlUrl = FilePickerController.class.getResource("/fxml/FilePickerDialog.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();
            FilePickerController ctrl = loader.getController();

            Stage dialog = new Stage();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(owner);
            dialog.setTitle(title);
            dialog.setScene(new Scene(root));

            ctrl.configure(initialPath, directoryOnly, dialog);
            dialog.setOnShown(e -> ctrl.scrollToSelection());
            dialog.showAndWait();

            return Optional.ofNullable(ctrl.getResult());
        } catch (IOException e) {
            ASBlockWar.LOGGER.error("GUI: cannot open file picker", e);
            return Optional.empty();
        }
    }
}
