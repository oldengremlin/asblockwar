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

import ch.qos.logback.classic.LoggerContext;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;
import org.slf4j.LoggerFactory;

/**
 * Controller for RunProgressDialog.fxml — modal window that shows a live log
 * stream while ASBlockWar processing runs in the background.
 *
 * @author olden
 */
public class RunProgressController implements Initializable {

    @FXML private ProgressBar progressBar;
    @FXML private TextArea logArea;
    @FXML private Button closeButton;

    private Stage stage;
    private GuiLogAppender appender;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
    }

    private static final long HIGHLIGHT_INTERVAL_MS = 100;

    /**
     * Attach a Logback appender and a rate-limited UIProgressCallback, start
     * processing on a daemon thread, and block the OS close button until done.
     */
    public void startProcessing(Stage dialogStage, MainWindowsController mainCtrl) {
        this.stage = dialogStage;

        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new GuiLogAppender(this::appendLine);
        appender.setContext(lc);
        appender.start();
        lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(appender);

        AtomicLong lastHighlight = new AtomicLong(0);
        ASBlockWar.uiCallback = new ASBlockWar.UIProgressCallback() {
            @Override
            public void onAsnProcessing(String asn) {
                if (throttle(lastHighlight)) {
                    mainCtrl.highlightAsn(asn);
                }
            }
            @Override
            public void onAsSetProcessing(String asSet) {
                if (throttle(lastHighlight)) {
                    mainCtrl.highlightAsSet(asSet);
                }
            }
            @Override
            public void onMntByProcessing(String mntBy) {
                if (throttle(lastHighlight)) {
                    mainCtrl.highlightMntBy(mntBy);
                }
            }
        };

        stage.setOnCloseRequest(e -> {
            if (closeButton.isDisable()) {
                e.consume();
            }
        });

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ASBlockWar.runProcessing();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            ASBlockWar.uiCallback = null;
            mainCtrl.clearHighlight();
            detachAppender();
            progressBar.setProgress(1.0);
            appendLine("--- Done ---");
            closeButton.setDisable(false);
            stage.setTitle("ASBlockWar — Done");
        });

        task.setOnFailed(e -> {
            ASBlockWar.uiCallback = null;
            mainCtrl.clearHighlight();
            Throwable ex = task.getException();
            detachAppender();
            progressBar.setProgress(0.0);
            appendLine("--- Error: " + (ex != null ? ex.getMessage() : "unknown") + " ---");
            ASBlockWar.LOGGER.error("GUI: runProcessing failed", ex);
            closeButton.setDisable(false);
            stage.setTitle("ASBlockWar — Error");
        });

        Thread thread = new Thread(task, "asblockwar-run");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean throttle(AtomicLong lastTs) {
        long now = System.currentTimeMillis();
        long prev = lastTs.get();
        return (now - prev) >= HIGHLIGHT_INTERVAL_MS && lastTs.compareAndSet(prev, now);
    }

    private void appendLine(String line) {
        Platform.runLater(() -> {
            logArea.appendText(line + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void detachAppender() {
        if (appender != null) {
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
            appender = null;
        }
    }

    @FXML
    private void doClose() {
        if (stage != null) {
            stage.hide();
        }
    }
}
