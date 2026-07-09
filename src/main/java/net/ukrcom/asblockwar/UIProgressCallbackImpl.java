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
 * Заглушка реалізації {@link ASBlockWar.UIProgressCallback} для використання
 * поза GUI-контекстом. Усі методи не підтримуються і кидають виняток —
 * клас призначений для заміни реальною реалізацією у графічному режимі.
 */
public class UIProgressCallbackImpl implements ASBlockWar.UIProgressCallback {

    /**
     * Викликається під час обробки кожного ASN.
     * Заглушка — не підтримується.
     *
     * @param asn рядкове позначення ASN (наприклад, {@code "AS12345"})
     */
    @Override
    public void onAsnProcessing(String asn) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    /**
     * Викликається під час обробки кожного AS-SET.
     * Заглушка — не підтримується.
     *
     * @param asSet назва AS-SET (наприклад, {@code "AS-EXAMPLE"})
     */
    @Override
    public void onAsSetProcessing(String asSet) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    /**
     * Викликається під час обробки кожного mnt-by дескриптора.
     * Заглушка — не підтримується.
     *
     * @param mntBy назва mnt-by (наприклад, {@code "MNTNER-UA"})
     */
    @Override
    public void onMntByProcessing(String mntBy) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

}
