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
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

    /** Поріг розміру файлу, вище якого WebView не пропонується (2 МБ). */
    private static final long WEBVIEW_SIZE_LIMIT_BYTES = 2L * 1024 * 1024;

    /**
     * Показує діалог вибору режиму перегляду і відкриває граф залежностей.
     *
     * <p>Якщо файл графа перевищує {@value #WEBVIEW_SIZE_LIMIT_BYTES} байт —
     * WebView не пропонується: завантаження великого Canvas-графа з D3-симуляцією
     * у libjfxwebkit блокує JavaFX Application Thread і призводить до «зависання».
     *
     * @param owner батьківське вікно
     * @throws IOException якщо FXML-файл не вдалося завантажити
     */
    public static void show(Stage owner) throws IOException {
        Path htmlPath = Path.of(ASBlockWar.config.getDependencyGraphPath()).toAbsolutePath();
        if (!Files.exists(htmlPath)) {
            new Alert(Alert.AlertType.ERROR,
                    "Файл графа не знайдено:\n" + htmlPath
                    + "\n\nВиконайте Run для генерації.",
                    ButtonType.OK).showAndWait();
            return;
        }

        long fileSize;
        try { fileSize = Files.size(htmlPath); } catch (IOException e) { fileSize = 0; }
        boolean largeGraph = fileSize > WEBVIEW_SIZE_LIMIT_BYTES;

        if (largeGraph) {
            long mb = fileSize / (1024 * 1024);
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.initOwner(owner);
            info.setTitle("Граф залежностей");
            info.setHeaderText("Великий граф (" + mb + " МБ) — відкриваємо у браузері");
            info.setContentText(
                    "WebView може зависнути для графів такого розміру.\n"
                    + "Файл буде відкрито у зовнішньому браузері.");
            info.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            Optional<ButtonType> r = info.showAndWait();
            if (r.isEmpty() || r.get() == ButtonType.CANCEL) return;
            openInExternalBrowser(htmlPath);
            return;
        }

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
            openInExternalBrowser(htmlPath);
        } else {
            openInWebView(owner);
        }
    }

    // ── browser mode ──────────────────────────────────────────────────────────

    private static void openInExternalBrowser(Path htmlPath) {
        Thread.ofVirtual().start(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    launch("cmd", "/c", "start", "", htmlPath.toUri().toString());
                } else if (os.contains("mac")) {
                    launch("open", htmlPath.toString());
                } else {
                    // Linux: try launchers in order until one succeeds
                    if (!launch("xdg-open",        htmlPath.toString()) &&
                        !launch("firefox",          htmlPath.toString()) &&
                        !launch("chromium-browser", htmlPath.toString()) &&
                        !launch("chromium",         htmlPath.toString())) {
                        log.error("Жоден браузер не знайдено. Відкрийте вручну: {}", htmlPath);
                        Platform.runLater(() ->
                            new Alert(Alert.AlertType.WARNING,
                                    "Браузер не знайдено.\nВідкрийте файл вручну:\n" + htmlPath,
                                    ButtonType.OK).showAndWait());
                    }
                }
            } catch (Exception e) {
                log.error("Не вдалося відкрити браузер", e);
                Platform.runLater(() ->
                    new Alert(Alert.AlertType.ERROR,
                            "Не вдалося відкрити браузер:\n" + e.getMessage(),
                            ButtonType.OK).showAndWait());
            }
        });
    }

    /**
     * Запускає команду і чекає до 2 с; повертає {@code true} якщо процес стартував
     * та вийшов з кодом 0, або якщо він ще виконується (браузер відкрився).
     */
    private static boolean launch(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean exited = p.waitFor(2, TimeUnit.SECONDS);
            return !exited || p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
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

    /**
     * Завантажує вже згенерований HTML-файл у WebView через {@code file://} URL,
     * що дозволяє браузерному рушію завантажити CDN-скрипти (d3.js) та правильно
     * відобразити canvas-граф.
     */
    private void buildAndDisplay() {
        Path htmlPath = Path.of(ASBlockWar.config.getDependencyGraphPath()).toAbsolutePath();
        if (!Files.exists(htmlPath)) {
            statusLabel.setText("Файл не знайдено: " + htmlPath + "\nВиконайте Run спочатку.");
            return;
        }
        statusLabel.setText("Завантажуємо " + htmlPath.getFileName() + "…");
        webView.getEngine().load(htmlPath.toUri().toString());
    }
}
