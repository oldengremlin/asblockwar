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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Тести розбору RPSL-полів.
 */
class RpslUtilsTest {

    private static final String BLOCK = """
        organisation:   ORG-EX1-RIPE
        org-name:       Example Telecom
        address:        Main str. 1
        address:        Kyiv
        address:        01001
        address:        UA
        country:        UA
        """;

    @Test
    @DisplayName("rpslField повертає перше значення")
    void singleValue() {
        assertEquals("Example Telecom", RpslUtils.rpslField(BLOCK, "org-name"));
        assertEquals("Main str. 1", RpslUtils.rpslField(BLOCK, "address"));
    }

    @Test
    @DisplayName("rpslFieldJoined збирає всі рядки address:")
    void multilineAddressJoined() {
        assertEquals("Main str. 1, Kyiv, 01001, UA",
                RpslUtils.rpslFieldJoined(BLOCK, "address", ", "));
    }

    @Test
    @DisplayName("Відсутнє поле дає порожній рядок")
    void missingField() {
        assertEquals("", RpslUtils.rpslField(BLOCK, "descr"));
        assertEquals("", RpslUtils.rpslFieldJoined(BLOCK, "descr", ", "));
        assertEquals("", RpslUtils.rpslFieldJoined(null, "address", ", "));
    }

    @Test
    @DisplayName("Порожнє поле не затягує наступний рядок у значення")
    void emptyFieldDoesNotSwallowNextLine() {
        // Саме тут ламався граф: \\s у Java матчить і \\n, тож "org:" без значення
        // з'їдав перенесення рядка і хапав "ORG-NAME:" як ідентифікатор org.
        String block = "aut-num:        AS64512\norg:\norg-name:       Example\n";
        Pattern fixed = Pattern.compile("(?m)^org:[ \\t]*(\\S+)");
        Matcher m = fixed.matcher(block);
        assertFalse(m.find(), "порожнє org: не повинно давати збіг");

        Pattern broken = Pattern.compile("(?m)^org:\\s*(\\S+)");
        Matcher b = broken.matcher(block);
        assertEquals(true, b.find());
        assertEquals("org-name:", b.group(1), "демонструє стару поведінку");
    }

    @Test
    @DisplayName("mp-members розпізнається нарівні з members")
    void mpMembersRecognised() {
        Pattern p = Pattern.compile("(?m)^(?:mp-)?members:[ \\t]*(.+)$");
        Matcher m = p.matcher("as-set: AS-EX\nmp-members:     AS64512, AS64513\n");
        assertEquals(true, m.find());
        assertEquals("AS64512, AS64513", m.group(1));
    }
}
