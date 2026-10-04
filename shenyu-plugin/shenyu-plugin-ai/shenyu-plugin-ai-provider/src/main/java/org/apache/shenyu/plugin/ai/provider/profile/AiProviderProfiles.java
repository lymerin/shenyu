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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Initial profile definitions and composition point.
 *
 * <p>Only documented model capabilities are declared; unlisted models remain unknown.
 */
public final class AiProviderProfiles {

    /**
     * Provisional OpenAI wire protocol identifier.
     */
    public static final String OPENAI_PROTOCOL = "openai-chat";

    /**
     * Chat completion operation identifier.
     */
    public static final String CHAT_COMPLETIONS = "chat-completions";

    private AiProviderProfiles() {
    }

    /**
     * Define the OpenAI profile.
     *
     * @return the effective provider profile
     */
    public static AiProviderProfile openAi() {
        return create("openai", URI.create("https://api.openai.com/v1"), Set.of("max_completion_tokens"),
                Map.of("gpt-4.1", new AiProviderCapabilities.ModelCapabilities(AiProviderCapabilities.Support.SUPPORTED,
                                AiProviderCapabilities.Support.SUPPORTED, AiProviderCapabilities.Support.SUPPORTED),
                        "o1-mini", new AiProviderCapabilities.ModelCapabilities(AiProviderCapabilities.Support.SUPPORTED,
                                AiProviderCapabilities.Support.UNSUPPORTED, AiProviderCapabilities.Support.UNKNOWN)),
                Map.of("gpt-3.5-turbo", Set.of("max_tokens"), "gpt-3.5-turbo-0125", Set.of("max_tokens")));
    }

    /**
     * Define the generic compatible profile.
     *
     * @return the effective provider profile
     */
    public static AiProviderProfile openAiCompatible() {
        return create("openai-compatible", null);
    }

    /**
     * Define the DeepSeek profile.
     *
     * @return the effective provider profile
     */
    public static AiProviderProfile deepSeek() {
        final AiProviderCapabilities.ModelCapabilities supported = new AiProviderCapabilities.ModelCapabilities(
                AiProviderCapabilities.Support.SUPPORTED, AiProviderCapabilities.Support.SUPPORTED, AiProviderCapabilities.Support.SUPPORTED);
        return create("deepseek", URI.create("https://api.deepseek.com"), Set.of("max_tokens"),
                Map.of("deepseek-flash", supported, "deepseek-v4-pro", supported), Map.of());
    }

    /**
     * Combine an owned profile with per-call profile overrides.
     *
     * @param base base profile
     * @param override per-call overrides
     * @return the effective provider profile
     */
    public static AiProviderProfile merge(final AiProviderProfile base, final AiProviderProfile override) {
        if (Objects.isNull(override)) {
            return base;
        }
        return new AiProviderProfile(select(base.name(), override.name()), select(base.defaultEndpoint(), override.defaultEndpoint()),
                mergeMaps(base.operationPaths(), override.operationPaths()), select(base.authentication(), override.authentication()),
                mergeMaps(base.modelMappings(), override.modelMappings()), mergeMaps(base.requestFieldMappings(), override.requestFieldMappings()),
                mergeMaps(base.responseFieldMappings(), override.responseFieldMappings()), mergeDefaults(base.requestDefaults(), override.requestDefaults()),
                select(base.tokenFields(), override.tokenFields()), select(base.usageMode(), override.usageMode()),
                select(base.capabilities(), override.capabilities()), mergeMaps(base.modelTokenFields(), override.modelTokenFields()));
    }

    /**
     * Find a named profile or an identity-only override for an undefined profile.
     *
     * <p>Defined profiles contain capability declarations, including UNKNOWN declarations.
     * Identity-only overrides have null capabilities and inherit the implementation defaults.
     *
     * @param profileName selected profile identity
     * @return named rules or a sparse identity inheriting the implementation defaults
     */
    public static AiProviderProfile getProfile(final String profileName) {
        if ("openai".equalsIgnoreCase(profileName)) {
            return openAi();
        }
        if ("deepseek".equalsIgnoreCase(profileName)) {
            return deepSeek();
        }
        if (Objects.isNull(profileName) || "openai-compatible".equalsIgnoreCase(profileName)) {
            return openAiCompatible();
        }
        return new AiProviderProfile(profileName, null, null, null, null, null, null, null, null, null, null);
    }

    private static AiProviderProfile create(final String name, final URI endpoint) {
        return create(name, endpoint, Set.of("max_tokens"), Map.of(), Map.of());
    }

    private static AiProviderProfile create(final String name, final URI endpoint, final Set<String> tokenFields,
                                           final Map<String, AiProviderCapabilities.ModelCapabilities> models, final Map<String, Set<String>> modelTokenFields) {
        return new AiProviderProfile(name, endpoint, Map.of(CHAT_COMPLETIONS, "/chat/completions", "chat.completions", "/chat/completions"),
                new AiProviderProfile.Authentication(AiProviderProfile.AuthenticationMode.BEARER, "Authorization", "Bearer "),
                Map.of(), Map.of(), Map.of(), JsonNodeFactory.instance.objectNode(), tokenFields, AiProviderProfile.UsageMode.CUMULATIVE,
                new AiProviderCapabilities(Set.of(OPENAI_PROTOCOL, "openai"), Set.of(CHAT_COMPLETIONS, "chat.completions"), models), modelTokenFields);
    }

    private static <T> T select(final T base, final T override) {
        return Objects.isNull(override) ? base : override;
    }

    private static <T> Map<String, T> mergeMaps(final Map<String, T> base, final Map<String, T> override) {
        Map<String, T> result = new LinkedHashMap<>(base);
        result.putAll(override);
        return result;
    }

    private static JsonNode mergeDefaults(final JsonNode base, final JsonNode override) {
        if (Objects.isNull(override)) {
            return base;
        }
        if (base instanceof ObjectNode && override instanceof ObjectNode) {
            ObjectNode result = base.deepCopy();
            override.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue().deepCopy()));
            return result;
        }
        return override;
    }
}
