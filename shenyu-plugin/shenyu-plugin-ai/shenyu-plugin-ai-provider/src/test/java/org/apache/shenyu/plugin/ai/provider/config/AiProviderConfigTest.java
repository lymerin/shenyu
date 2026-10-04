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

package org.apache.shenyu.plugin.ai.provider.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderCapabilities;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import org.apache.shenyu.plugin.ai.provider.registry.AiProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration log descriptions must not expose credential-bearing values.
 */
class AiProviderConfigTest {

    @Test
    void snapshotsCallerOwnedValues() {
        List<String> values = new ArrayList<>(List.of("original", "second"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-First", List.of("first"));
        headers.put("X-Test", values);
        ObjectNode defaults = JsonNodeFactory.instance.objectNode().put("seed", 1);
        Map<String, String> mappings = new HashMap<>(Map.of("model", "mapped-model"));
        final Set<String> tokenFields = new HashSet<>(Set.of("max_tokens"));
        final Map<String, Set<String>> modelRules = new HashMap<>(Map.of("mapped-model", tokenFields));
        AiProviderProfile profile = new AiProviderProfile("custom", null, null, null, mappings, null, null, defaults, tokenFields, null, null, modelRules);
        final AiProviderConfig config = new AiProviderConfig(null, null, null, headers, headers, null, null, null, null, profile);
        values.add("changed");
        headers.clear();
        mappings.clear();
        tokenFields.clear();
        modelRules.clear();
        defaults.put("seed", 2);
        ((ObjectNode) profile.requestDefaults()).put("seed", 3);
        assertEquals(List.of("original", "second"), config.getHeaders().get("X-Test"));
        assertEquals(List.of("original", "second"), config.getQueryParameters().get("X-Test"));
        assertEquals(List.of("X-First", "X-Test"), new ArrayList<>(config.getHeaders().keySet()));
        assertEquals(List.of("X-First", "X-Test"), new ArrayList<>(config.getQueryParameters().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> config.getHeaders().get("X-Test").add("value"));
        assertEquals("mapped-model", profile.modelMappings().get("model"));
        assertEquals(1, profile.requestDefaults().path("seed").intValue());
        assertEquals(Set.of("max_tokens"), profile.tokenFields());
        assertThrows(UnsupportedOperationException.class, () -> profile.tokenFields().clear());
        assertEquals(Set.of("max_tokens"), profile.modelTokenFields().get("mapped-model"));
        assertThrows(UnsupportedOperationException.class, () -> profile.modelTokenFields().get("mapped-model").clear());
    }

    @Test
    void mergesSparseProfileOverrides() {
        AiProviderProfile base = AiProviderProfiles.openAi();
        AiProviderProfile override = new AiProviderProfile(null, URI.create("https://gateway.test/v1"), null, null,
                Map.of("alias", "actual"), null, null, JsonNodeFactory.instance.objectNode().put("seed", 9), null, null, null,
                Map.of("actual", Set.of("custom_token_limit")));
        AiProviderProfile merged = AiProviderProfiles.merge(base, override);
        assertEquals(base.name(), merged.name());
        assertEquals(override.defaultEndpoint(), merged.defaultEndpoint());
        assertEquals("/chat/completions", merged.operationPaths().get(AiProviderProfiles.CHAT_COMPLETIONS));
        assertEquals("/chat/completions", merged.operationPaths().get("chat.completions"));
        assertTrue(merged.capabilities().protocols().containsAll(List.of("openai-chat", "openai")));
        assertTrue(merged.capabilities().operations().containsAll(List.of("chat-completions", "chat.completions")));
        assertEquals("actual", merged.modelMappings().get("alias"));
        assertEquals(base.tokenFields(), merged.tokenFields());
        assertEquals(Set.of("custom_token_limit"), merged.modelTokenFields().get("actual"));
        AiProviderProfile emptyTokens = new AiProviderProfile(null, null, null, null, null, null, null, null, Set.of(), null, null);
        assertEquals(Set.of(), AiProviderProfiles.merge(base, emptyTokens).tokenFields());
        assertEquals(AiProviderCapabilities.Support.UNKNOWN, merged.capabilities().forModel("unspecified-model").stream());
        assertThrows(UnsupportedOperationException.class, () -> merged.capabilities().operations().clear());
    }

    @Test
    void discoversProvidersAndResolvesLegacyNames() {
        AiProviderRegistry registry = new AiProviderRegistry();
        assertTrue(registry.getProviders().keySet().containsAll(List.of("openai", "deepseek", "openai-compatible")));
        assertEquals("openai", registry.resolve("OpenAI").profileName());
        assertEquals("deepseek", registry.getProvider("DeepSeek").getName());
        assertEquals("openai-compatible", registry.getProvider("ALiYun").getName());
        assertEquals("openai-compatible", registry.getProvider("Open API").getName());
        assertEquals("openai-compatible", registry.getProvider("unknown-provider").getName());
        assertNull(AiProviderProfiles.openAiCompatible().defaultEndpoint());
        assertEquals(Set.of("max_completion_tokens"), AiProviderProfiles.openAi().tokenFields());
        assertEquals(Set.of("max_tokens"), AiProviderProfiles.deepSeek().tokenFields());
        assertEquals(Set.of("max_tokens"), AiProviderProfiles.openAiCompatible().tokenFields());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Q7", "sk-provider-test-secret", "sk-provider-test-secret-with-a-long-suffix"})
    void redactsCredentialBearingValues(final String apiKey) {
        AiProviderProfile profile = new AiProviderProfile("profile-name-secret", null, Map.of(), null,
                Map.of(), Map.of(), Map.of(), JsonNodeFactory.instance.objectNode().put("api_key", "profile-default-secret"), null, null, null);
        AiProviderConfig config = new AiProviderConfig("protocol-secret",
                URI.create("https://uri-user-secret:uri-password-secret@example.test/v1?api_key=uri-query-secret"), apiKey,
                Map.of("Authorization", List.of("Bearer header-token-secret")), Map.of("api_key", List.of("query-token-secret")),
                "model-secret", 0.5D, 256L, true, profile);

        String description = config.toString();
        assertTrue(description.startsWith("AiProviderConfig{"));
        assertTrue(description.contains("apiKey=[REDACTED]"));
        assertTrue(description.contains("temperature=0.5"));
        assertTrue(description.contains("maxTokens=256"));
        assertTrue(description.contains("stream=true"));
        assertEquals(apiKey, config.getApiKey());
        if (Objects.nonNull(apiKey) && !apiKey.isEmpty()) {
            assertFalse(description.contains(apiKey));
        }
        for (String secret : List.of("protocol-secret", "uri-user-secret", "uri-password-secret", "uri-query-secret",
                "header-token-secret", "query-token-secret", "model-secret", "profile-name-secret", "profile-default-secret")) {
            assertFalse(description.contains(secret), "Configuration descriptions must not expose credential-bearing values");
        }
    }
}
