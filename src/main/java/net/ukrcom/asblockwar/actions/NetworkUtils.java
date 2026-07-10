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

import java.util.Comparator;

/**
 * Мережеві утиліти: генерація команд blackbgp та компаратори CIDR-адрес.
 * <p>
 * Усі методи є чистими функціями без зовнішніх залежностей.
 */
public class NetworkUtils {

    private NetworkUtils() {}

    /**
     * Формує рядок команди для управління маршрутом у таблиці blackbgp.
     * <p>
     * Для IPv6-prefix додає прапор {@code -6}.
     * Синтаксис: {@code sudo ip [-6] r VERB bl PREFIX t blackbgp}.
     *
     * @param verb дієслово команди: {@code "r"} (replace/add) або {@code "d"} (delete)
     * @param prefix мережевий prefix у нотації CIDR
     * @return готовий рядок команди
     */
    public static String blackbgpCmd(String verb, String prefix) {
        boolean isIpv6 = prefix.contains(":");
        return "sudo ip " + (isIpv6 ? "-6 " : "") + "r " + verb + " bl " + prefix + " t blackbgp";
    }

    // CIDR comparator: IPv4 before IPv6; within each family — prefix length desc, then address asc
    public static final Comparator<String> CIDR_ORDER = (a, b) -> {
        boolean aV6 = a.contains(":");
        boolean bV6 = b.contains(":");
        if (aV6 != bV6) {
            return aV6 ? 1 : -1;
        }
        int la = cidrLen(a), lb = cidrLen(b);
        if (la != lb) {
            return lb - la; // descending: more specific first
        }
        if (!aV6) {
            return Long.compare(ipv4ToLong(cidrAddr(a)), ipv4ToLong(cidrAddr(b)));
        }
        return cidrAddr(a).compareTo(cidrAddr(b));
    };

    /**
     * Повертає довжину маски з CIDR-нотації.
     *
     * @param cidr рядок у нотації CIDR, наприклад {@code "192.168.0.0/24"}
     * @return числова довжина маски або {@code 0}, якщо рядок не містить {@code /}
     */
    public static int cidrLen(String cidr) {
        int i = cidr.lastIndexOf('/');
        try {
            return i >= 0 ? Integer.parseInt(cidr.substring(i + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Повертає адресну частину з CIDR-нотації (без маски).
     *
     * @param cidr рядок у нотації CIDR, наприклад {@code "192.168.0.0/24"}
     * @return адреса, наприклад {@code "192.168.0.0"}
     */
    public static String cidrAddr(String cidr) {
        int i = cidr.lastIndexOf('/');
        return i >= 0 ? cidr.substring(0, i) : cidr;
    }

    /**
     * Перетворює IPv4-адресу у 32-розрядне ціле для числового порівняння.
     *
     * @param addr IPv4-адреса у крапково-десятковому форматі
     * @return числове представлення адреси або {@code 0} при помилці парсингу
     */
    public static long ipv4ToLong(String addr) {
        String[] p = addr.split("\\.", -1);
        if (p.length != 4) {
            return 0;
        }
        try {
            return (Long.parseLong(p[0]) << 24) | (Long.parseLong(p[1]) << 16)
                    | (Long.parseLong(p[2]) << 8) | Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // networks.list order: IPv4 before IPv6; within each family — address asc, then mask asc (1..32)
    public static final Comparator<String> NETWORK_ADDR_ORDER = (a, b) -> {
        boolean aV6 = a.contains(":");
        boolean bV6 = b.contains(":");
        if (aV6 != bV6) {
            return aV6 ? 1 : -1;
        }
        int addrCmp = aV6
                      ? cidrAddr(a).compareTo(cidrAddr(b))
                      : Long.compare(ipv4ToLong(cidrAddr(a)), ipv4ToLong(cidrAddr(b)));
        if (addrCmp != 0) {
            return addrCmp;
        }
        return Integer.compare(cidrLen(a), cidrLen(b));
    };
}
