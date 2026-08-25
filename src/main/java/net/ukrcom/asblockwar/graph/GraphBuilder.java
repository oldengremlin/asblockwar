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
package net.ukrcom.asblockwar.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import net.ukrcom.asblockwar.serviceStructures.SuspiciousAS;

/**
 * Будує граф залежностей RPSL-об'єктів з результатів обробки ASBlockWar.
 *
 * <p>Вузли і ребра збираються з фінальних карт обробки (blocked, suspicious, cleared),
 * а також зі списків mntner і as-set. Зв'язки витягуються парсингом RPSL-блоків.
 * Peer-ребра (import/export AS) включаються лише між вузлами, що вже є в графі.
 * RIPE-* мантейнери виключаються (надто шумні — присутні в кожному об'єкті).
 */
@Slf4j
public class GraphBuilder {

    private static final Pattern AS_NAME_PAT = Pattern.compile("(?m)^as-name:[ \\t]*(.+)$");
    private static final Pattern ORG_ID_PAT = Pattern.compile("(?m)^org:[ \\t]*(\\S+)");
    private static final Pattern ORG_NAME_PAT = Pattern.compile("(?m)^org-name:[ \\t]*(.+)$");
    private static final Pattern MNT_BY_PAT = Pattern.compile("(?m)^mnt-by:[ \\t]*(\\S+)");
    private static final Pattern MNT_REF_PAT = Pattern.compile("(?m)^mnt-ref:[ \\t]*(\\S+)");
    private static final Pattern PEER_ASN_PAT = Pattern.compile("(?i)\\b(?:from|to)\\s+(AS\\d+)");
    private static final Pattern COUNTRY_PAT = Pattern.compile("(?m)^country:[ \\t]*([A-Z]{2,3})");
    private static final Pattern DESCR_PAT = Pattern.compile("(?m)^descr:[ \\t]*(.+)$");
    private static final Pattern SERVICE_MNT = Pattern.compile("^RIPE-.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_OF_PAT = Pattern.compile("(?m)^member-of:[ \\t]*(\\S+)");
    /** Початок поля members:/mp-members:. */
    private static final Pattern MEMBERS_LINE = Pattern.compile("(?i)^(?:mp-)?members:.*");
    /** Continuation-рядок RFC 2622 — починається з пробілу або табуляції. */
    private static final Pattern MEMBERS_CONT = Pattern.compile("^[ \\t]+\\S.*");
    private static final Pattern MNTNER_AUTNUM_PAT = Pattern.compile("(?m)^aut-num:[ \\t]*(AS\\d+)");
    private static final Pattern MNTNER_ASSET_PAT = Pattern.compile("(?m)^as-set:[ \\t]*(\\S+)");

    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final Set<GraphEdge> edges = ConcurrentHashMap.newKeySet();

    private GraphBuilder() {
    }

    /**
     * Будує граф з фінальних карт обробки ASBlockWar.
     *
     * @param blocked   заблоковані ASN → RPSL-блок
     * @param suspicious підозрілі ASN → SuspiciousAS
     * @param cleared   видалені ASN → ASN-запис (містить RPSL у data())
     * @param allMntBy  всі відомі mntner з list.mnt-by.txt
     * @param allAsSets всі відомі as-set з list.as-set.txt
     * @return заповнений граф
     */
    public static GraphBuilder build(
            Map<String, String> blocked,
            Map<String, SuspiciousAS> suspicious,
            Map<String, ASN> cleared,
            Map<String, String> allMntBy,
            Map<String, String> allAsSets,
            Map<String, String> memberAsns,
            boolean dependencyWithUnknown) {

        GraphBuilder g = new GraphBuilder();

        // Чотири CPU-важкі потоки (regex × RPSL-блок), незалежні між собою.
        // ConcurrentHashMap/newKeySet thread-safe, Matcher локальний → parallelStream безпечний.
        blocked.entrySet().parallelStream().forEach(e -> {
            g.addNode(e.getKey(), NodeType.ASN, NodeStatus.BLOCKED,
                    extractAsnLabel(e.getValue()), extractAsnDetails(e.getKey(), e.getValue()));
            g.parseRpslEdges(e.getKey(), e.getValue());
        });

        suspicious.entrySet().parallelStream().forEach(e -> {
            g.addNode(e.getKey(), NodeType.ASN, NodeStatus.SUSPICIOUS,
                    e.getKey(), "country: " + e.getValue().country() + "\n" + e.getValue().matchedLine());
            String rpsl = e.getValue().rpsl();
            if (rpsl != null && !rpsl.isBlank()) {
                g.parseRpslEdges(e.getKey(), rpsl);
            }
        });

        cleared.entrySet().parallelStream().forEach(e -> {
            String rpsl = e.getValue().data() != null ? e.getValue().data() : "";
            g.addNode(e.getKey(), NodeType.ASN, NodeStatus.CLEAR,
                    extractAsnLabel(rpsl), extractAsnDetails(e.getKey(), rpsl));
            if (!rpsl.isBlank()) {
                g.parseRpslEdges(e.getKey(), rpsl);
            }
        });

        allAsSets.entrySet().parallelStream().forEach(e -> {
            g.addNode(e.getKey(), NodeType.AS_SET, NodeStatus.UNKNOWN, e.getKey(), "");
            if (!e.getValue().isBlank()) {
                g.parseAsSetEdges(e.getKey(), e.getValue());
            }
        });

        // allMntBy — додаємо вузли; sequential достатньо (немає regex)
        allMntBy.keySet().forEach(mnt -> g.addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, ""));

        // ASN-члени AS-SET-ів, яких немає у blocked/suspicious/cleared — додаємо як UNKNOWN
        memberAsns.entrySet().parallelStream().forEach(e -> {
            g.addNode(e.getKey(), NodeType.ASN, NodeStatus.UNKNOWN,
                    extractAsnLabel(e.getValue()), extractAsnDetails(e.getKey(), e.getValue()));
            g.parseRpslEdges(e.getKey(), e.getValue());
        });

        // Ребра з mntner RPSL (reverse-lookup: aut-num та as-set, що мають mnt-by цього mntner).
        // Запускаємо ПІСЛЯ всіх ASN-вузлів, щоб фільтр nodes::containsKey був актуальним.
        allMntBy.entrySet().parallelStream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .forEach(e -> g.parseMntnerEdges(e.getKey(), e.getValue()));

        // Ребра де хоча б один кінець не є відомим вузлом:
        // PEER — щоб не породжувати фантомні ASN-вузли;
        // MEMBER_OF — для ASN-членів з as-set.members, що не входять до жодної карти
        g.edges.removeIf(e -> (e.relation() == EdgeRelation.PEER || e.relation() == EdgeRelation.MEMBER_OF)
                && (!g.nodes.containsKey(e.source()) || !g.nodes.containsKey(e.target())));

        // Поширюємо статус з вузлів ASN на суміжні не-ASN вузли
        // (mntner, org, as-set) через структурні ребра (не PEER).
        // Повний ланцюжок: BLOCKED > SUSPICIOUS > CLEAR > UNKNOWN.
        // ASN-члени AS-SET-ів (з memberAsns) мають статус UNKNOWN — він не перезаписує вищі.
        g.edges.parallelStream()
                .filter(e -> e.relation() != EdgeRelation.PEER)
                .forEach(e -> {
                    GraphNode src = g.nodes.get(e.source());
                    if (src == null || src.type() != NodeType.ASN) {
                        return;
                    }
                    g.nodes.computeIfPresent(e.target(), (id, current) -> {
                        if (current.type() == NodeType.ASN) {
                            return current;
                        }
                        if (src.status().priority() > current.status().priority()) {
                            return new GraphNode(id, current.type(), src.status(),
                                    current.label(), current.details());
                        }
                        return current;
                    });
                });

        log.info("Граф побудовано: {} вузлів, {} ребер", g.nodes.size(), g.edges.size());

        // Якщо DependencyWithUnknown=false — видаляємо вузли зі статусом UNKNOWN і ребра до них
        if (!dependencyWithUnknown) {
            g.nodes.values().removeIf(n -> n.status() == NodeStatus.UNKNOWN);
            g.edges.removeIf(e -> !g.nodes.containsKey(e.source()) || !g.nodes.containsKey(e.target()));
            log.info("Граф без Unknown: {} вузлів, {} ребер", g.nodes.size(), g.edges.size());
        }

        return g;
    }

