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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Тести впорядкування CIDR-префіксів.
 * <p>
 * Порядок визначає вигляд {@code war.blackbgp.txt}, {@code networks.list}
 * та email-звіту.
 */
class NetworkUtilsTest {

    /** Еталон: порівняння через {@link InetAddress}, повільне, але завідомо правильне. */
    private static int referenceCompare(String a, String b) {
        String addrA = NetworkUtils.cidrAddr(a);
        String addrB = NetworkUtils.cidrAddr(b);
        boolean v6a = addrA.contains(":");
        boolean v6b = addrB.contains(":");
        if (v6a != v6b) {
            return v6a ? 1 : -1;
        }
        try {
            return Arrays.compareUnsigned(
                    InetAddress.getByName(addrA).getAddress(),
                    InetAddress.getByName(addrB).getAddress());
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    @Test
    @DisplayName("IPv6 впорядковуються числово, а не лексикографічно")
    void ipv6SortsNumerically() {
        // 2a2:: це 0x02a2, тобто МЕНШЕ за 2001:: (0x2001) і за 2a14:: —
        // лексикографічно ж '2' > '0', через що порядок був хибним
        List<String> actual = new ArrayList<>(List.of(
                "2a14:e080::/32", "2001:db8::/32", "2a2:ffff::/32", "2a03:5840::/32", "2a3:1::/32"));
        actual.sort(NetworkUtils.NETWORK_ADDR_ORDER);

        assertEquals(List.of(
                "2a2:ffff::/32", "2a3:1::/32", "2001:db8::/32", "2a03:5840::/32", "2a14:e080::/32"),
                actual);
    }

    @Test
    @DisplayName("IPv4 йдуть перед IPv6")
    void ipv4BeforeIpv6() {
        List<String> actual = new ArrayList<>(List.of("2001:db8::/32", "10.0.0.0/8", "::1/128"));
        actual.sort(NetworkUtils.NETWORK_ADDR_ORDER);
        assertEquals(List.of("10.0.0.0/8", "::1/128", "2001:db8::/32"), actual);
    }

    @Test
    @DisplayName("Швидкий парсер збігається з InetAddress на випадкових наборах")
    void matchesReferenceImplementation() {
        Random rnd = new Random(20260806L);
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            prefixes.add(String.format("%x:%x::/%d",
                    rnd.nextInt(0x10000), rnd.nextInt(0x10000), 32 + rnd.nextInt(32)));
        }
        for (int i = 0; i < 150; i++) {
            prefixes.add(String.format("%d.%d.%d.0/%d",
                    rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 8 + rnd.nextInt(17)));
        }

        List<String> viaComparator = new ArrayList<>(prefixes);
        viaComparator.sort(NetworkUtils.NETWORK_ADDR_ORDER);

        List<String> viaReference = new ArrayList<>(prefixes);
        viaReference.sort((a, b) -> {
            int c = referenceCompare(a, b);
            return c != 0 ? c : Integer.compare(NetworkUtils.cidrLen(a), NetworkUtils.cidrLen(b));
        });

        assertEquals(viaReference, viaComparator);
    }

    @Test
    @DisplayName("CIDR_ORDER ставить найспецифічніші маски першими")
    void cidrOrderPutsMoreSpecificFirst() {
        List<String> actual = new ArrayList<>(List.of("10.0.0.0/8", "10.1.0.0/16", "10.1.1.0/24"));
        actual.sort(NetworkUtils.CIDR_ORDER);
        assertEquals(List.of("10.1.1.0/24", "10.1.0.0/16", "10.0.0.0/8"), actual);
    }
}
