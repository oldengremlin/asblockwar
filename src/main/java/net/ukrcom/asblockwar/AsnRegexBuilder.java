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
package net.ukrcom.asblockwar;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Будує trie з ASN-чисел і серіалізує його в оптимізований регулярний вираз.
 *
 * <p>Приклад: {@code 219407|219413|219445|219470|219529}
 * стискається до {@code 219(4(07|13|45|70)|529)}.
 *
 * <p>Juniper реалізує DFA, тому довжина regex і кількість альтернатив
 * не впливають на швидкість обробки.
 *
 * @author olden
 */
public class AsnRegexBuilder {

    private static final class TrieNode {
        final TreeMap<Character, TrieNode> children = new TreeMap<>();
        boolean isEnd = false;
    }

    private final TrieNode root = new TrieNode();

    public void add(long asn) {
        TrieNode node = root;
        for (char c : Long.toString(asn).toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.isEnd = true;
    }

    /**
     * Рекурсивно серіалізує піддерево trie у regex-рядок.
     * Вузли з одним варіантом не отримують дужок — спільний префікс
     * просто конкатенується.
     */
    private static String toRegex(TrieNode node) {
        List<String> alts = new ArrayList<>();
        if (node.isEnd) {
            alts.add("");
        }
        for (var e : node.children.entrySet()) {
            alts.add(e.getKey() + toRegex(e.getValue()));
        }
        if (alts.size() == 1) {
            return alts.get(0);
        }
        // Juniper rejects empty alternatives like (|x|y) — use (x|y)? instead
        if (node.isEnd) {
            List<String> childAlts = alts.subList(1, alts.size());
            String suffix = childAlts.size() == 1
                    ? childAlts.get(0)
                    : "(" + String.join("|", childAlts) + ")";
            return suffix + "?";
        }
        return "(" + String.join("|", alts) + ")";
    }

    /**
     * Повертає повний trie-оптимізований regex для всіх доданих ASN.
     */
    public String build() {
        return toRegex(root);
    }
}
