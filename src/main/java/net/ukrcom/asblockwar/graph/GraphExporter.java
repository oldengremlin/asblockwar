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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Генерує автономний HTML-файл з Canvas-графом залежностей RPSL-об'єктів.
 *
 * <p>Якщо {@code sfdp} (з пакету graphviz) доступний у PATH, розраховує
 * layout заздалегідь і вбудовує координати у JSON: браузер відкриває граф
 * миттєво без D3-симуляції. Якщо sfdp не знайдено або завершився помилкою —
 * прозоро переключається на D3 force-simulation у браузері.
 */
@Slf4j
public class GraphExporter {

    private static final int SFDP_TIMEOUT_SEC = 120;

    private GraphExporter() {}

    /**
     * Генерує HTML і записує у файл.
     *
     * @param graph      побудований граф
     * @param outputPath шлях до вихідного HTML-файлу
     * @throws IOException якщо шаблон не знайдено або файл не вдалося записати
     */
    public static void export(GraphBuilder graph, String outputPath) throws IOException {
        String html = generateHtml(graph);
        Files.writeString(Path.of(outputPath), html, StandardCharsets.UTF_8);
        log.info("Граф залежностей збережено: {}", outputPath);
    }

    /**
     * Генерує повний HTML-рядок з вбудованими даними графа.
     *
     * @param graph побудований граф
     * @return HTML-рядок, готовий для запису у файл або завантаження у WebView
     * @throws IOException якщо шаблон {@code /graph/template.html} не знайдено у classpath
     */
    public static String generateHtml(GraphBuilder graph) throws IOException {
        Map<String, double[]> positions = tryComputeLayout(graph);
        try (InputStream in = GraphExporter.class.getResourceAsStream("/graph/template.html")) {
            if (in == null) {
                throw new IOException("Шаблон /graph/template.html не знайдено у classpath");
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("[[GRAPH_DATA]]", buildJson(graph, positions));
        }
    }

    // ── sfdp layout ────────────────────────────────────────────────────────────

    private static Map<String, double[]> tryComputeLayout(GraphBuilder graph) {
        if (!isSfdpAvailable()) return Map.of();

        Path dotFile = null;
        try {
            dotFile = Files.createTempFile("asblockwar-graph-", ".dot");
            Files.writeString(dotFile, buildDot(graph), StandardCharsets.UTF_8);

            log.info("sfdp: розраховуємо layout для {} вузлів, {} ребер…",
                    graph.getNodes().size(), graph.getEdges().size());

            Process sfdp = new ProcessBuilder(
                    "sfdp", "-Tplain", "-Goverlap=false", dotFile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            byte[][] out = {null};
            Thread reader = Thread.ofVirtual().start(() -> {
                try { out[0] = sfdp.getInputStream().readAllBytes(); }
                catch (IOException ignored) {}
            });

            boolean finished = sfdp.waitFor(SFDP_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                sfdp.destroyForcibly();
                log.warn("sfdp: timeout {}s, переключаємось на D3 симуляцію", SFDP_TIMEOUT_SEC);
                return Map.of();
            }
            reader.join(10_000);

            if (sfdp.exitValue() != 0) {
                log.warn("sfdp: вийшов з кодом {}, переключаємось на D3 симуляцію", sfdp.exitValue());
                return Map.of();
            }

            byte[] raw = out[0];
            Map<String, double[]> pos = parsePlainPositions(
                    raw != null ? new String(raw, StandardCharsets.UTF_8) : "");

            if (pos.size() < graph.getNodes().size() * 0.9) {
                log.warn("sfdp: неповний layout ({}/{}), переключаємось на D3 симуляцію",
                        pos.size(), graph.getNodes().size());
                return Map.of();
            }

            log.info("sfdp: layout готовий, {} позицій", pos.size());
            return pos;

        } catch (Exception e) {
            log.warn("sfdp: помилка — {}, переключаємось на D3 симуляцію", e.getMessage());
            return Map.of();
        } finally {
            if (dotFile != null) {
                try { Files.deleteIfExists(dotFile); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean isSfdpAvailable() {
        try {
            Process p = new ProcessBuilder("sfdp", "-V")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) log.debug("sfdp недоступний або вийшов з помилкою");
            return ok;
        } catch (Exception e) {
            log.debug("sfdp не знайдено: {}", e.getMessage());
            return false;
        }
    }

    private static String buildDot(GraphBuilder graph) {
        int n = graph.getNodes().size(), e = graph.getEdges().size();
        StringBuilder sb = new StringBuilder(n * 20 + e * 40 + 64);
        sb.append("digraph G {\n  graph [overlap=false splines=false];\n");
        for (GraphNode node : graph.getNodes().values()) {
            sb.append("  ").append(dotId(node.id())).append(";\n");
        }
        for (GraphEdge edge : graph.getEdges()) {
            sb.append("  ").append(dotId(edge.source()))
              .append(" -> ").append(dotId(edge.target())).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String dotId(String id) {
        return '"' + id.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Парсить вивід {@code sfdp -Tplain} і повертає map id → [x, y] у Canvas-координатах.
     * Graphviz використовує систему з Y-вгору (одиниці — дюйми × 72),
     * тому Y інвертується для відповідності Canvas (Y-вниз).
     */
    private static Map<String, double[]> parsePlainPositions(String plain) {
        Map<String, double[]> pos = new HashMap<>();
        for (String line : plain.split("\n")) {
            if (!line.startsWith("node ")) continue;
            String rest = line.substring(5).trim();

            String name;
            String remaining;
            if (rest.startsWith("\"")) {
                // quoted name: find closing quote, skipping escaped ones
                int end = 1;
                while (end < rest.length()) {
                    if (rest.charAt(end) == '"' && rest.charAt(end - 1) != '\\') break;
                    end++;
                }
                if (end >= rest.length()) continue;
                name = rest.substring(1, end).replace("\\\"", "\"").replace("\\\\", "\\");
                remaining = rest.substring(end + 1).trim();
            } else {
                int sp = rest.indexOf(' ');
                if (sp < 0) continue;
                name = rest.substring(0, sp);
                remaining = rest.substring(sp + 1).trim();
            }

            String[] parts = remaining.split("\\s+");
            if (parts.length < 2) continue;
            try {
                double x =  Double.parseDouble(parts[0]) * 72.0;
                double y = -Double.parseDouble(parts[1]) * 72.0; // invert Y
                pos.put(name, new double[]{x, y});
            } catch (NumberFormatException ignored) {}
        }
        return pos;
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    private static String buildJson(GraphBuilder graph, Map<String, double[]> positions) {
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("{\"nodes\":[");
        boolean first = true;
        for (GraphNode n : graph.getNodes().values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(jsonStr(n.id()))
              .append(",\"type\":").append(jsonStr(n.type().name()))
              .append(",\"status\":").append(jsonStr(n.status().name()))
              .append(",\"label\":").append(jsonStr(n.label()))
              .append(",\"details\":").append(jsonStr(n.details()));
            double[] xy = positions.get(n.id());
            if (xy != null) {
                sb.append(",\"px\":").append(formatCoord(xy[0]))
                  .append(",\"py\":").append(formatCoord(xy[1]));
            }
            sb.append('}');
        }

        sb.append("],\"links\":[");
        first = true;
        for (GraphEdge e : graph.getEdges()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"source\":").append(jsonStr(e.source()))
              .append(",\"target\":").append(jsonStr(e.target()))
              .append(",\"relation\":").append(jsonStr(e.relation().name()))
              .append('}');
        }

        long blocked    = graph.count(NodeStatus.BLOCKED);
        long suspicious = graph.count(NodeStatus.SUSPICIOUS);
        long clear      = graph.count(NodeStatus.CLEAR);
        long unknown    = graph.count(NodeStatus.UNKNOWN);

        sb.append("],\"stats\":{")
          .append("\"blocked\":").append(blocked)
          .append(",\"suspicious\":").append(suspicious)
          .append(",\"clear\":").append(clear)
          .append(",\"unknown\":").append(unknown)
          .append(",\"total\":").append(graph.getNodes().size())
          .append(",\"edges\":").append(graph.getEdges().size())
          .append(",\"preLayout\":").append(!positions.isEmpty())
          .append("}}");

        return sb.toString();
    }

    private static String formatCoord(double v) {
        long rounded = Math.round(v);
        return Long.toString(rounded);
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
