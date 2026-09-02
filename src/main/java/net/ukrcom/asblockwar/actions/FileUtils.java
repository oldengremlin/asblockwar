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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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

    /** Лічильник для унікальних імен тимчасових файлів у межах процесу. */
    private static final AtomicLong TMP_SEQ = new AtomicLong();

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
     * Атомарно записує вміст у файл через тимчасовий файл.
     * <p>
     * Якщо {@code content} порожній або {@code null} — нічого не робить.
     * Кожен файл у STORE/ записується рівно одним потоком, тому FileLock не потрібен;
     * {@link StandardCopyOption#ATOMIC_MOVE} забезпечує безпечну заміну файлу.
     *
     * @param file шлях до цільового файлу
     * @param content вміст для запису
     * @throws IOException якщо виникла помилка запису або переміщення файлу
     */
    /**
     * Вирішує ім'я файлу відносно директорії, не дозволяючи вийти за її межі.
     * <p>
     * Імена в {@code STORE/} формуються з mntner, AS-SET та інших значень, що
     * походять із редагованих користувачем списків і з RPSL-даних. Значення
     * на кшталт {@code ../../etc/cron.d/job} інакше записалося б поза {@code STORE/}.
     *
     * @param dir  базова директорія
     * @param name ім'я файлу з недовіреного джерела
     * @return шлях усередині {@code dir}
     * @throws IOException якщо ім'я виводить за межі {@code dir}
     */
    public static Path safeResolve(Path dir, String name) throws IOException {
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(name).normalize();
        if (!target.startsWith(base)) {
            throw new IOException("Некоректне ім'я файлу «" + name + "» — виходить за межі " + base);
        }
        return target;
    }

    public static void writeStoreFile(Path file, String content) throws IOException {
        if (content == null || content.isBlank()) {
            return;
        }
        // У режимі dry-run жодних записів на диск не виконуємо
        if (ASBlockWar.config != null && ASBlockWar.config.isDryRun()) {
            log.debug("DRY-RUN: skip write → {}", file);
            return;
        }
        replaceAtomically(file, content);
    }

    /**
     * Замінює вміст файлу атомарно: запис у тимчасовий файл поруч, потім
     * {@code ATOMIC_MOVE} на місце цільового.
     * <p>
     * Ім'я тимчасового файлу містить PID і власний лічильник, тож у двох
     * одночасних записів у той самий шлях воно ніколи не збігається. Фіксоване
     * {@code <файл>.tmp} цього не давало: другий записувач перетирав чужий
     * тимчасовий файл, перший переносив його на місце, а другий отримував
     * {@code NoSuchFileException: …tmp -> …txt}. Гірше за сам виняток те, що
     * перенесений файл при цьому міг містити вміст іншого записувача, а
     * прибирання в {@code finally} видаляло щойно створений чужий файл.
     * <p>
     * Записувачами можуть бути і два процеси ASBlockWar над спільним
     * {@code STORE/} — жодного блокування там немає, і покладатися на те, що
     * кожен шлях пише рівно одна задача, не можна.
     * <p>
     * Права доступу лишаються звичайними ({@code 0666 &amp; ~umask}), бо файл
     * створює {@code Files.writeString}, а не {@code Files.createTempFile} —
     * останній дав би {@code 0600}, і після перенесення цільовий файл став би
     * недоступним для читання іншим користувачам.
     *
     * @param file    цільовий файл
     * @param content вміст, який має опинитися у файлі
     * @throws IOException якщо запис або перенесення не вдалися
     */
    public static void replaceAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "."
                + ProcessHandle.current().pid() + "-" + TMP_SEQ.incrementAndGet() + ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
