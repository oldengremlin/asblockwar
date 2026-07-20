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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.ASBlockWar;

/**
 * Утиліти для читання та запису файлів конфігурації та сховища.
 * <p>
 * Забезпечує атомарний запис через тимчасові файли та файлові блокування,
 * а також читання списків записів з файлів.
 */
@Slf4j
public class FileUtils {

    private FileUtils() {
    }

    /**
     * Читає непорожні рядки файлу, пропускаючи коментарі ({@code #}, {@code ;}).
     *
     * @param path шлях до файлу
     * @return {@link Set} рядків або порожня множина, якщо файл не існує
     * @throws IOException якщо виникла помилка читання файлу
     */
    public static Set<String> readFileEntries(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Set.of();
        }
        try (Stream<String> lines = Files.lines(path)) {
            return lines
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith(";"))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Створює директорію (включно з батьківськими) та встановлює права {@code rwxr-x---}.
     * <p>
     * Якщо файлова система не підтримує POSIX-права, виняток тихо ігнорується.
     *
     * @param dir шлях до директорії
     * @throws IOException якщо директорію не вдалося створити
     */
    public static void ensureStoreDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    /**
     * Атомарно записує вміст у файл через тимчасовий файл і файлове блокування.
     * <p>
     * Якщо {@code content} порожній або {@code null} — нічого не робить.
     * Використовує {@link FileLock} для захисту від паралельного запису та
     * {@link StandardCopyOption#ATOMIC_MOVE} для безпечної заміни файлу.
     *
     * @param file шлях до цільового файлу
     * @param content вміст для запису
     * @throws IOException якщо виникла помилка запису або переміщення файлу
     */
    public static void writeStoreFile(Path file, String content) throws IOException {
        if (content == null || content.isBlank()) {
            return;
        }
        // У режимі dry-run жодних записів на диск не виконуємо
        if (ASBlockWar.config != null && ASBlockWar.config.isDryRun()) {
            log.info("DRY-RUN: skip write → {}", file);
            return;
        }
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel lc = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fl = lc.lock()) {
                Files.writeString(tmp, content);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(lockPath);
            Files.deleteIfExists(tmp);
        }
    }
}
