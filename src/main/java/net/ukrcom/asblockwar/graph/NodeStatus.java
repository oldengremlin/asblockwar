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
 * Статус вузла в графі залежностей.
 * Пріоритет визначає, яке значення зберігається при злитті дублікатів.
 */
public enum NodeStatus {
    /** AS не ідентифікована або є допоміжним об'єктом (mntner, org) */
    UNKNOWN(1),
    /** AS перевірена — не ворожа, не підозріла */
    CLEAR(2),
    /** AS збігається з AggressorPattern, але country не в BlockCountry */
    SUSPICIOUS(3),
    /** AS заблокована: country входить до BlockCountry */
    BLOCKED(4);

    private final int priority;

    NodeStatus(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
