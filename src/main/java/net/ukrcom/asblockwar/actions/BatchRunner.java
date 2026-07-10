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
package net.ukrcom.asblockwar.actions;

import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.UIProgressCallback;

/**
 * Запуск AfterCommand-скрипту після завершення основної обробки.
 * <p>
 * Підтримує CLI-режим (вивід у консоль) та GUI-режим (потоковий рядок за рядком через {@link UIProgressCallback}).
 */
public class BatchRunner {

    private BatchRunner() {}

    /**
     * Запускає AfterCommand-скрипт після завершення обробки, якщо увімкнено пакетний режим ({@code -b}).
     *
     * <p>Відсутність файлу або відсутність прав виконання (на Unix-подібних системах) не призводить
     * до аварійного завершення — лише до попередження в лозі. В CLI-режимі вивід скрипту
     * успадковується консоллю процесу; в GUI-режимі кожен рядок передається через
     * {@link UIProgressCallback#onBatchOutputLine(String, boolean)}.
     */
    public static void runBatchCommand() {
        if (!ASBlockWar.config.isBatchMode()) {
            return;
        }
        String cmd = ASBlockWar.config.getAfterCommand();
        java.io.File scriptFile = new java.io.File(cmd);
        if (!scriptFile.exists()) {
            ASBlockWar.LOGGER.warn("AfterCommand: файл не знайдено: {}", cmd);
            return;
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!isWindows && !scriptFile.canExecute()) {
            ASBlockWar.LOGGER.warn("AfterCommand: файл не має атрибуту виконання: {}", cmd);
            return;
        }
        ASBlockWar.LOGGER.info("AfterCommand: запуск {}", cmd);
        ProcessBuilder pb = isWindows
                            ? new ProcessBuilder("cmd.exe", "/c", scriptFile.getAbsolutePath())
                            : new ProcessBuilder(scriptFile.getAbsolutePath());
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        UIProgressCallback cb = ASBlockWar.uiCallback;
        try {
            if (cb == null) {
                // CLI-режим: вивід успадковується консоллю
                pb.inheritIO();
                int code = pb.start().waitFor();
                ASBlockWar.LOGGER.info("AfterCommand: завершено з кодом {}", code);
            } else {
                // GUI-режим: потоковий вивід рядок за рядком з розрізненням stdout/stderr
                pb.redirectErrorStream(false);
                Process proc = pb.start();
                Thread stdoutThread = Thread.ofVirtual().start(() -> {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            cb.onBatchOutputLine(line, false);
                        }
                    } catch (java.io.IOException ignored) {
                    }
                });
                Thread stderrThread = Thread.ofVirtual().start(() -> {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getErrorStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            cb.onBatchOutputLine(line, true);
                        }
                    } catch (java.io.IOException ignored) {
                    }
                });
                int code = proc.waitFor();
                stdoutThread.join();
                stderrThread.join();
                ASBlockWar.LOGGER.info("AfterCommand: завершено з кодом {}", code);
            }
        } catch (java.io.IOException | InterruptedException e) {
            ASBlockWar.LOGGER.error("AfterCommand: помилка виконання: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
