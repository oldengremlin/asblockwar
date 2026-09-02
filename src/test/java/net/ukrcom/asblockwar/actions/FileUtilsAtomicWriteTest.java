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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тести атомарного запису.
 * <p>
 * Ім'я тимчасового файлу було фіксованим — {@code <файл>.tmp}. Два записувачі
 * в той самий шлях (дві задачі в одному процесі або два запуски ASBlockWar над
 * спільним {@code STORE/}) використовували один і той самий тимчасовий файл:
 * другий перетирав його, перший переносив на місце, а другий отримував
 * {@code NoSuchFileException: …txt.tmp -> …txt} — саме те, що спостерігалося
 * на {@code STORE/AS-NET/}.
 */
class FileUtilsAtomicWriteTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Паралельні записи в один шлях не падають і не крадуть чужий tmp")
    void concurrentWritesToSamePathDoNotCollide() throws Exception {
        Path file = dir.resolve("42498.txt");
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int round = 0; round < 200 && failure.get() == null; round++) {
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 8; i++) {
                    final String content = "origin: as" + i + "\n";
                    ex.execute(() -> {
                        try {
                            FileUtils.replaceAtomically(file, content);
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        }
                    });
                }
            }
        }

        assertNull(failure.get(), "паралельний запис не має падати");
        assertTrue(Files.exists(file), "цільовий файл має існувати");
    }

    @Test
    @DisplayName("Після запису не лишається жодного тимчасового файлу")
    void noTemporaryFilesAreLeftBehind() throws IOException {
        FileUtils.replaceAtomically(dir.resolve("as.list"), "AS1\nAS2\n");

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("as.list"), names);
        }
    }

    @Test
    @DisplayName("Вміст файлу — рівно той, що передали")
    void contentIsWrittenVerbatim() throws IOException {
        Path file = dir.resolve("networks.list");
        FileUtils.replaceAtomically(file, "1.2.3.0/24\tas64500\n");
        assertEquals("1.2.3.0/24\tas64500\n", Files.readString(file));

        // Перезапис замінює вміст цілком, а не дописує
        FileUtils.replaceAtomically(file, "5.6.7.0/24\tas64501\n");
        assertEquals("5.6.7.0/24\tas64501\n", Files.readString(file));
    }

    @Test
    @DisplayName("Цільовий файл лишається доступним для читання іншим користувачам")
    void targetFileKeepsReadablePermissions() throws IOException {
        Path file = dir.resolve("AS.list");
        FileUtils.replaceAtomically(file, "AS1\n");

        // Files.createTempFile дав би 0600, і після ATOMIC_MOVE ці права
        // успадкував би цільовий файл — вміст STORE/ став би нечитабельним
        assertTrue(Files.getPosixFilePermissions(file).stream()
                .anyMatch(perm -> perm.name().startsWith("OTHERS_")
                        || perm.name().startsWith("GROUP_")),
                "права мають лишатися звичайними (0666 & ~umask), а не 0600");
    }
}
