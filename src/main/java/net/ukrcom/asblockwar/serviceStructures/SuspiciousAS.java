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
 * Незмінний запис, що описує AS, яка збігається з {@code AggressorPattern},
 * але чия країна не входить до списку {@code BlockCountry}.
 *
 * <p>Використовується для формування звіту про потенційно ворожі AS,
 * що уникли блокування через невідповідність коду країни.
 *
 * @param asn         рядкове позначення автономної системи (наприклад, {@code "AS12345"})
 * @param country     код країни з RPSL-блоку, або {@code "?"} якщо поле відсутнє
 * @param matchedLine рядок RPSL-блоку, що збігся з AggressorPattern
 * @author olden
 */
public record SuspiciousAS(String asn, String country, String matchedLine) {

}
