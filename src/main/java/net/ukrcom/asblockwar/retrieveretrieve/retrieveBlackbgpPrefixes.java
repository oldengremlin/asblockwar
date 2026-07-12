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
package net.ukrcom.asblockwar.retrieveretrieve;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Читає поточний перелік prefixes з таблиці маршрутизації blackbgp
 * командою з конфігурації {@code GetBlackhole} / {@code GetBlackholeIpv6}.
 *
 * @author olden
 */
@Slf4j
public class retrieveBlackbgpPrefixes {

    private static final Pattern CIDR4 = Pattern.compile(
            "\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2})\\b");
    private static final Pattern CIDR6 = Pattern.compile(
            "\\b([0-9a-fA-F]*:[0-9a-fA-F:]+/\\d{1,3})\\b");
    // ip route list не виводить /32 (/128) для хостових маршрутів
    private static final Pattern HOST4 = Pattern.compile(
            "\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b");
    private static final Pattern HOST6 = Pattern.compile(
            "([0-9a-fA-F]*:[0-9a-fA-F:]+[0-9a-fA-F])");

    private final Set<String> prefixes = new HashSet<>();

    /**
     * Виконує зовнішні команди з конфігурації ({@code GetBlackhole} та, опційно,
     * {@code GetBlackholeIpv6}) і збирає поточний перелік prefixes з blackbgp.
     *
     * @param includeIpv6 якщо {@code true} — також виконується IPv6-команда
     *                    і зібрані IPv6-префікси додаються до результату
     */
    public retrieveBlackbgpPrefixes(boolean includeIpv6) {
        fetch(net.ukrcom.asblockwar.ASBlockWar.config.getGetBlackhole(), false);
        if (includeIpv6) {
            fetch(net.ukrcom.asblockwar.ASBlockWar.config.getGetBlackholeIpv6(), true);
        }
    }

    private void fetch(String command, boolean ipv6) {
        Pattern pattern = ipv6 ? CIDR6 : CIDR4;
        try {
            Process proc = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                reader.lines().forEach(line -> {
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        prefixes.add(m.group(1));
                    } else if (!ipv6) {
                        Matcher mh = HOST4.matcher(line);
                        if (mh.find()) {
                            prefixes.add(mh.group(1) + "/32");
                        }
                    } else {
                        Matcher mh = HOST6.matcher(line);
                        if (mh.find()) {
                            prefixes.add(mh.group(1) + "/128");
                        }
                    }
                });
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                log.warn("retrieveBlackbgpPrefixes: '{}' завершився з кодом {}", command, exit);
            } else {
                log.debug("retrieveBlackbgpPrefixes: прочитано {} prefixes ({})", prefixes.size(), command);
            }
        } catch (IOException | InterruptedException e) {
            log.error("retrieveBlackbgpPrefixes: помилка '{}': {}", command, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Повертає незмінну множину CIDR-префіксів, наявних у таблиці маршрутизації blackbgp.
     *
     * @return множина CIDR-рядків (IPv4 і/або IPv6 залежно від параметра конструктора)
     */
    public Set<String> get() {
        return Collections.unmodifiableSet(prefixes);
    }
}
