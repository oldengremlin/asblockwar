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

import java.util.Map;
import java.util.Set;

/**
 * Обчислені зміни для blackbgp та нові ворожі ASN, виявлені під час перевірки маршрутів.
 *
 * @param toDelete          префікси на зняття з blackbgp
 * @param toReplace         префікси на додавання/оновлення
 * @param newEnemies        ASN → RPSL-блок для ворожих AS, виявлених під час звірки
 * @param effectivePrefixes стан blackbgp після застосування змін
 * @param maskedPrefixes    префікс → {@code "DE (RU)"}: маршрут переоформлено під
 *                          ASN «чистої» країни, але покривний inetnum/inet6num
 *                          належить блокованій. Такі лишаються заблокованими,
 *                          а не знімаються
 */
public record BlackbgpChanges(
        Set<String> toDelete,
        Set<String> toReplace,
        Map<String, String> newEnemies,
        Set<String> effectivePrefixes,
        Map<String, String> maskedPrefixes) {

}