    public Map<String, GraphNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public Set<GraphEdge> getEdges() {
        return Collections.unmodifiableSet(edges);
    }

    public long count(NodeStatus status) {
        return nodes.values().stream().filter(n -> n.status() == status).count();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------
    private void addNode(String id, NodeType type, NodeStatus status, String label, String details) {
        if (id == null || id.isBlank()) {
            return;
        }
        // RPSL-ідентифікатори регістронезалежні — нормалізуємо до верхнього регістру
        String normId = id.trim().toUpperCase();
        nodes.merge(normId, new GraphNode(normId, type, status, label, details),
                (existing, incoming) -> {
                    NodeStatus better = existing.status().priority() >= incoming.status().priority()
                                        ? existing.status() : incoming.status();
                    String betterLabel = existing.label().isBlank() ? incoming.label() : existing.label();
                    String betterDetails = existing.details().isBlank() ? incoming.details() : existing.details();
                    return new GraphNode(normId, existing.type(), better, betterLabel, betterDetails);
                });
    }

    private void addEdge(String source, String target, EdgeRelation relation) {
        if (source == null || target == null) {
            return;
        }
        String normSrc = source.trim().toUpperCase();
        String normTgt = target.trim().toUpperCase();
        if (normSrc.equals(normTgt)) {
            return;
        }
        edges.add(new GraphEdge(normSrc, normTgt, relation));
    }

    private void parseRpslEdges(String asn, String rpsl) {
        allMatches(MNT_BY_PAT, rpsl).forEach(mnt -> {
            if (!SERVICE_MNT.matcher(mnt).matches()) {
                addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, "");
                addEdge(asn, mnt, EdgeRelation.MNT_BY);
            }
        });

        allMatches(MNT_REF_PAT, rpsl).forEach(mnt -> {
            if (!SERVICE_MNT.matcher(mnt).matches()) {
                addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, "");
                addEdge(asn, mnt, EdgeRelation.MNT_REF);
            }
        });

