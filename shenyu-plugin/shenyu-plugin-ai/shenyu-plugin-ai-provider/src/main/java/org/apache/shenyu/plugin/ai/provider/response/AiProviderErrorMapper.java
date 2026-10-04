/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.ai.provider.response;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiError;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.Objects;

/**
 * Provider error normalization; execution policy stays in the caller.
 *
 * <p>Unknown provider fields remain available in copied payloads.
 */
public final class AiProviderErrorMapper {

    /**
     * Map provider errors without deciding retry or fallback.
     *
     * @param statusCode upstream status
     * @param payload decoded provider error JSON
     * @param profile provider error rules
     * @return the normalized error
     */
    public ShenyuAiError map(final int statusCode, final JsonNode payload, final AiProviderProfile profile) {
        JsonNode error = Objects.isNull(payload) ? null : payload.get(field("error", profile));
        String type = value(error, field("type", profile));
        String message = value(error, field("message", profile));
        if (Objects.isNull(message)) {
            message = Objects.nonNull(payload) && payload.isTextual() ? payload.textValue() : "Upstream HTTP error " + statusCode;
        }
        return new ShenyuAiError(Objects.isNull(type) ? "upstream_error" : type, message, value(error, field("code", profile)),
                statusCode == 429 || statusCode >= 500 && statusCode <= 599, Objects.isNull(payload) ? null : payload.deepCopy());
    }

    private String field(final String name, final AiProviderProfile profile) {
        return Objects.isNull(profile) || Objects.isNull(profile.responseFieldMappings())
                ? name : profile.responseFieldMappings().getOrDefault(name, name);
    }

    private String value(final JsonNode error, final String name) {
        JsonNode value = Objects.isNull(error) ? null : error.get(name);
        return Objects.isNull(value) || value.isNull() || !value.isValueNode() ? null : value.asText();
    }
}
