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

package org.apache.shenyu.plugin.ai.provider.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model, defaults, token and provider-specific request field adaptation.
 *
 * <p>Explicit configured token limits override client values; otherwise raw token fields are retained.
 *
 * <p>Provider field differences belong in profiles. Vendors requiring different semantics may
 * supply a Provider SPI implementation rather than subclassing this concrete mapper.
 * Configuration resolution, HTTP execution and retry orchestration remain outside this mapper.
 */
public final class AiProviderRequestMapper {

    /**
     * Map the effective request while preserving unmapped fields.
     *
     * <p>Streaming uses the non-null original payload, then configuration as a default, then a
     * profile default, then the normalized boolean. A null payload value is treated as missing.
     *
     * @param request shared normalized request
     * @param config per-call overrides
     * @param profile provider mapping rules
     * @return the effective upstream request
     */
    public MappedRequest map(final ShenyuAiRequest request, final AiProviderConfig config, final AiProviderProfile profile) {
        if (Objects.isNull(request.payload()) || !request.payload().isObject()) {
            throw new IllegalArgumentException("AI request payload must be a JSON object");
        }
        final ObjectNode payload = request.payload().deepCopy();
        final String model = resolveEffectiveModel(request, config, profile);
        if (Objects.nonNull(model)) {
            payload.put("model", model);
        }
        // Remove a missing client stream before defaults; configuration remains a default, not an override.
        if (!payload.hasNonNull("stream")) {
            payload.remove("stream");
        }
        applyDefaults(payload, config, profile);
        if (!request.payload().hasNonNull("stream") && Objects.nonNull(config.getStream())) {
            payload.put("stream", config.getStream());
        } else if (!payload.hasNonNull("stream")) {
            payload.put("stream", request.stream());
        }
        mapTokenFields(payload, config.getMaxTokens(), profile);
        mapFields(payload, profile);
        final JsonNode effectiveModel = payload.get(profile.requestFieldMappings().getOrDefault("model", "model"));
        final JsonNode effectiveStream = payload.get(profile.requestFieldMappings().getOrDefault("stream", "stream"));
        return new MappedRequest(request.operation(), Objects.isNull(effectiveModel) || effectiveModel.isNull() ? null : effectiveModel.asText(),
                Objects.nonNull(effectiveStream) && effectiveStream.asBoolean(), payload);
    }

    /**
     * Resolve the model used by both request mapping and model-specific token selection.
     *
     * <p>Configuration, normalized request, non-null payload and profile defaults are consulted
     * in that order. Aliases are applied once to the selected value.
     *
     * @param request normalized request
     * @param config resolved configuration
     * @param profile model mappings
     * @return the resolved value
     */
    public String resolveEffectiveModel(final ShenyuAiRequest request, final AiProviderConfig config, final AiProviderProfile profile) {
        String model = Objects.isNull(config.getModel()) ? request.model() : config.getModel();
        if (Objects.isNull(model) && Objects.nonNull(request.payload()) && request.payload().hasNonNull("model")) {
            model = request.payload().get("model").asText();
        }
        final JsonNode defaults = profile.requestDefaults();
        if (Objects.isNull(model) && Objects.nonNull(defaults) && defaults.hasNonNull("model")) {
            model = defaults.get("model").asText();
        }
        return Objects.isNull(model) ? null : profile.modelMappings().getOrDefault(model, model);
    }

    /**
     * Apply defaults and per-call parameter overrides to an owned payload.
     *
     * <p>Declared and canonical token fields are reserved for explicit token configuration.
     *
     * @param payload owned payload copy
     * @param config per-call overrides
     * @param profile provider defaults
     * @return the adapted payload
     */
    private JsonNode applyDefaults(final JsonNode payload, final AiProviderConfig config, final AiProviderProfile profile) {
        final ObjectNode object = (ObjectNode) payload;
        final JsonNode defaults = profile.requestDefaults();
        if (Objects.nonNull(defaults)) {
            defaults.fields().forEachRemaining(entry -> {
                if (!isTokenField(entry.getKey(), profile) && !object.has(entry.getKey())) {
                    object.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
        }
        if (Objects.nonNull(config.getTemperature())) {
            object.put("temperature", config.getTemperature());
        }
        return object;
    }

    /**
     * Apply an explicit configured token limit to the profile's declared token fields.
     *
     * <p>Without configured tokens or a token field declaration, raw fields remain unchanged. Shared normalized tokens do not
     * participate in mapping. With configured tokens, undeclared canonical token fields are removed;
     * other vendor fields remain unchanged.
     *
     * @param payload owned payload with original client token fields
     * @param configuredTokens per-call configured token value
     * @param profile declared provider token fields
     * @return the adapted payload
     */
    private JsonNode mapTokenFields(final JsonNode payload, final Long configuredTokens, final AiProviderProfile profile) {
        if (Objects.isNull(configuredTokens) || Objects.isNull(profile.tokenFields())) {
            return payload;
        }
        final long value = configuredTokens;
        final ObjectNode object = (ObjectNode) payload;
        if (!profile.tokenFields().contains("max_tokens")) {
            object.remove("max_tokens");
        }
        if (!profile.tokenFields().contains("max_completion_tokens")) {
            object.remove("max_completion_tokens");
        }
        profile.tokenFields().forEach(field -> object.put(field, value));
        return object;
    }

    /**
     * Apply provider field mappings to an owned payload.
     *
     * <p>Token fields are excluded from general source and target renaming. All sources are read
     * before any writes, so mappings rename original values once rather than recursively chaining.
     * A mapped source value replaces any existing value at its target field.
     *
     * @param payload owned payload copy
     * @param profile provider field rules
     * @return the adapted payload
     */
    private JsonNode mapFields(final JsonNode payload, final AiProviderProfile profile) {
        final ObjectNode object = (ObjectNode) payload;
        final Map<String, JsonNode> sources = new LinkedHashMap<>();
        profile.requestFieldMappings().forEach((field, mappedField) -> {
            if (!isTokenField(field, profile) && !isTokenField(mappedField, profile) && object.has(field)) {
                sources.put(field, object.get(field));
            }
        });
        for (Map.Entry<String, String> mapping : profile.requestFieldMappings().entrySet()) {
            if (sources.containsKey(mapping.getKey()) && !mapping.getKey().equals(mapping.getValue())) {
                object.remove(mapping.getKey());
            }
        }
        for (Map.Entry<String, String> mapping : profile.requestFieldMappings().entrySet()) {
            if (sources.containsKey(mapping.getKey())) {
                object.set(mapping.getValue(), sources.get(mapping.getKey()));
            }
        }
        return object;
    }

    private boolean isTokenField(final String name, final AiProviderProfile profile) {
        return "max_tokens".equals(name) || "max_completion_tokens".equals(name)
                || Objects.nonNull(profile.tokenFields()) && profile.tokenFields().contains(name);
    }

    /**
     * Effective request used for capability validation.
     *
     * @param operation effective operation
     * @param model effective model
     * @param stream effective stream option
     * @param payload mapped upstream JSON
     */
    public record MappedRequest(String operation, String model, boolean stream, JsonNode payload) {
    }
}
