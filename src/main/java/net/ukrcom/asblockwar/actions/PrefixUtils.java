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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Канонізація CIDR-префіксів для порівняння наборів маршрутів.
 * <p>
 * Поточні маршрути читаються з роутера, цільові — з RPSL-бази, і та сама мережа
 * може бути записана по-різному: {@code 2001:db8:0::/48} проти {@code 2001:db8::/48},
 * {@code 2001:DB8::/32} проти {@code 2001:db8::/32}. При порівнянні сирих рядків
 * такий префікс щоразу потрапляв одночасно до {@code toDelete} і {@code toReplace} —
 * маршрут знімався й одразу ставився назад, і так на кожному прогоні.
 */
@Slf4j
public final class PrefixUtils {

    private PrefixUtils() {
    }

    /**
     * Зводить префікс до єдиної форми запису.
     * <p>
     * Для IPv6 повертає повністю розгорнуту адресу ({@code 2001:db8:0:0:0:0:0:0/32}) —
     * це внутрішній ключ порівняння, а не те, що йде в команду роутера.
     *
     * @param prefix CIDR-префікс
     * @return канонічна форма, або вихідний рядок (у нижньому регістрі), якщо
     *         адресу не вдалося розібрати
     */
    public static String canonical(String prefix) {
        int slash = prefix.indexOf('/');
        if (slash < 0) {
            return prefix.toLowerCase();
        }
        String addr = prefix.substring(0, slash);
        // Літеральна адреса — DNS не задіюється; на не-літералі впаде у except
        if (!addr.matches("[0-9A-Fa-f:.]+")) {
            return prefix.toLowerCase();
        }
        try {
            InetAddress ia = InetAddress.getByName(addr);
            String host = ia.getHostAddress();
            if (ia instanceof Inet6Address) {
                // getHostAddress() може додати зону («%eth0») — вона тут зайва
                int pct = host.indexOf('%');
                if (pct >= 0) {
                    host = host.substring(0, pct);
                }
            }
            return host.toLowerCase() + prefix.substring(slash);
        } catch (UnknownHostException e) {
            log.debug("PrefixUtils: не вдалося канонізувати «{}»", prefix);
            return prefix.toLowerCase();
        }
    }

    /**
     * Будує відображення {@code канонічна форма → вихідний рядок}.
     * <p>
     * Вихідну форму треба зберегти: у команди роутера має йти саме той запис,
     * який очікує відповідна сторона, а не внутрішній ключ порівняння.
     *
     * @param prefixes набір префіксів
     * @return відображення з передбачуваним порядком обходу
     */
    public static Map<String, String> byCanonical(Set<String> prefixes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String p : prefixes) {
            map.putIfAbsent(canonical(p), p);
        }
        return map;
    }
}
