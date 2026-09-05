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
 * Маршрут, який лишили в blackbgp за покривним {@code inetnum}, попри
 * «чистий» (або взагалі відсутній) {@code route:}.
 * <p>
 * Дані збираються там, де їх уже прочитано — у {@code discoverBlackbgpChanges}.
 * Пізніше взяти їх нема звідки: origin такого маршруту за побудовою <b>не</b>
 * ворожий (інакше його зняли б Перевірки 1–2), тож його немає ні в
 * {@code aggressorAsnResources}, ні в кеші {@code STORE/AS/} — і звіт лишався б
 * без назви організації саме там, де вона найпотрібніша.
 *
 * @param origin       origin-ASN маршруту; порожній, якщо {@code route:} прибрано з RIPE
 * @param countryChain країни в порядку, в якому їх віддає whois: {@code country:}
 *                     покривного inetnum, потім його {@code organisation},
 *                     наостанок країна origin-ASN — наприклад {@code "RU, EE"}
 * @param orgName      {@code org-name:} origin-ASN або порожній рядок
 * @param evidence     RPSL-блоки покривного {@code inetnum} та його
 *                     {@code organisation} — те, звідки взялася ворожа країна
 */
public record MaskedRoute(
        String origin,
        String countryChain,
        String orgName,
        String evidence) {

}
