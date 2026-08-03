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
package net.ukrcom.asblockwar.retrieveretrieve;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Витягує повний RPSL-блок as-set для заданого імені AS-SET
 * з локальної бази даних whois-lite-local.
 *
 * @author olden
 */
@Slf4j
public class retrieveAsSet {

    private static final RpslCache cache = RpslCache.create();

    private StringBuilder sb;

    private final String asSet;

    /**
     * Відкриває з'єднання з БД і завантажує RPSL-блок для вказаного AS-SET.
     *
     * @param asSet назва AS-SET (наприклад, {@code "AS-EXAMPLE"})
     */
    public retrieveAsSet(String asSet) {
        this.sb = new StringBuilder();
        this.asSet = asSet;

        String cached = cache.get(asSet);
        if (cached != null) {
            this.sb.append(cached);
            return;
        }

        try (Connection conn = RpslDb.open()) {
            this.sb.append(RpslDb.fetchBlocks(conn, asSet, "as-set"));
            // Кешуємо ЛИШЕ успішний результат: інакше одна транзієнтна помилка
            // (SQLITE_BUSY, оновлення БД ззовні) назавжди зафіксувала б порожнє значення
            cache.put(asSet, this.sb.toString());
        } catch (SQLException ex) {
            log.error("Помилка при отриманні AsSet {}", asSet, ex);
        }
    }

    /**
     * Повертає RPSL-блок as-set у вигляді рядка.
     *
     * @return текст RPSL-блоку, або порожній рядок, якщо AS-SET не знайдено
     */
    public String get() {
        log.debug("retrieveAsSet({}).get(): {}", this.asSet, this.sb.toString());
        return this.sb.toString();
    }

}
