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
 * через SSH: {@code ssh <host> sudo ip r l t blackbgp}.
 *
 * @author olden
 */
public class retrieveBlackbgpPrefixes {

    private static final Pattern CIDR4 = Pattern.compile(
            "\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2})\\b");
    private static final Pattern CIDR6 = Pattern.compile(
            "\\b([0-9a-fA-F]*:[0-9a-fA-F:]+/\\d{1,3})\\b");

    private final Set<String> prefixes = new HashSet<>();

    public retrieveBlackbgpPrefixes(String host, boolean includeIpv6) {
        fetch(host, false);
        if (includeIpv6) {
            fetch(host, true);
        }
    }

    private void fetch(String host, boolean ipv6) {
        String remoteCmd = ipv6 ? "sudo ip -6 r l t blackbgp" : "sudo ip r l t blackbgp";
        Pattern pattern = ipv6 ? CIDR6 : CIDR4;
        try {
            Process proc = new ProcessBuilder("ssh", host, remoteCmd)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                reader.lines().forEach(line -> {
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        prefixes.add(m.group(1));
                    }
                });
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                net.ukrcom.asblockwar.ASBlockWar.LOGGER
                        .warn("retrieveBlackbgpPrefixes: ssh {} '{}' завершився з кодом {}",
                                host, remoteCmd, exit);
            } else {
                net.ukrcom.asblockwar.ASBlockWar.LOGGER
                        .debug("retrieveBlackbgpPrefixes: {} прочитано {} prefixes ({})",
                                host, prefixes.size(), remoteCmd);
            }
        } catch (IOException | InterruptedException e) {
            net.ukrcom.asblockwar.ASBlockWar.LOGGER
                    .error("retrieveBlackbgpPrefixes: помилка SSH {} '{}'", host, remoteCmd, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Set<String> get() {
        return Collections.unmodifiableSet(prefixes);
    }
}
