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

import java.net.URL;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

/**
 * Точка входу JavaFX-застосунку для GUI-режиму (прапор {@code --gui} / {@code -g}).
 *
 * <p>Завантажує головне вікно ({@code MainWindows.fxml}) і підключає CSS-стилі.
 *
 * @author olden
 */
public class ASBlockWarApp extends Application {

    /**
     * Ініціалізує та відображає головне вікно застосунку.
     *
     * @param stage первинне вікно JavaFX Runtime
     * @throws Exception якщо FXML або CSS-ресурс не вдається завантажити
     */
    @Override
    public void start(Stage stage) throws Exception {
        URL fxmlUrl = getClass().getResource("/fxml/MainWindows.fxml");
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        DialogPane root = loader.load();

        Scene scene = UiUtils.styledScene(root);

        stage.setTitle("ASBlockWar");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> System.exit(0));
        stage.show();
    }
}
