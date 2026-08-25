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
package net.ukrcom.asblockwar.graph;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тести розбору RPSL у побудовнику графа. */
class GraphBuilderTest {

    @Test
    @DisplayName("members: з continuation-рядками читається повністю")
    void membersWithContinuationLines() {
        String rpsl = """
            as-set:         AS-EXAMPLE
            members:        AS1, AS2,
                            AS3, AS-CHILD
            members:        AS4
            tech-c:         DUMMY-RIPE
            """;
        List<String> lines = GraphBuilder.memberLines(rpsl);

        String all = String.join(" ", lines);
        for (String expected : List.of("AS1", "AS2", "AS3", "AS-CHILD", "AS4")) {
            assertTrue(all.contains(expected),
                    () -> "не знайдено «" + expected + "» у " + lines);
        }
    }

    @Test
    @DisplayName("Поле після members: не вважається його продовженням")
    void nextFieldEndsMembers() {
        String rpsl = """
            as-set:         AS-EXAMPLE
            members:        AS1
            tech-c:         SHOULD-NOT-APPEAR
            """;
        assertEquals(List.of("AS1"), GraphBuilder.memberLines(rpsl));
    }

    @Test
    @DisplayName("mp-members розпізнається нарівні з members")
    void mpMembersRecognised() {
        String rpsl = "as-set: AS-EX\nmp-members:     AS64512,\n                AS64513\n";
        assertEquals(List.of("AS64512,", "AS64513"), GraphBuilder.memberLines(rpsl));
    }

    @Test
    @DisplayName("Блок без members: дає порожній список")
    void noMembers() {
        assertEquals(List.of(), GraphBuilder.memberLines("as-set: AS-EX\ntech-c: X\n"));
    }
}
