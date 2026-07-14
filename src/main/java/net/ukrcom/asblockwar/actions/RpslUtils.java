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

/**
 * Утиліти для розбору текстових RPSL-блоків.
 * <p>
 * Містить чисті функції без зовнішніх залежностей для витягування полів з RPSL-тексту.
 */
public class RpslUtils {

    private RpslUtils() {
    }

    /**
     * Витягує перше значення вказаного поля RPSL з текстового блоку.
     * <p>
     * Наприклад, для {@code key = "org-name"} і блоку {@code "org-name: Example Corp\n..."}
     * поверне {@code "Example Corp"}.
     *
     * @param block текстовий RPSL-блок
     * @param key назва поля (без двокрапки)
     * @return перше значення поля або порожній рядок, якщо поле відсутнє
     */
    public static String rpslField(String block, String key) {
        if (block == null || block.isEmpty()) {
            return "";
        }
        String prefix = key + ":";
        return block.lines()
                .filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).trim())
                .findFirst()
                .orElse("");
    }
}
