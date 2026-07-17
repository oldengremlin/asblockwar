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

import java.awt.Desktop;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.actions.FileUtils;
import net.ukrcom.asblockwar.graph.GraphBuilder;
import net.ukrcom.asblockwar.graph.GraphExporter;

/**
 * Контролер вікна перегляду графа залежностей RPSL-об'єктів.
 *
 * <p>При відкритті пропонує вибір між зовнішнім браузером (рекомендовано — відкриває
 * вже згенерований HTML-файл) та вбудованим WebView (може бути нестабільним для
 * великих графів &gt;10 000 вузлів через обмеження libjfxwebkit).
 */
@Slf4j
public class DependencyGraphController {

    @FXML
    private WebView webView;

    @FXML
    private Label statusLabel;

    // ── static factory ────────────────────────────────────────────────────────

    /**
     * Показує діалог вибору режиму перегляду і відкриває граф залежностей.
     *
     * @param owner батьківське вікно
     * @throws IOException якщо FXML-файл не вдалося завантажити
     */
    public static void show(Stage owner) throws IOException {
        ButtonType btnBrowser = new ButtonType("Браузер (рекомендовано)");
        ButtonType btnWebView = new ButtonType("WebView");

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(owner);
        ask.setTitle("Граф залежностей");
        ask.setHeaderText("Де відкрити граф залежностей?");
        ask.setContentText(
                "WebView може бути нестабільним при великих графах (>10 000 вузлів).\n"
                + "Рекомендовано: зовнішній браузер.");
        ask.getButtonTypes().setAll(btnBrowser, btnWebView, ButtonType.CANCEL);

        Optional<ButtonType> choice = ask.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) return;

        if (choice.get() == btnBrowser) {
            openInExternalBrowser(owner);
        } else {
            openInWebView(owner);
        }
    }

    // ── browser mode ──────────────────────────────────────────────────────────

    private static void openInExternalBrowser(Stage owner) {
        Path htmlPath = Path.of(ASBlockWar.config.getDependencyGraphPath()).toAbsolutePath();
        if (!Files.exists(htmlPath)) {
            new Alert(Alert.AlertType.ERROR,
                    "Файл графа не знайдено:\n" + htmlPath
                    + "\n\nВиконайте Run для генерації.",
                    ButtonType.OK).showAndWait();
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(htmlPath.toUri());
            } else {
                new ProcessBuilder("xdg-open", htmlPath.toString()).inheritIO().start();
            }
        } catch (IOException e) {
            log.error("Не вдалося відкрити браузер", e);
            new Alert(Alert.AlertType.ERROR,
                    "Не вдалося відкрити браузер:\n" + e.getMessage(),
                    ButtonType.OK).showAndWait();
        }
    }

    // ── WebView mode ──────────────────────────────────────────────────────────

    private static void openInWebView(Stage owner) throws IOException {
        URL fxmlUrl = DependencyGraphController.class.getResource("/fxml/DependencyGraphView.fxml");
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        DependencyGraphController ctrl = loader.getController();

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("ASBlockWar — Dependency Graph");
        stage.setScene(new Scene(root, 1280, 900));
        stage.setMaximized(true);
        stage.show();

        ctrl.buildAndDisplay();
    }

    // ── WebView builder ───────────────────────────────────────────────────────

    private void buildAndDisplay() {
        statusLabel.setText("Будуємо граф залежностей…");

        Thread.ofVirtual().start(() -> {
            try {
                Set<String> allMntBy  = FileUtils.readFileEntries(
                        Path.of(ASBlockWar.config.getListMntbyFile()));
                Set<String> allAsSets = FileUtils.readFileEntries(
                        Path.of(ASBlockWar.config.getListAssetFile()));

                GraphBuilder graph = GraphBuilder.build(
                        ASBlockWar.lastAggressorAsnResources,
                        ASBlockWar.suspiciousAsnResources,
                        ASBlockWar.resourcesForVerification,
                        allMntBy,
                        allAsSets);

                String html = GraphExporter.generateHtml(graph);
                long nodes = graph.getNodes().size();
                long edges = graph.getEdges().size();

                Platform.runLater(() -> {
                    webView.getEngine().loadContent(html, "text/html");
                    statusLabel.setText(String.format(
                            "Граф: %d вузлів, %d ребер", nodes, edges));
                });
            } catch (IOException e) {
                log.error("Не вдалося побудувати граф залежностей", e);
                Platform.runLater(() -> statusLabel.setText("Помилка: " + e.getMessage()));
            }
        });
    }
}
