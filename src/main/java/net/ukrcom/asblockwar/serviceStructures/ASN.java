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
package net.ukrcom.asblockwar.serviceStructures;

/**
 * Незмінний запис, що описує одну операцію над автономною системою (ASN).
 *
 * <p>Використовується для передачі дій (додавання, видалення, модифікація)
 * між компонентами обробки, де {@code asn} — ідентифікатор АС,
 * а {@code data} — додаткові дані (наприклад, список префіксів).
 *
 * @param action тип дії ({@link Action#add}, {@link Action#remove}, {@link Action#modify})
 * @param asn    рядкове позначення автономної системи (наприклад, {@code "AS12345"})
 * @param data   додаткові дані, пов'язані з операцією (може бути {@code null})
 * @author olden
 */
public record ASN(Action action, String asn, String data) {

}
