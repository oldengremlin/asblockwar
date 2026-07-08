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
 * <p>Для довгих списків {@link #buildWars(int)} ділить результат на частини
 * WAR1, WAR2, … так, щоб кожна не перевищувала {@code maxWarLen} символів.
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
        return "(" + String.join("|", alts) + ")";
    }

    /**
     * Рекурсивно збирає "сегменти" — атомарні частини regex, кожна не більша
     * за {@code maxSegLen}. Якщо піддерево занадто велике, воно розкривається
     * в дочірні підсегменти до тих пір, поки кожен не вкладеться в ліміт.
     */
    private static void collectSegments(String prefix, TrieNode node, int maxSegLen, List<String> out) {
        String full = prefix + toRegex(node);
        if (full.length() <= maxSegLen || node.children.isEmpty()) {
            if (!full.isEmpty()) {
                out.add(full);
            }
            return;
        }
        if (node.isEnd) {
            out.add(prefix);
        }
        for (var e : node.children.entrySet()) {
            collectSegments(prefix + e.getKey(), e.getValue(), maxSegLen, out);
        }
    }

    /**
     * Повертає список WAR-рядків (оптимізованих регексів), кожен не довший
     * за {@code maxWarLen} символів. Кожен рядок готовий для підстановки у
     * {@code set policy-options as-path WARn "_ <рядок> _"}.
     */
    public List<String> buildWars(int maxWarLen) {
        int segMax = Math.min(2048, Math.max(64, maxWarLen / 4));

        List<String> segments = new ArrayList<>();
        collectSegments("", root, segMax, segments);

        List<String> wars = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            if (sb.isEmpty()) {
                sb.append(seg);
            } else if (sb.length() + 1 + seg.length() <= maxWarLen) {
                sb.append('|').append(seg);
            } else {
                wars.add(sb.toString());
                sb = new StringBuilder(seg);
            }
        }
        if (!sb.isEmpty()) {
            wars.add(sb.toString());
        }
        return wars;
    }
}
