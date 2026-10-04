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
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiFinishReason;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiResponse;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiTokenUsage;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decoded response, usage and finish reason adaptation.
 *
 * <p>Unknown provider fields remain available in copied payloads.
 *
 * <p>Provider field differences belong in profiles. Vendors requiring different semantics may
 * supply a Provider SPI implementation rather than subclassing this concrete mapper.
 * Configuration resolution, HTTP execution and retry orchestration remain outside this mapper.
 */
public final class AiProviderResponseMapper {

    private final AiProviderErrorMapper errorMapper;

    /**
     * Create the mapper.
     *
     * @param errorMapper error normalization
     */
    public AiProviderResponseMapper(final AiProviderErrorMapper errorMapper) {
        this.errorMapper = errorMapper;
    }

    /**
     * Map decoded upstream JSON and metadata to the shared response.
     *
     * @param statusCode upstream status
     * @param headers upstream response headers
     * @param payload decoded response JSON
     * @param profile provider response rules
     * @return the normalized response
     */
    public ShenyuAiResponse map(final int statusCode, final Map<String, List<String>> headers, final JsonNode payload, final AiProviderProfile profile) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        if (Objects.nonNull(headers)) {
            headers.forEach((name, values) -> snapshot.put(name, List.copyOf(values)));
        }
        JsonNode error = field(payload, "error", profile);
        return new ShenyuAiResponse(statusCode, Collections.unmodifiableMap(snapshot), text(field(payload, "model", profile)),
                mapUsage(payload, profile), mapFinishReason(payload, profile),
                statusCode >= 400 || Objects.nonNull(error) && !error.isNull() ? errorMapper.map(statusCode, payload, profile) : null,
                Objects.isNull(payload) ? null : payload.deepCopy());
    }

    /**
     * Normalize usage fields.
     *
     * @param payload decoded response or event
     * @param profile usage rules
     * @return the normalized token usage
     */
    public ShenyuAiTokenUsage mapUsage(final JsonNode payload, final AiProviderProfile profile) {
        JsonNode usage = field(payload, "usage", profile);
        if (Objects.isNull(usage) || usage.isNull()) {
            return null;
        }
        return new ShenyuAiTokenUsage(number(field(usage, "prompt_tokens", profile)),
                number(field(usage, "completion_tokens", profile)), number(field(usage, "total_tokens", profile)));
    }

    /**
     * Normalize finish reason while retaining the provider value.
     *
     * @param payload decoded response or event
     * @param profile finish reason rules
     * @return the normalized finish reason
     */
    public ShenyuAiFinishReason mapFinishReason(final JsonNode payload, final AiProviderProfile profile) {
        JsonNode reason = field(payload, "finish_reason", profile);
        JsonNode choices = field(payload, "choices", profile);
        if ((Objects.isNull(reason) || reason.isNull()) && Objects.nonNull(choices) && choices.isArray()) {
            for (JsonNode choice : choices) {
                reason = field(choice, "finish_reason", profile);
                if (Objects.nonNull(reason) && !reason.isNull()) {
                    break;
                }
            }
        }
        String raw = text(reason);
        if (Objects.isNull(raw)) {
            return null;
        }
        String normalized = "tool_calls".equals(raw) || "function_call".equals(raw) ? ShenyuAiFinishReason.TOOL_CALL : raw;
        return new ShenyuAiFinishReason(normalized, raw);
    }

    /**
     * Read a provider field using a simple canonical name mapping.
     *
     * @param payload containing object
     * @param name canonical field name
     * @param profile provider mappings
     * @return field or null when absent
     */
    public JsonNode field(final JsonNode payload, final String name, final AiProviderProfile profile) {
        String mapped = Objects.isNull(profile) || Objects.isNull(profile.responseFieldMappings())
                ? name : profile.responseFieldMappings().getOrDefault(name, name);
        return Objects.isNull(payload) ? null : payload.get(mapped);
    }

    private String text(final JsonNode node) {
        return Objects.isNull(node) || node.isNull() || !node.isValueNode() ? null : node.asText();
    }

    private Long number(final JsonNode node) {
        return Objects.nonNull(node) && node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
    }
}
