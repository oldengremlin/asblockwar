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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Кеш RPSL-блоків між запусками обробки.
 * <p>
 * Замінює три побайтово однакові копії {@code static Map<String,String> cache}
 * у {@code retrieveAsSet}, {@code retrieveMntBy} і {@code retrieveOrganisation}.
 * Кожен екземпляр реєструється у спільному реєстрі, тож {@link #clearAll()}
 * очищає всі кеші одним викликом — раніше додавання четвертого кешу вимагало
 * не забути про ще один рядок у {@code ASBlockWar}.
 */
public final class RpslCache {

    private static final List<RpslCache> REGISTRY = new CopyOnWriteArrayList<>();

    private final Map<String, String> entries = new ConcurrentHashMap<>();

    private RpslCache() {
    }

    /**
     * Створює новий кеш і реєструє його для {@link #clearAll()}.
     *
     * @return новий порожній кеш
     */
    public static RpslCache create() {
        RpslCache cache = new RpslCache();
        REGISTRY.add(cache);
        return cache;
    }

    /** Очищає всі зареєстровані кеші — викликається при скиданні стану між запусками. */
    public static void clearAll() {
        REGISTRY.forEach(c -> c.entries.clear());
    }

    /**
     * @param key ключ
     * @return закешоване значення або {@code null}
     */
    public String get(String key) {
        return entries.get(key);
    }

    /**
     * Кешує значення. Викликати <b>лише</b> після успішного запиту: закешований
     * порожній результат невідрізнимий від «запису немає» і живе до кінця процесу.
     *
     * @param key   ключ
     * @param value значення
     */
    public void put(String key, String value) {
        entries.put(key, value);
    }
}