        // Назва організації однакова для всіх збігів у блоці — рахуємо один раз,
        // а не повним скануванням rpsl на кожен org:
        String orgName = extractOrgName(rpsl);
        allMatches(ORG_ID_PAT, rpsl).forEach(org -> {
            addNode(org, NodeType.ORGANISATION, NodeStatus.UNKNOWN, org,
                    orgName.isBlank() ? "" : orgName);
            addEdge(asn, org, EdgeRelation.ORG);
        });

        allMatches(MEMBER_OF_PAT, rpsl).forEach(set -> {
            if (!set.isBlank()) {
                addNode(set, NodeType.AS_SET, NodeStatus.UNKNOWN, set, "");
                addEdge(asn, set, EdgeRelation.MEMBER_OF);
            }
        });

        // Peer-ребра — лише з рядків import/export. Раніше патерн шукав «from|to AS\\d+»
        // по ВСЬОМУ блоку, тож «remarks: migrated from AS12345» або
        // «descr: transit to AS3356» малювали peering, якого в політиці немає.
        allMatches(PEER_ASN_PAT, String.join("\n", policyLines(rpsl))).stream()
                .map(String::toUpperCase)
                .filter(peer -> !peer.equals(asn))
                .forEach(peer -> addEdge(asn, peer, EdgeRelation.PEER));
    }


    /**
     * Збирає значення полів {@code members:}/{@code mp-members:} разом із
     * continuation-рядками.
     * <p>
     * За RFC 2622 великі as-set записуються з переносом:
     * <pre>
     * members:        AS1, AS2,
     *                 AS3, AS-CHILD
     * </pre>
     * Регулярний вираз, прив'язаний до {@code ^members:}, бачив лише перший рядок,
     * тож граф систематично недораховував членів найбільших as-set, а вкладені
     * as-set не потрапляли до нього взагалі. {@code retrieveAsSetMembers}
     * обробляє continuation саме так — тут була розбіжність.
     *
     * @param rpsl RPSL-блок as-set
     * @return значення полів members без імені поля, по рядку на запис
     */

    /** Поля політики маршрутизації, у яких лише й мають шукатися peer-ASN. */
    private static final Pattern POLICY_LINE
            = Pattern.compile("(?i)^(?:mp-)?(?:import|export|default):.*");

    /**
     * Збирає рядки {@code import:}/{@code export:} (та їхні mp-варіанти) разом
     * із continuation-рядками RFC 2622.
     *
     * @param rpsl RPSL-блок aut-num
     * @return рядки політики маршрутизації
     */
    private static List<String> policyLines(String rpsl) {
        List<String> out = new ArrayList<>();
        boolean inPolicy = false;
        for (String line : rpsl.split("\n")) {
            if (POLICY_LINE.matcher(line).matches()) {
                inPolicy = true;
                out.add(line);
            } else if (inPolicy && MEMBERS_CONT.matcher(line).matches()) {
                out.add(line);
            } else {
                inPolicy = false;
            }
        }
        return out;
    }

    static List<String> memberLines(String rpsl) {
        List<String> out = new ArrayList<>();
        boolean inMembers = false;
        for (String line : rpsl.split("\n")) {
            if (MEMBERS_LINE.matcher(line).matches()) {
                inMembers = true;
                out.add(line.replaceFirst("(?i)^(?:mp-)?members:", "").trim());
            } else if (inMembers && MEMBERS_CONT.matcher(line).matches()) {
                out.add(line.trim());
            } else {
                inMembers = false;
            }
        }
        return out;
    }

    private void parseAsSetEdges(String asSetId, String rpsl) {
        memberLines(rpsl).forEach(line -> {
            for (String token : line.split("[,\\s]+")) {
                String member = token.trim().replaceAll(";$", "");
                if (member.isEmpty()) {
                    continue;
                }
                String memberUp = member.toUpperCase();
                if (memberUp.matches("AS\\d+")) {
                    // ASN-член: ребро ASN → AS-SET (буде відфільтровано якщо ASN не в графі)
                    addEdge(memberUp, asSetId, EdgeRelation.MEMBER_OF);
                } else if (memberUp.startsWith("AS-") || memberUp.startsWith("RS-") || memberUp.startsWith("FLTR-")) {
                    // AS-SET або filter-set: завжди додаємо вузол і ребро
                    addNode(memberUp, NodeType.AS_SET, NodeStatus.UNKNOWN, memberUp, "");
                    addEdge(memberUp, asSetId, EdgeRelation.MEMBER_OF);
                }
            }
        });

        // mnt-by та mnt-ref з RPSL AS-SET (аналогічно до parseRpslEdges для ASN)
        allMatches(MNT_BY_PAT, rpsl).forEach(mnt -> {
            if (!SERVICE_MNT.matcher(mnt).matches()) {
                addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, "");
                addEdge(asSetId, mnt, EdgeRelation.MNT_BY);
            }
        });
        allMatches(MNT_REF_PAT, rpsl).forEach(mnt -> {
            if (!SERVICE_MNT.matcher(mnt).matches()) {
                addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, "");
                addEdge(asSetId, mnt, EdgeRelation.MNT_REF);
            }
        });
    }

    /**
     * Парсить mntner RPSL (reverse-lookup: результат -rmb) для полів aut-num та as-set.
     * Створює ребра від об'єктів до mntner. Для ASN — лише якщо вузол вже є у графі.
     */
    private void parseMntnerEdges(String mntnerId, String rpsl) {
        allMatches(MNTNER_AUTNUM_PAT, rpsl).stream()
                .map(String::toUpperCase)
                .filter(nodes::containsKey)
                .forEach(asn -> addEdge(asn, mntnerId, EdgeRelation.MNT_BY));

        allMatches(MNTNER_ASSET_PAT, rpsl).forEach(asSet -> {
            String asSetUp = asSet.toUpperCase();
            addNode(asSetUp, NodeType.AS_SET, NodeStatus.UNKNOWN, asSetUp, "");
            addEdge(asSetUp, mntnerId, EdgeRelation.MNT_BY);
        });
    }

    private static java.util.List<String> allMatches(Pattern p, String text) {
        java.util.List<String> result = new java.util.ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            result.add(m.group(1).trim());
        }
        return result;
    }

    private static String extractAsnLabel(String rpsl) {
        Matcher m = AS_NAME_PAT.matcher(rpsl);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extractOrgName(String rpsl) {
        Matcher m = ORG_NAME_PAT.matcher(rpsl);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extractAsnDetails(String asn, String rpsl) {
        StringBuilder sb = new StringBuilder(asn);
        Matcher c = COUNTRY_PAT.matcher(rpsl);
        if (c.find()) {
            sb.append("\ncountry: ").append(c.group(1));
        }
        Matcher o = ORG_NAME_PAT.matcher(rpsl);
        if (o.find()) {
            sb.append("\norg: ").append(o.group(1).trim());
        }
        Matcher d = DESCR_PAT.matcher(rpsl);
        if (d.find()) {
            sb.append("\n").append(d.group(1).trim());
        }
        return sb.toString();
    }
}
