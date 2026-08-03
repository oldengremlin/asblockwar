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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import javafx.stage.Stage;
import net.ukrcom.asblockwar.ASBlockWar;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.UIProgressCallback;
import org.slf4j.LoggerFactory;

/**
 * Controller for RunProgressDialog.fxml — modal window that shows a live log
 * stream while ASBlockWar processing runs in the background.
 *
 * @author olden
 */
@Slf4j
public class RunProgressController implements Initializable {

    @FXML
    private ProgressBar progressBar;
    @FXML
    private WebView logView;
    @FXML
    private Button closeButton;

    private Stage stage;
    private GuiLogAppender appender;
    private WebEngine engine;

    // Scripts queued before WebEngine finishes loading the initial document
    private boolean webReady = false;
    private final Queue<String> pendingScripts = new ArrayDeque<>();

    /**
     * Рядки логу, що чекають на відображення.
     * <p>
     * Раніше кожен рядок породжував власний {@code Platform.runLater} з окремим
     * {@code executeScript} — на прогоні в десятки тисяч рядків черга FX-потоку
     * росла необмежено і GUI переставав відповідати. Тепер рядки накопичуються
     * тут і виштовхуються пачкою раз на {@link #FLUSH_INTERVAL_MS}.
     */
    private final Queue<String> lineBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferedLines = new AtomicInteger();
    private Timeline flusher;

    /** Період виштовхування накопичених рядків у WebView. */
    private static final long FLUSH_INTERVAL_MS = 100;

    /** Стеля буфера: понад це найстаріші рядки відкидаються, щоб не з'їсти пам'ять. */
    private static final int MAX_BUFFERED_LINES = 20_000;

    /**
     * Ініціалізує WebEngine: завантажує початковий HTML-документ і чекає на SUCCEEDED
     * перед виконанням накопичених JS-викликів.
     * @param url
     * @param rb
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        engine = logView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, nw) -> {
            if (nw == Worker.State.SUCCEEDED) {
                webReady = true;
                String js;
                while ((js = pendingScripts.poll()) != null) {
                    engine.executeScript(js);
                }
            }
        });
        engine.loadContent(buildInitialHtml());

        flusher = new Timeline(new KeyFrame(Duration.millis(FLUSH_INTERVAL_MS), e -> flushLines()));
        flusher.setCycleCount(Timeline.INDEFINITE);
        flusher.play();
    }

    /** Виштовхує накопичені рядки одним викликом JS. Виконується на FX-потоці. */
    private void flushLines() {
        if (lineBuffer.isEmpty()) {
            return;
        }
        StringBuilder js = new StringBuilder();
        String line;
        while ((line = lineBuffer.poll()) != null) {
            bufferedLines.decrementAndGet();
            js.append(line).append(';');
        }
        if (webReady) {
            engine.executeScript(js.toString());
        } else {
            pendingScripts.add(js.toString());
        }
    }

    /** Ставить JS-виклик у чергу на найближче виштовхування. */
    private void enqueueScript(String js) {
        if (bufferedLines.get() >= MAX_BUFFERED_LINES) {
            lineBuffer.poll();
            bufferedLines.decrementAndGet();
        }
        lineBuffer.add(js);
        bufferedLines.incrementAndGet();
    }

    private static final long HIGHLIGHT_INTERVAL_MS = 100;

    /**
     * Attach a Logback appender and a rate-limited UIProgressCallback, start
     * processing on a daemon thread, and block the OS close button until done.
     * @param dialogStage
     * @param mainCtrl
     */
    public void startProcessing(Stage dialogStage, MainWindowsController mainCtrl) {
        this.stage = dialogStage;

        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new GuiLogAppender(this::appendLine);
        appender.setContext(lc);
        appender.start();
        lc.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(appender);

        AtomicLong lastHighlight = new AtomicLong(0);
        ASBlockWar.uiCallback = new UIProgressCallback() {
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

            @Override
            public void onBatchOutputLine(String line, boolean stderr) {
                appendBatchLine(line, stderr);
            }
        };

        stage.setOnCloseRequest(e -> {
            if (closeButton.isDisable()) {
                e.consume();
                return;
            }
            // Інакше Timeline і завантажений документ WebEngine лишаються
            // жити після закриття вікна
            shutdownView();
        });

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ASBlockWar.runProcessing();
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            try {
                ASBlockWar.uiCallback = null;
                mainCtrl.clearHighlight();
                detachAppender();
                progressBar.setProgress(1.0);
                appendLine("--- Done ---");
                stage.setTitle("ASBlockWar — Done");
            } finally {
                closeButton.setDisable(false);
            }
        });

        task.setOnFailed(e -> {
            try {
                ASBlockWar.uiCallback = null;
                mainCtrl.clearHighlight();
                Throwable ex = task.getException();
                detachAppender();
                progressBar.setProgress(0.0);
                appendLine("--- Error: " + (ex != null ? ex.getMessage() : "unknown") + " ---");
                log.error("GUI: runProcessing failed", ex);
                stage.setTitle("ASBlockWar — Error");
            } finally {
                closeButton.setDisable(false);
            }
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
        enqueueScript("appendLine(\"" + jsEscape(line) + "\",false)");
    }

    private void appendBatchLine(String line, boolean stderr) {
        enqueueScript("appendLine(\"" + jsEscape(line) + "\"," + stderr + ")");
    }

    private static String jsEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    // Must be called from any thread; executes or queues the JS on the FX thread.
    private void runScript(String js) {
        Platform.runLater(() -> {
            if (webReady) {
                engine.executeScript(js);
            } else {
                pendingScripts.add(js);
            }
        });
    }

    /**
     * Зупиняє періодичне виштовхування і звільняє WebEngine.
     * Без цього {@code Timeline} і завантажений документ жили б після закриття вікна.
     */
    private void shutdownView() {
        if (flusher != null) {
            flusher.stop();
            flusher = null;
        }
        flushLines();
        if (engine != null) {
            engine.loadContent("");
        }
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

    private static String buildInitialHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <style>
                  body {
                    font-family: monospace;
                    font-size: 12px;
                    margin: 4px;
                    padding: 0;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                    background: #ffffff;
                    color: #1e1e1e;
                  }
                  .err { color: #cc0000; }
                  @media (prefers-color-scheme: dark) {
                    body { background: #1e1e1e; color: #d4d4d4; }
                    .err { color: #ff6b6b; }
                  }
                </style>
                <script>
                  function appendLine(text, isErr) {
                    var div = document.createElement('div');
                    div.textContent = text;
                    if (isErr) div.className = 'err';
                    document.body.appendChild(div);
                    window.scrollTo(0, document.body.scrollHeight);
                  }
                </script>
                </head>
                <body></body>
                </html>
                """;
    }
}
