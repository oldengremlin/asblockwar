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

/**
 * Незмінний запис, що описує вузол у графі залежностей RPSL-об'єктів.
 *
 * @param id      унікальний ідентифікатор (наприклад, {@code "AS12345"}, {@code "MNTNER-UA"})
 * @param type    тип RPSL-об'єкта
 * @param status  статус у контексті блокування
 * @param label   коротка мітка для відображення (напр. as-name)
 * @param details деталі для tooltip (country, org-name, matched line тощо)
 */
public record GraphNode(
        String id,
        NodeType type,
        NodeStatus status,
        String label,
        String details
) {}
