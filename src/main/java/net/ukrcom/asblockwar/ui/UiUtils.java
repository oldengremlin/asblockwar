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
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Shared UI utilities for the ASBlockWar JavaFX front-end.
 */
class UiUtils {

    private UiUtils() {}

    /**
     * Creates a {@link Scene} for the given root and attaches the shared
     * application stylesheet ({@code mainwindows.css}).
     *
     * @param root the scene-graph root to wrap
     * @return a new Scene with the stylesheet applied
     */
    static Scene styledScene(Parent root) {
        Scene scene = new Scene(root);
        URL css = UiUtils.class.getResource("/styles/mainwindows.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        return scene;
    }
}
