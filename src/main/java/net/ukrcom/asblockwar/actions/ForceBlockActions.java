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
package net.ukrcom.asblockwar.actions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.retrieveretrieve.retrieveOrganisation;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;

/**
 * Примусове блокування AS, що обходить country + AGGRESSOR_PATTERN фільтри.
 * <p>
 * ASN зі списку {@code ForceASBlock} додаються безпосередньо до
 * {@code aggressorAsnResources} — їхні префікси потраплять у WAR (Juniper)
 * і до цільового набору blackbgp.
 */
@Slf4j
public class ForceBlockActions {

    private ForceBlockActions() {}

    /**
     * Додає всі ASN зі списку {@code ForceASBlock} до карти ворожих ресурсів,
     * оминаючи перевірки country та AGGRESSOR_PATTERN.
     *
     * @param aggressorAsnResources карта {@code ASN → RPSL-блок}; модифікується на місці
     */
    public static void applyForceAsBlock(Map<String, String> aggressorAsnResources) {
        List<String> forceList = ASBlockWar.config.getForceAsBlock();
        if (forceList.isEmpty()) {
            return;
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore dbLimit = new Semaphore(ASBlockWar.MAX_CONCURRENT_DB_QUERIES);
            forceList.forEach(raw -> executor.submit(() -> {
                String asn = raw.trim().toUpperCase();
                if (!asn.startsWith("AS")) {
                    asn = "AS" + asn;
                }
                if (aggressorAsnResources.containsKey(asn)) {
                    log.debug("applyForceAsBlock: {} вже у списку, пропускаємо", asn);
                    return;
                }
                try {
                    dbLimit.acquire();
                    String block;
                    try {
                        block = new retrieveOrganisation(asn).get();
                    } finally {
                        dbLimit.release();
                    }
                    aggressorAsnResources.put(asn, block);
                    ASBlockWar.resourcesForVerification.put(asn, new ASN(Action.add, asn, block));
                    log.warn("applyForceAsBlock: {} примусово додано до списку блокування", asn);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
    }
}
