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

package org.apache.shenyu.plugin.ai.provider.profile;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider adaptation rules, separate from per-call credentials.
 *
 * <p>Null scalars and empty maps represent unspecified per-call overrides.
 *
 * @param name profile identity
 * @param defaultEndpoint default upstream base URI
 * @param operationPaths operation to endpoint path mappings
 * @param authentication credential placement policy
 * @param modelMappings model name mappings
 * @param requestFieldMappings request field mappings
 * @param responseFieldMappings response and event field mappings
 * @param requestDefaults provider-specific parameter defaults
 * @param tokenFields declared token field names, or null when unspecified
 * @param usageMode usage timing and accumulation policy
 * @param capabilities supported capabilities
 * @param modelTokenFields token field rules keyed by exact upstream model name after alias resolution
 */
public record AiProviderProfile(
        String name,
        URI defaultEndpoint,
        Map<String, String> operationPaths,
        Authentication authentication,
        Map<String, String> modelMappings,
        Map<String, String> requestFieldMappings,
        Map<String, String> responseFieldMappings,
        JsonNode requestDefaults,
        Set<String> tokenFields,
        UsageMode usageMode,
        AiProviderCapabilities capabilities,
        Map<String, Set<String>> modelTokenFields) {

    public AiProviderProfile(final String name, final URI defaultEndpoint, final Map<String, String> operationPaths,
                             final Authentication authentication, final Map<String, String> modelMappings,
                             final Map<String, String> requestFieldMappings, final Map<String, String> responseFieldMappings,
                             final JsonNode requestDefaults, final Set<String> tokenFields, final UsageMode usageMode,
                             final AiProviderCapabilities capabilities) {
        this(name, defaultEndpoint, operationPaths, authentication, modelMappings, requestFieldMappings,
                responseFieldMappings, requestDefaults, tokenFields, usageMode, capabilities, Map.of());
    }

    public AiProviderProfile {
        operationPaths = Objects.isNull(operationPaths) ? Map.of() : Map.copyOf(operationPaths);
        modelMappings = Objects.isNull(modelMappings) ? Map.of() : Map.copyOf(modelMappings);
        requestFieldMappings = Objects.isNull(requestFieldMappings) ? Map.of() : Map.copyOf(requestFieldMappings);
        responseFieldMappings = Objects.isNull(responseFieldMappings) ? Map.of() : Map.copyOf(responseFieldMappings);
        requestDefaults = Objects.isNull(requestDefaults) ? null : requestDefaults.deepCopy();
        tokenFields = Objects.isNull(tokenFields) ? null : Set.copyOf(tokenFields);
        Map<String, Set<String>> modelRules = new LinkedHashMap<>();
        if (Objects.nonNull(modelTokenFields)) {
            modelTokenFields.forEach((model, fields) -> modelRules.put(model, Set.copyOf(fields)));
        }
        modelTokenFields = Map.copyOf(modelRules);
    }

    /**
     * Get an independent snapshot of the provider parameter defaults.
     *
     * @return owned defaults, or null when unspecified
     */
    @Override
    public JsonNode requestDefaults() {
        return Objects.isNull(requestDefaults) ? null : requestDefaults.deepCopy();
    }

    /**
     * Authentication rule without credentials.
     *
     * @param mode credential placement
     * @param parameterName header or query name
     * @param prefix credential prefix
     */
    public record Authentication(AuthenticationMode mode, String parameterName, String prefix) {
    }

    /**
     * Authentication placement strategies.
     */
    public enum AuthenticationMode {
        BEARER,
        HEADER,
        QUERY,
        NONE
    }

    /**
     * Usage accumulation strategies.
     */
    public enum UsageMode {
        CUMULATIVE,
        INCREMENTAL,
        UNKNOWN
    }
}
