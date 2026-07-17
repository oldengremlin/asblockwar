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
 * Тип зв'язку між вузлами в графі залежностей RPSL-об'єктів.
 */
public enum EdgeRelation {
    /** ASN або org обслуговується мантейнером (поле mnt-by:) */
    MNT_BY,
    /** Організація дозволяє мантейнеру посилатися на неї (поле mnt-ref:) */
    MNT_REF,
    /** ASN належить організації (поле org:) */
    ORG,
    /** ASN входить до AS-SET (поле members:) */
    MEMBER_OF,
    /** AS має import/export-зв'язок з іншою AS */
    PEER
}
