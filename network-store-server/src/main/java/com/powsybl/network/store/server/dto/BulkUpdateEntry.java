/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.powsybl.network.store.model.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One buffered change of a {@link BulkUpdateBundle}: an operation on the collection of one resource
 * type, whose body is the exact payload the corresponding per-resource-type endpoint would have
 * received (a JSON array of resources for CREATE/UPDATE, a JSON array of ids for REMOVE).
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@Schema(description = "One buffered collection change of a bulk update")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkUpdateEntry {

    @Schema(description = "Resource type the change applies to")
    private ResourceType resourceType;

    @Schema(description = "Operation to apply: CREATE, UPDATE or REMOVE")
    private String operation;

    @Schema(description = "Attribute filter of an UPDATE: null for a full update, SV for a state variable update")
    private String attributeFilter;

    @Schema(description = "CREATE/UPDATE: JSON array of resources; REMOVE: JSON array of ids")
    private JsonNode body;
}
