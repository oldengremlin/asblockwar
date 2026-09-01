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

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Країни {@code inetnum}/{@code inet6num}-об'єктів, що покривають заданий префікс.
 * <p>
 * Потрібно, бо RPSL <b>не несе country на самому {@code route:}</b> — країна
 * резолвиться лише через {@code origin:} → {@code aut-num}/{@code organisation}.
 * Це дає обхід: мережу переоформлюють під ASN легітимного хостера з «чистої»
 * країни (створюють точніший {@code route:} з його {@code origin:}), а фактичний
 * власник лишається в окремому ланцюжку {@code inetnum:} → {@code org:} →
 * {@code organisation:}, якого перевірка за origin ніколи не торкається.
 * Формально за RPSL усе чесно, а по суті блокування пробите наскрізь.
 * <p>
 * Перевіряються <b>обидва</b> джерела країни: поле {@code country:} самого
 * inetnum і країна організації з його {@code org:}. У спостереженому випадку
 * ({@code 5.231.231.0/24}) inetnum заявляв {@code country: FI}, а організація,
 * на яку він посилався — {@code country: RU}.
 * <p>
 * Пошук повторює підхід whois-lite-local (після
 * «return the most specific covering object, and drop masklen from rpsl_net»):
 * кожен рядок {@code rpsl_net} — це CIDR-блок, тож обидві його межі виводяться
 * з адреси й довжини префікса. Довжини перебираються від найточнішої до нуля,
 * і на кожній робиться точний збіг за {@code (version, key, firstip, lastip)} —
 * щонайбільше 33 (або 129) звернень до індексу, із зупинкою на першому влучанні.
 * Діапазонний предикат тут вироджувався б у зворотний скан індексу аж до рядка
 * {@code 0.0.0.0/0}, а стовпця {@code masklen} у схемі більше немає: довжина
 * маски належить окремому CIDR-блоку, а не об'єкту — діапазон на кшталт
 * {@code 20.33.0.0 - 20.128.255.255} розкладається на сім блоків шести різних
 * розмірів.
 * <p>
 * Береться <b>лише найточніший</b> покривний об'єкт, як і відповідає whois:
 * RIPE тримає inetnum-заглушку на весь адресний простір, тож «усі покривні»
 * означало б домішувати країну заглушки до кожного префікса. Якщо адресу
 * покриває лише ця заглушка — мережа нікому не призначена, і результат порожній.
 */
@Slf4j
public class retrieveInetnumCountry {

    /** Ширина десяткового подання адреси у стовпцях firstip/lastip (whois-lite-local). */
    private static final int IP_DECIMAL_WIDTH = 40;

    private static final RpslCache cache = RpslCache.create();

    private final String prefix;
    private final List<String> countries = new ArrayList<>();

    /**
     * @param prefix CIDR-префікс маршруту, наприклад {@code "5.231.231.0/24"}
     */
    public retrieveInetnumCountry(String prefix) {
        this.prefix = prefix;

        String cached = cache.get(prefix);
        if (cached != null) {
            if (!cached.isEmpty()) {
                Collections.addAll(this.countries, cached.split(","));
            }
            return;
        }

        try (Connection conn = RpslDb.open()) {
            collect(conn);
            cache.put(prefix, String.join(",", this.countries));
        } catch (SQLException ex) {
            log.error("Помилка при отриманні inetnum для {}", prefix, ex);
        }
    }

    /**
     * @return коди країн найточнішого покривного об'єкта у верхньому регістрі —
     *         спершу його власний {@code country:}, далі країни організацій
     *         з його {@code org:}; порожній список, якщо покривних об'єктів немає
     */
    public List<String> get() {
        return List.copyOf(countries);
    }

    private void collect(Connection conn) throws SQLException {
        int slash = prefix.indexOf('/');
        String host = slash < 0 ? prefix : prefix.substring(0, slash);

        BigInteger address;
        int bits;
        try {
            byte[] raw = InetAddress.getByName(host).getAddress();
            address = new BigInteger(1, raw);
            bits = raw.length * 8;
        } catch (UnknownHostException | SecurityException e) {
            log.debug("retrieveInetnumCountry: не вдалося розібрати «{}»", prefix);
            return;
        }
        int version = bits == 32 ? 4 : 6;
        String key = version == 4 ? "inetnum" : "inet6num";

        List<String> values = findMostSpecific(conn, address, bits, version, key);

        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String block = RpslDb.fetchBlocks(conn, value, key);
            if (block.isEmpty()) {
                continue;
            }
            addCountries(block, seen);
            // Країна організації, на яку посилається inetnum: у спостереженому
            // випадку саме тут була RU, тоді як сам inetnum заявляв FI
            for (String org : fieldValues(block, "org:")) {
                addCountries(RpslDb.fetchBlocks(conn, org, "organisation"), seen);
            }
        }
        countries.addAll(seen);
    }

    private static void addCountries(String block, Set<String> target) {
        for (String value : fieldValues(block, "country:")) {
            target.add(value.toUpperCase());
        }
    }

    private static List<String> fieldValues(String block, String field) {
        List<String> out = new ArrayList<>();
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, field, 0, field.length())) {
                String value = trimmed.substring(field.length()).trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /**
     * Знаходить найточніший покривний {@code inetnum}/{@code inet6num}.
     *
     * @return значення знайдених об'єктів або порожній список, якщо покривних
     *         немає чи адресу покриває лише заглушка на весь адресний простір
     */
    private List<String> findMostSpecific(Connection conn, BigInteger address, int bits,
            int version, String key) throws SQLException {
        List<String> values = new ArrayList<>();
        String sql = "SELECT value FROM rpsl_net"
                + " WHERE version = ? AND key = ? AND firstip = ? AND lastip = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.setString(2, key);
            for (int masklen = bits; masklen >= 0; masklen--) {
                BigInteger network = networkAddress(address, bits, masklen);
                BigInteger last = network.add(BigInteger.ONE.shiftLeft(bits - masklen))
                        .subtract(BigInteger.ONE);
                stmt.setString(3, padIpDecimal(network));
                stmt.setString(4, padIpDecimal(last));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        values.add(rs.getString("value"));
                    }
                }
                if (!values.isEmpty()) {
                    // Заглушка на весь адресний простір не свідчить ні про що:
                    // вона покриває будь-яку невидану нікому адресу
                    if (masklen == 0) {
                        log.debug("retrieveInetnumCountry: {} покриває лише заглушка {} — "
                                + "мережа нікому не призначена", prefix, values);
                        return List.of();
                    }
                    return values;
                }
            }
        }
        return values;
    }

    /** Маскує адресу до {@code maskLength} біт. */
    private static BigInteger networkAddress(BigInteger address, int bits, int maskLength) {
        int hostBits = bits - maskLength;
        return hostBits <= 0 ? address : address.shiftRight(hostBits).shiftLeft(hostBits);
    }

    /** Доповнює десяткове подання нулями зліва — так TEXT-порівняння дає числовий порядок. */
    private static String padIpDecimal(BigInteger value) {
        String decimal = value.toString();
        int pad = IP_DECIMAL_WIDTH - decimal.length();
        return pad > 0 ? "0".repeat(pad) + decimal : decimal;
    }
}
