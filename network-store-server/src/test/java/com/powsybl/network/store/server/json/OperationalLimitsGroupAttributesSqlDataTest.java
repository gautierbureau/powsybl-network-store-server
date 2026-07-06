/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server.json;

import com.powsybl.network.store.model.LimitsAttributes;
import com.powsybl.network.store.model.OperationalLimitsGroupAttributes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Gautier Bureau <gautier.bureau at rte-france.com>
 */
class OperationalLimitsGroupAttributesSqlDataTest {

    @Test
    void eachLimitKindKeepsItsOwnProperties() {
        // give each limit kind distinct properties, so a getter mix-up is detectable
        LimitsAttributes currentLimits = LimitsAttributes.builder()
                .permanentLimit(10.)
                .properties(Map.of("kind", "current"))
                .build();
        LimitsAttributes apparentPowerLimits = LimitsAttributes.builder()
                .permanentLimit(20.)
                .properties(Map.of("kind", "apparent"))
                .build();
        LimitsAttributes activePowerLimits = LimitsAttributes.builder()
                .permanentLimit(30.)
                .properties(Map.of("kind", "active"))
                .build();
        OperationalLimitsGroupAttributes group = OperationalLimitsGroupAttributes.builder()
                .id("group1")
                .currentLimits(currentLimits)
                .apparentPowerLimits(apparentPowerLimits)
                .activePowerLimits(activePowerLimits)
                .build();

        OperationalLimitsGroupAttributesSqlData sqlData = OperationalLimitsGroupAttributesSqlData.of(group);

        assertEquals(Map.of("kind", "current"), sqlData.getCurrentLimitsProperties());
        // apparent power limits properties must come from the apparent power limits, not the active ones
        assertEquals(Map.of("kind", "apparent"), sqlData.getApparentPowerLimitsProperties());
        assertEquals(Map.of("kind", "active"), sqlData.getActivePowerLimitsProperties());
    }
}
