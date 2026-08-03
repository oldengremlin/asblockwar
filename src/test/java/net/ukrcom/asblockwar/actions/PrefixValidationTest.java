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

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тести запобіжників, що стоять між недовіреним вводом і командами роутера
 * чи файловою системою.
 */
class PrefixValidationTest {

    @ParameterizedTest(name = "коректний: {0}")
    @ValueSource(strings = {
        "192.0.2.0/24", "10.0.0.0/8", "0.0.0.0/0", "255.255.255.255/32",
        "2001:db8::/32", "2001:db8::1/128", "::/0"
    })
    @DisplayName("Валідні CIDR-префікси приймаються")
    void validPrefixesAccepted(String prefix) {
        assertTrue(DiscoverAggressor.isValidPrefix(prefix));
    }

    @ParameterizedTest(name = "відхилено: {0}")
    @ValueSource(strings = {
        "192.0.2.0",              // без маски
        "300.1.2.3/24",           // октет поза межами
        "192.0.2.0/33",           // маска поза межами для IPv4
        "2001:db8::/129",         // маска поза межами для IPv6
        "192.0.2.0/abc",          // маска не число
        "192.0.2.0/24 ; reboot",  // ін'єкція команди
        "10.0.0.0/8\nrm -rf /",   // перенесення рядка
        "не-адреса/24",
        "2001:db8:::1/64"         // потрійна двокрапка
    })
    @DisplayName("Некоректні та небезпечні значення ForceNetBlock відхиляються")
    void invalidPrefixesRejected(String prefix) {
        assertFalse(DiscoverAggressor.isValidPrefix(prefix));
    }

    @Test
    @DisplayName("safeResolve дозволяє звичайні імена")
    void safeResolveAllowsPlainNames() throws IOException {
        Path base = Path.of("/tmp/store");
        assertEquals(Path.of("/tmp/store/MNTNER-UA.txt"),
                FileUtils.safeResolve(base, "MNTNER-UA.txt"));
    }

    @Test
    @DisplayName("safeResolve блокує вихід за межі директорії")
    void safeResolveBlocksTraversal() {
        Path base = Path.of("/tmp/store");
        assertThrows(IOException.class,
                () -> FileUtils.safeResolve(base, "../../etc/cron.d/job.txt"));
        assertThrows(IOException.class,
                () -> FileUtils.safeResolve(base, "/etc/passwd"));
    }
}
