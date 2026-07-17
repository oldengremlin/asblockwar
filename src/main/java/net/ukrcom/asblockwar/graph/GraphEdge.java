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
 * Незмінний запис, що описує направлений зв'язок між двома вузлами графу.
 * Рівність визначається комбінацією (source, target, relation) — ребра дедуплікуються.
 *
 * @param source   ідентифікатор вузла-джерела
 * @param target   ідентифікатор вузла-призначення
 * @param relation тип зв'язку
 */
public record GraphEdge(
        String source,
        String target,
        EdgeRelation relation
) {}
