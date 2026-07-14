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
package net.ukrcom.asblockwar;

/**
 * Зворотній виклик для оновлення GUI під час обробки.
 * <p>
 * У CLI-режимі {@link ASBlockWar#uiCallback} дорівнює {@code null}.
 * В GUI-режимі реалізацію встановлює {@code RunProgressController}.
 */
public interface UIProgressCallback {

    /** Викликається перед запитом RPSL-блоку для ASN.
     * @param asn номер автономної системи у форматі {@code "ASNnnn"}, для якої запитується RPSL-блок */
    void onAsnProcessing(String asn);

    /** Викликається перед запитом RPSL-блоку для AS-SET.
     * @param asSet назва набору AS-SET у форматі RPSL, наприклад {@code "AS-EXAMPLE"}, для якого запитується RPSL-блок */
    void onAsSetProcessing(String asSet);

    /** Викликається перед запитом RPSL-блоку для MNT-BY.
     * @param mntBy ідентифікатор мантейнера RPSL, наприклад {@code "EXAMPLE-MNT"}, для якого запитується RPSL-блок */
    void onMntByProcessing(String mntBy);

    /**
     * Викликається для кожного рядка виводу AfterCommand-скрипту в пакетному режимі ({@code -b}).
     * @param line   рядок виводу скрипту
     * @param stderr {@code true}, якщо рядок надійшов зі stderr (відображається червоним у GUI)
     */
    default void onBatchOutputLine(String line, boolean stderr) {
    }
}
