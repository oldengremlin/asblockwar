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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.function.Consumer;

/**
 * Logback appender that forwards formatted log lines to a JavaFX consumer
 * (typically a TextArea). Thread-safe: the consumer is expected to marshal
 * to the FX thread via Platform.runLater.
 */
public class GuiLogAppender extends AppenderBase<ILoggingEvent> {

    private final Consumer<String> handler;

    /**
     * Створює аппендер з вказаним обробником рядків лога.
     *
     * @param handler callback, що отримує відформатовані рядки лога;
     *                відповідає за маршалінг у FX-потік через {@code Platform.runLater}
     */
    public GuiLogAppender(Consumer<String> handler) {
        this.handler = handler;
    }

    /**
     * Форматує подію лога у рядок виду {@code "[LEVEL] message"} і передає
     * його обробнику. Події нижче рівня INFO ігноруються.
     *
     * @param event подія Logback для виводу
     */
    @Override
    protected void append(ILoggingEvent event) {
        if (handler == null || !event.getLevel().isGreaterOrEqual(Level.INFO)) {
            return;
        }
        String line = "[" + event.getLevel() + "] " + event.getFormattedMessage();
        handler.accept(line);
    }
}
