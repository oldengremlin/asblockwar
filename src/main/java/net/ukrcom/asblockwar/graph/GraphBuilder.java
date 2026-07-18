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

import java.util.Collections;
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

    private static final Pattern AS_NAME_PAT = Pattern.compile("(?m)^as-name:\\s*(.+)$");
    private static final Pattern ORG_ID_PAT = Pattern.compile("(?m)^org:\\s*(\\S+)");
    private static final Pattern ORG_NAME_PAT = Pattern.compile("(?m)^org-name:\\s*(.+)$");
    private static final Pattern MNT_BY_PAT = Pattern.compile("(?m)^mnt-by:\\s*(\\S+)");
    private static final Pattern MNT_REF_PAT = Pattern.compile("(?m)^mnt-ref:\\s*(\\S+)");
    private static final Pattern PEER_ASN_PAT = Pattern.compile("(?i)\\b(?:from|to)\\s+(AS\\d+)");
    private static final Pattern COUNTRY_PAT = Pattern.compile("(?m)^country:\\s*([A-Z]{2,3})");
    private static final Pattern DESCR_PAT = Pattern.compile("(?m)^descr:\\s*(.+)$");
    private static final Pattern SERVICE_MNT = Pattern.compile("^RIPE-.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_OF_PAT = Pattern.compile("(?m)^member-of:\\s*(\\S+)");
    private static final Pattern MEMBERS_PAT = Pattern.compile("(?m)^members:\\s*(.+)$");

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
            Set<String> allMntBy,
            Map<String, String> allAsSets) {

        GraphBuilder g = new GraphBuilder();

        // blocked і cleared — CPU-важкі (regex × RPSL-блок), незалежні між собою,
        // ConcurrentHashMap thread-safe → parallelStream масштабується до кількості ядер
        blocked.entrySet().parallelStream().forEach(e -> {
            g.addNode(e.getKey(), NodeType.ASN, NodeStatus.BLOCKED,
                    extractAsnLabel(e.getValue()), extractAsnDetails(e.getKey(), e.getValue()));
            g.parseRpslEdges(e.getKey(), e.getValue());
        });

        suspicious.forEach((asn, sus) -> {
            g.addNode(asn, NodeType.ASN, NodeStatus.SUSPICIOUS,
                    asn, "country: " + sus.country() + "\n" + sus.matchedLine());
            if (sus.rpsl() != null && !sus.rpsl().isBlank()) {
                g.parseRpslEdges(asn, sus.rpsl());
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

        allMntBy.forEach(mnt -> g.addNode(mnt, NodeType.MNTNER, NodeStatus.UNKNOWN, mnt, ""));
        allAsSets.forEach((as, rpsl) -> {
            g.addNode(as, NodeType.AS_SET, NodeStatus.UNKNOWN, as, "");
            if (!rpsl.isBlank()) {
                g.parseAsSetEdges(as, rpsl);
            }
        });

        // Ребра де хоча б один кінець не є відомим вузлом:
        // PEER — щоб не породжувати фантомні ASN-вузли;
        // MEMBER_OF — для ASN-членів з as-set.members, що не входять до жодної карти
        g.edges.removeIf(e -> (e.relation() == EdgeRelation.PEER || e.relation() == EdgeRelation.MEMBER_OF)
                && (!g.nodes.containsKey(e.source()) || !g.nodes.containsKey(e.target())));

        // Поширюємо статус з вузлів ASN на суміжні не-ASN вузли
        // (mntner, org, as-set) через структурні ребра (не PEER).
        // Повний ланцюжок: BLOCKED > SUSPICIOUS > CLEAR > UNKNOWN.
        // ASN-вузлів зі статусом UNKNOWN не існує → effectivly лише три статуси.
        g.edges.stream()
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
        nodes.merge(id.trim(), new GraphNode(id.trim(), type, status, label, details),
                (existing, incoming) -> {
                    NodeStatus better = existing.status().priority() >= incoming.status().priority()
                                        ? existing.status() : incoming.status();
                    String betterLabel = existing.label().isBlank() ? incoming.label() : existing.label();
                    String betterDetails = existing.details().isBlank() ? incoming.details() : existing.details();
                    return new GraphNode(id.trim(), existing.type(), better, betterLabel, betterDetails);
                });
    }

    private void addEdge(String source, String target, EdgeRelation relation) {
        if (source == null || target == null || source.equals(target)) {
            return;
        }
        edges.add(new GraphEdge(source.trim(), target.trim(), relation));
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

        allMatches(ORG_ID_PAT, rpsl).forEach(org -> {
            String orgName = extractOrgName(rpsl);
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

        // Peer-ребра — додаємо умовно, будуть відфільтровані якщо target не в графі
        allMatches(PEER_ASN_PAT, rpsl).stream()
                .map(String::toUpperCase)
                .filter(peer -> !peer.equals(asn))
                .forEach(peer -> addEdge(asn, peer, EdgeRelation.PEER));
    }

    private void parseAsSetEdges(String asSetId, String rpsl) {
        allMatches(MEMBERS_PAT, rpsl).forEach(line -> {
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
