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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.UIProgressCallback;

/**
 * Запуск AfterCommand-скрипту після завершення основної обробки.
 * <p>
 * Підтримує CLI-режим (вивід у консоль) та GUI-режим (потоковий рядок за рядком через {@link UIProgressCallback}).
 */
@Slf4j
public class BatchRunner {

    private BatchRunner() {
    }

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
        if (ASBlockWar.config.isDryRun()) {
            log.info("DRY-RUN: AfterCommand не запускається");
            return;
        }
        String cmd = ASBlockWar.config.getAfterCommand();
        File scriptFile = new File(cmd);
        if (!scriptFile.exists()) {
            log.warn("AfterCommand: файл не знайдено: {}", cmd);
            return;
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!isWindows && !scriptFile.canExecute()) {
            log.warn("AfterCommand: файл не має атрибуту виконання: {}", cmd);
            return;
        }
        log.info("AfterCommand: запуск {}", cmd);
        ProcessBuilder pb = isWindows
                            ? new ProcessBuilder("cmd.exe", "/c", scriptFile.getAbsolutePath())
                            : new ProcessBuilder(scriptFile.getAbsolutePath());
        pb.directory(new File(System.getProperty("user.dir")));
        UIProgressCallback cb = ASBlockWar.uiCallback;
        try {
            long t0 = System.nanoTime();
            if (cb == null) {
                // CLI-режим: вивід успадковується консоллю
                pb.inheritIO();
                int code = pb.start().waitFor();
                log.info("AfterCommand: завершено з кодом {} за {}.", code, formatDuration(System.nanoTime() - t0));
            } else {
                // GUI-режим: потоковий вивід рядок за рядком з розрізненням stdout/stderr
                pb.redirectErrorStream(false);
                Process proc = pb.start();
                Thread stdoutThread = pipeStream(proc.getInputStream(), cb, false);
                Thread stderrThread = pipeStream(proc.getErrorStream(), cb, true);
                int code = proc.waitFor();
                stdoutThread.join();
                stderrThread.join();
                log.info("AfterCommand: завершено з кодом {} за {}.", code, formatDuration(System.nanoTime() - t0));
            }
        } catch (IOException | InterruptedException e) {
            log.error("AfterCommand: помилка виконання: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String formatDuration(long nanos) {
        long ms   = nanos / 1_000_000L;
        long secs = ms / 1000;
        long min  = secs / 60;
        return min > 0
                ? String.format("%d хв %02d.%03d с", min, secs % 60, ms % 1000)
                : String.format("%d.%03d с", secs, ms % 1000);
    }

    private static Thread pipeStream(InputStream stream, UIProgressCallback cb, boolean isStderr) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null) {
                    cb.onBatchOutputLine(line, isStderr);
                }
            } catch (IOException ignored) {
            }
        });
    }
}
