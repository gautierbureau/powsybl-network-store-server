/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A batch of buffered collection changes flushed by a client in a single round trip, applied in
 * entry order by dispatching each entry to the same repository method as the corresponding
 * per-resource-type endpoint.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@Schema(description = "A batch of buffered collection changes applied in a single call")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkUpdateBundle {

    @Schema(description = "Changes to apply, in order")
    private List<BulkUpdateEntry> entries;
}
