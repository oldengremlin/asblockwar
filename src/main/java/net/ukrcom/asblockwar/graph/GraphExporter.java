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
import lombok.extern.slf4j.Slf4j;

/**
 * Генерує автономний HTML-файл з D3.js-графом залежностей RPSL-об'єктів.
 *
 * <p>Завантажує шаблон {@code /graph/template.html} з classpath, замінює
 * плейсхолдер {@code [[GRAPH_DATA]]} JSON-рядком зі вмістом графа та зберігає
 * результат до файлу або повертає як рядок для відображення у JavaFX WebView.
 */
@Slf4j
public class GraphExporter {

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
        try (InputStream in = GraphExporter.class.getResourceAsStream("/graph/template.html")) {
            if (in == null) {
                throw new IOException("Шаблон /graph/template.html не знайдено у classpath");
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("[[GRAPH_DATA]]", buildJson(graph));
        }
    }

    // -----------------------------------------------------------------------
    // JSON serialization (no external library — keeps fat-JAR lean)
    // -----------------------------------------------------------------------

    private static String buildJson(GraphBuilder graph) {
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
              .append(",\"details\":").append(jsonStr(n.details()))
              .append('}');
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
        long total      = graph.getNodes().size();
        long edges      = graph.getEdges().size();

        sb.append("],\"stats\":{")
          .append("\"blocked\":").append(blocked)
          .append(",\"suspicious\":").append(suspicious)
          .append(",\"clear\":").append(clear)
          .append(",\"unknown\":").append(unknown)
          .append(",\"total\":").append(total)
          .append(",\"edges\":").append(edges)
          .append("}}");

        return sb.toString();
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
