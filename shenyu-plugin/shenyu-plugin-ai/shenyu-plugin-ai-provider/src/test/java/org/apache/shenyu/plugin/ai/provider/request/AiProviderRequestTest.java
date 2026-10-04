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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.api.model.AiUpstreamRequest;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderCapabilities;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import org.apache.shenyu.plugin.ai.provider.registry.AiProviderRegistry;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderErrorMapper;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderResponseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProviderRequestMapper mapper = new AiProviderRequestMapper();

    private final AiProviderRequestAssembler assembler = new AiProviderRequestAssembler(mapper,
            new AiProviderCapabilityValidator(), new AiProviderEndpointResolver(), new AiProviderAuthenticator());

    @ParameterizedTest
    @ValueSource(strings = {"openai", "deepseek", "compatible"})
    void assemblesProviderRequestsWithIndependentBodySubscriptions(final String provider) throws Exception {
        final AiProviderProfile profile = switch (provider) {
            case "openai" -> AiProviderProfiles.openAi();
            case "deepseek" -> AiProviderProfiles.deepSeek();
            default -> AiProviderProfiles.openAiCompatible();
        };
        final URI endpoint = "compatible".equals(provider) ? URI.create("https://gateway.example/v1") : null;
        final AiProviderConfig config = config(endpoint, "configured-model", 0.4, 200L, true, Map.of("content-type", List.of("application/custom")), Map.of());
        final JsonNode original = MAPPER.readTree("{\"model\":\"raw\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"你好\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://images.example/photo.png\"}}]},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"result\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"parameters\":{\"type\":\"object\"}}}],"
                + "\"vendor\":{\"enabled\":true},\"max_tokens\":100,\"max_completion_tokens\":150}");
        final JsonNode snapshot = original.deepCopy();
        final AiUpstreamRequest upstream = assembler.assemble(new ShenyuAiRequest("chat.completions", "shared", false, 100L, original), config, profile);
        assertEquals("POST", upstream.method());
        assertEquals(List.of("Bearer secret"), upstream.headers().get("Authorization"));
        assertEquals(List.of("application/custom"), upstream.headers().get("content-type"));
        assertFalse(upstream.headers().containsKey("Content-Type"));
        assertEquals(List.of("content-type", "Authorization"), new ArrayList<>(upstream.headers().keySet()));
        assertEquals("deepseek".equals(provider) ? "/chat/completions" : "/v1/chat/completions", upstream.uri().getPath());
        final ByteBuffer first = upstream.body().blockFirst();
        assertTrue(first.isReadOnly());
        final byte[] bytes = new byte[first.remaining()];
        first.get(bytes);
        final JsonNode body = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        assertEquals(bytes.length, upstream.body().blockFirst().remaining());
        assertEquals("configured-model", body.get("model").asText());
        assertEquals(0.4, body.get("temperature").asDouble());
        assertTrue(body.get("stream").asBoolean());
        assertEquals(original.get("vendor"), body.get("vendor"));
        assertEquals(original.get("messages"), body.get("messages"));
        assertEquals(original.get("tools"), body.get("tools"));
        if ("openai".equals(provider)) {
            assertEquals(200L, body.get("max_completion_tokens").asLong());
            assertFalse(body.has("max_tokens"));
        } else {
            assertEquals(200L, body.get("max_tokens").asLong());
            assertFalse(body.has("max_completion_tokens"));
        }
        assertFalse(original.has("temperature"));
        assertEquals("raw", original.get("model").asText());
        assertEquals(100L, original.get("max_tokens").asLong());
        assertEquals(snapshot, original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI", "DeepSeek", "Open API"})
    void keepsConfiguredProtocolIndependentOfProviderAndModel(final String provider) throws Exception {
        final AiProviderRegistry.ProviderSelection selection = new AiProviderRegistry().resolve(provider);
        final URI endpoint = "Open API".equals(provider) ? URI.create("https://vendor.example/v1") : null;
        final JsonNode original = MAPPER.readTree("{\"model\":\"gpt-4.1\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        final JsonNode snapshot = original.deepCopy();
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, "gpt-4.1", false, null, original);
        final AiProviderConfig config = new AiProviderConfig(AiProviderProfiles.OPENAI_PROTOCOL, endpoint, "secret",
                Map.of(), Map.of(), null, null, null, null, null);
        final AiUpstreamRequest upstream = selection.prepareRequest(request, config).upstreamRequest();
        final String host = switch (provider) {
            case "OpenAI" -> "api.openai.com";
            case "DeepSeek" -> "api.deepseek.com";
            default -> "vendor.example";
        };
        assertEquals(host, upstream.uri().getHost());
        assertEquals(List.of("Bearer secret"), upstream.headers().get("Authorization"));
        final ByteBuffer buffer = upstream.body().blockFirst();
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        final JsonNode payload = MAPPER.readTree(bytes);
        assertEquals("gpt-4.1", payload.path("model").asText());
        assertEquals(original.get("messages"), payload.get("messages"));
        for (String protocol : List.of("openai-responses", "anthropic-messages")) {
            final AiProviderConfig unsupported = new AiProviderConfig(protocol, endpoint, "secret",
                    Map.of(), Map.of(), null, null, null, null, null);
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> selection.prepareRequest(request, unsupported));
            assertTrue(failure.getMessage().contains("protocol:" + protocol));
        }
        assertEquals(AiProviderProfiles.OPENAI_PROTOCOL, config.getProtocol());
        assertEquals(snapshot, original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "max_output_tokens"})
    void mapsFieldsAndDefaultsAndPreservesRawTokensWithoutConfiguration(final String tokenField) throws Exception {
        final AiProviderProfile base = AiProviderProfiles.openAiCompatible();
        final ObjectNode defaults = (ObjectNode) MAPPER.readTree("{\"temperature\":0.8,\"stream\":true,\"response_format\":{\"type\":\"json_object\"},"
                + "\"max_tokens\":1,\"max_completion_tokens\":2}");
        defaults.put(tokenField, 3);
        final AiProviderProfile mappedProfile = profile(base, base.authentication(), Map.of("shared", "vendor-model"),
                Map.of("model", "deployment", "stream", "streaming", "tools", "functions", "response_format", "format",
                        "max_tokens", "legacy_token_limit", "vendor_option", "max_completion_tokens"), defaults, base.capabilities());
        final AiProviderProfile profile = new AiProviderProfile(mappedProfile.name(), mappedProfile.defaultEndpoint(), mappedProfile.operationPaths(),
                mappedProfile.authentication(), mappedProfile.modelMappings(), mappedProfile.requestFieldMappings(), mappedProfile.responseFieldMappings(),
                mappedProfile.requestDefaults(), Set.of(tokenField), mappedProfile.usageMode(), mappedProfile.capabilities());
        final JsonNode original = MAPPER.readTree("{\"model\":\"raw\",\"temperature\":0.2,\"tools\":[{\"type\":\"function\"}],\"vendor_option\":7,\"max_tokens\":100,\"max_completion_tokens\":150}");
        final AiProviderConfig config = config(null, null, null, 200L, null, Map.of(), Map.of());
        final AiProviderRequestMapper.MappedRequest mapped = mapper.map(new ShenyuAiRequest("chat.completions", "shared", false, 300L, original), config, profile);
        assertEquals("vendor-model", mapped.model());
        assertTrue(mapped.stream());
        assertEquals(0.2, mapped.payload().get("temperature").asDouble());
        assertTrue(mapped.payload().has("functions"));
        assertTrue(mapped.payload().has("format"));
        assertFalse(mapped.payload().has("tools"));
        assertEquals(7, mapped.payload().get("vendor_option").asInt());
        assertEquals(200, mapped.payload().get(tokenField).asInt());
        assertFalse(mapped.payload().has("legacy_token_limit"));
        assertFalse(mapped.payload().has("max_completion_tokens"));
        assertEquals("max_tokens".equals(tokenField), mapped.payload().has("max_tokens"));
        assertEquals("raw", original.get("model").asText());
        final AiProviderConfig unconfigured = config(null, null, null, null, null, Map.of(), Map.of());
        final AiProviderRequestMapper.MappedRequest preserved = mapper.map(new ShenyuAiRequest("chat.completions", "shared", false, 300L, original), unconfigured, profile);
        assertEquals(original.get("max_tokens"), preserved.payload().get("max_tokens"));
        assertEquals(original.get("max_completion_tokens"), preserved.payload().get("max_completion_tokens"));
        final AiProviderRequestMapper.MappedRequest omitted = mapper.map(new ShenyuAiRequest("chat.completions", "shared", false, 300L, MAPPER.createObjectNode()), unconfigured, profile);
        assertFalse(omitted.payload().has("max_tokens"));
        assertFalse(omitted.payload().has("max_completion_tokens"));
        assertFalse(omitted.payload().has(tokenField));
    }

    @Test
    void preservesClientTokensWhenProfileDoesNotDeclareTokenFields() throws Exception {
        final AiProviderProfile base = AiProviderProfiles.openAiCompatible();
        final AiProviderProfile profile = new AiProviderProfile(base.name(), base.defaultEndpoint(), base.operationPaths(),
                base.authentication(), base.modelMappings(), base.requestFieldMappings(), base.responseFieldMappings(),
                base.requestDefaults(), null, base.usageMode(), base.capabilities());
        final JsonNode original = MAPPER.readTree("{\"max_tokens\":100,\"max_completion_tokens\":150,\"stream\":false}");
        final JsonNode snapshot = original.deepCopy();
        final AiProviderRequestMapper.MappedRequest mapped = mapper.map(
                new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, null, false, null, original),
                config(null, null, null, 200L, null, Map.of(), Map.of()), profile);
        assertEquals(snapshot, mapped.payload());
        assertEquals(snapshot, original);
    }

    @ParameterizedTest
    @EnumSource(value = AiProviderProfile.AuthenticationMode.class, names = {"HEADER", "QUERY"})
    void rejectsAuthenticationWithoutAParameterName(final AiProviderProfile.AuthenticationMode mode) {
        final AiProviderProfile base = AiProviderProfiles.openAiCompatible();
        final AiProviderProfile profile = profile(base, new AiProviderProfile.Authentication(mode, null, ""),
                Map.of(), Map.of(), null, base.capabilities());
        final AiProviderConfig configured = config(URI.create("https://gateway.example/v1"), null, null, null, null, Map.of(), Map.of());
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, null, false, null, MAPPER.createObjectNode()), configured, profile));
        assertTrue(failure.getMessage().contains(mode.name()));
        assertTrue(failure.getMessage().contains("parameter name"));
        assertFalse(failure.getMessage().contains(configured.getApiKey()));
    }

    @Test
    void mergesAuthenticationSnapshotsAndEncodesQueriesWhileRetainingBasePath() {
        final AiProviderProfile base = AiProviderProfiles.openAi();
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-First", List.of("one", "two"));
        headers.put("authorization", new ArrayList<>(List.of("old")));
        headers.put("X-Last", List.of("last"));
        final Map<String, List<String>> queries = new LinkedHashMap<>();
        queries.put("标签", List.of("a +/中文"));
        queries.put("repeat", List.of("one", "two"));
        final AiProviderConfig config = config(URI.create("https://gateway.example/v1?existing=a%20b&api_key=old"), null, null, null, null,
                headers, queries);
        final AiProviderAuthenticator authenticator = new AiProviderAuthenticator();
        final AiProviderAuthenticator.AuthenticationParameters bearer = authenticator.authenticate(config, base);
        assertEquals(List.of("X-First", "X-Last", "Authorization"), new ArrayList<>(bearer.headers().keySet()));
        assertEquals(List.of("one", "two"), bearer.headers().get("X-First"));
        assertEquals(List.of("Bearer secret"), bearer.headers().get("Authorization"));
        assertEquals(List.of("old"), headers.get("authorization"));
        assertThrows(UnsupportedOperationException.class, () -> bearer.headers().get("Authorization").add("changed"));
        final AiProviderProfile queryProfile = profile(base, new AiProviderProfile.Authentication(AiProviderProfile.AuthenticationMode.QUERY, "api_key", ""),
                Map.of(), Map.of(), null, base.capabilities());
        final AiProviderAuthenticator.AuthenticationParameters query = authenticator.authenticate(config, queryProfile);
        assertEquals(List.of("old"), query.headers().get("authorization"));
        final URI uri = new AiProviderEndpointResolver().resolve("chat.completions", config, queryProfile, query.queryParameters());
        assertEquals("/v1/chat/completions", uri.getPath());
        assertTrue(uri.getRawQuery().contains("existing=a%20b"));
        assertTrue(uri.getRawQuery().contains("api_key=secret"));
        assertFalse(uri.getRawQuery().contains("api_key=old"));
        assertTrue(uri.getRawQuery().contains("%E6%A0%87%E7%AD%BE=a%20%2B%2F%E4%B8%AD%E6%96%87"));
        assertEquals("existing=a%20b&%E6%A0%87%E7%AD%BE=a%20%2B%2F%E4%B8%AD%E6%96%87&repeat=one&repeat=two&api_key=secret", uri.getRawQuery());
        assertThrows(UnsupportedOperationException.class, () -> query.queryParameters().put("extra", List.of("value")));
        final AiProviderProfile headerProfile = profile(base, new AiProviderProfile.Authentication(AiProviderProfile.AuthenticationMode.HEADER, "X-Key", "Key "),
                Map.of(), Map.of(), null, base.capabilities());
        assertEquals(List.of("Key secret"), authenticator.authenticate(config, headerProfile).headers().get("X-Key"));
        final AiProviderProfile none = profile(base, new AiProviderProfile.Authentication(AiProviderProfile.AuthenticationMode.NONE, null, null),
                Map.of(), Map.of(), null, base.capabilities());
        assertEquals(List.of("old"), authenticator.authenticate(config, none).headers().get("authorization"));
        final AiProviderProfile overlapping = new AiProviderProfile(base.name(), base.defaultEndpoint(), Map.of("chat.completions", "/v1/chat/completions"),
                base.authentication(), base.modelMappings(), base.requestFieldMappings(), base.responseFieldMappings(), base.requestDefaults(),
                base.tokenFields(), base.usageMode(), base.capabilities());
        assertEquals("/v1/chat/completions", new AiProviderEndpointResolver().resolve("chat.completions", config, overlapping, Map.of()).getPath());
    }

    @Test
    void rejectsEndpointCredentialsWithoutExposingThem() {
        final URI endpoint = URI.create("https://user:password@gateway.example/v1");
        final AiProviderProfile base = AiProviderProfiles.openAi();
        final AiProviderConfig configured = config(endpoint, null, null, null, null, Map.of(), Map.of());
        final AiProviderEndpointResolver resolver = new AiProviderEndpointResolver();
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(AiProviderProfiles.CHAT_COMPLETIONS, configured, base, Map.of()));
        assertTrue(failure.getMessage().contains("user info"));
        assertFalse(failure.getMessage().contains("password"));
        final AiProviderProfile profile = new AiProviderProfile(base.name(), endpoint, base.operationPaths(), base.authentication(),
                base.modelMappings(), base.requestFieldMappings(), base.responseFieldMappings(), base.requestDefaults(),
                base.tokenFields(), base.usageMode(), base.capabilities());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(AiProviderProfiles.CHAT_COMPLETIONS,
                config(null, null, null, null, null, Map.of(), Map.of()), profile, Map.of()));
    }

    @Test
    void rejectsInvalidOperationPathsWithoutLeakingQueryCredentials() {
        final AiProviderProfile base = AiProviderProfiles.openAiCompatible();
        final AiProviderProfile profile = new AiProviderProfile(base.name(), null,
                Map.of(AiProviderProfiles.CHAT_COMPLETIONS, "/chat completions"),
                new AiProviderProfile.Authentication(AiProviderProfile.AuthenticationMode.QUERY, "api_key", ""),
                base.modelMappings(), base.requestFieldMappings(), base.responseFieldMappings(), base.requestDefaults(),
                base.tokenFields(), base.usageMode(), base.capabilities());
        final String credential = "review-dummy-key";
        final AiProviderConfig config = new AiProviderConfig(null, URI.create("https://gateway.example/v1"), credential,
                Map.of(), Map.of(), null, null, null, null, null);
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
                new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, null, false, null, MAPPER.createObjectNode()), config, profile));
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            assertFalse(Objects.toString(cause.getMessage(), "").contains(credential));
            assertFalse(Objects.toString(cause.getMessage(), "").contains("api_key="));
        }
    }

    @Test
    void renamesFieldsOnceFromOriginalValuesRegardlessOfMappingOrder() throws Exception {
        final AiProviderProfile base = AiProviderProfiles.openAiCompatible();
        final JsonNode original = MAPPER.readTree("{\"a\":1,\"b\":2,\"c\":3}");
        final Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("a", "b");
        mappings.put("b", "c");
        final Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("b", "c");
        reversed.put("a", "b");
        for (Map<String, String> order : List.of(mappings, reversed)) {
            final AiProviderProfile profile = profile(base, base.authentication(), Map.of(), order, null, base.capabilities());
            final AiProviderRequestMapper.MappedRequest mapped = mapper.map(new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS,
                    null, false, null, original), config(null, null, null, null, null, Map.of(), Map.of()), profile);
            assertEquals(MAPPER.readTree("{\"b\":1,\"c\":2,\"stream\":false}"), mapped.payload());
            assertEquals(MAPPER.readTree("{\"a\":1,\"b\":2,\"c\":3}"), original);
        }
    }

    @Test
    void validatesEffectiveMappedCapabilitiesAndPermitsUnknownModels() throws Exception {
        final AiProviderProfile base = AiProviderProfiles.openAi();
        final AiProviderCapabilities capabilities = new AiProviderCapabilities(Set.of("openai"), Set.of("chat.completions"),
                Map.of("limited", new AiProviderCapabilities.ModelCapabilities(AiProviderCapabilities.Support.UNSUPPORTED,
                        AiProviderCapabilities.Support.UNSUPPORTED, AiProviderCapabilities.Support.UNKNOWN)));
        final AiProviderProfile profile = profile(base, base.authentication(), Map.of(), Map.of("tools", "functions", "response_format", "format"), null, capabilities);
        final JsonNode payload = MAPPER.readTree("{\"tools\":[{\"type\":\"function\"}],\"response_format\":{\"type\":\"json_object\"}}");
        final AiProviderConfig limited = config(null, "limited", null, null, true, Map.of(), Map.of());
        final ShenyuAiRequest request = new ShenyuAiRequest("chat.completions", null, false, null, payload);
        final AiProviderCapabilityValidator.ValidationResult result = new AiProviderCapabilityValidator().validate("openai", mapper.map(request, limited, profile), profile);
        assertEquals(List.of("stream", "tools"), result.unsupported());
        assertEquals(List.of("jsonMode"), result.unknown());
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(request, limited, profile));
        final AiProviderConfig unknown = config(null, "new-model", null, null, true, Map.of(), Map.of());
        final AiProviderCapabilityValidator.ValidationResult unknownResult = new AiProviderCapabilityValidator().validate("openai", mapper.map(request, unknown, profile), profile);
        assertTrue(unknownResult.unsupported().isEmpty());
        assertEquals(List.of("model", "stream", "tools", "jsonMode"), unknownResult.unknown());
        assertEquals("POST", assembler.assemble(request, unknown, profile).method());
        final AiProviderConfig canonical = new AiProviderConfig(AiProviderProfiles.OPENAI_PROTOCOL, null, "secret", Map.of(), Map.of(),
                "new-model", null, null, false, null);
        final ShenyuAiRequest canonicalRequest = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, "new-model", false, null, MAPPER.createObjectNode());
        final AiProviderCapabilityValidator.ValidationResult canonicalResult = new AiProviderCapabilityValidator().validate(AiProviderProfiles.OPENAI_PROTOCOL,
                mapper.map(canonicalRequest, canonical, base), base);
        assertTrue(canonicalResult.unsupported().isEmpty());
        assertEquals(List.of("model"), canonicalResult.unknown());
        assertEquals("/v1/chat/completions", assembler.assemble(canonicalRequest, canonical, base).uri().getPath());
        final String operation = "custom-completion";
        final AiProviderProfile customPath = new AiProviderProfile(base.name(), base.defaultEndpoint(), Map.of(operation, "/custom/completions"),
                base.authentication(), base.modelMappings(), base.requestFieldMappings(), base.responseFieldMappings(), base.requestDefaults(),
                base.tokenFields(), base.usageMode(), base.capabilities());
        final ShenyuAiRequest customRequest = new ShenyuAiRequest(operation, "new-model", false, null, MAPPER.createObjectNode());
        final AiProviderCapabilityValidator.ValidationResult customResult = new AiProviderCapabilityValidator().validate(AiProviderProfiles.OPENAI_PROTOCOL,
                mapper.map(customRequest, canonical, customPath), customPath);
        assertTrue(customResult.unsupported().isEmpty());
        assertEquals(List.of("operation:" + operation, "model"), customResult.unknown());
        assertEquals("/v1/custom/completions", assembler.assemble(customRequest, canonical, customPath).uri().getPath());
        final IllegalArgumentException missingPath = assertThrows(IllegalArgumentException.class, () -> assembler.assemble(customRequest, canonical, base));
        assertTrue(missingPath.getMessage().contains(operation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4.1", "o1-mini", "deepseek-flash", "deepseek-v4-pro"})
    void validatesDocumentedInitialModelCapabilities(final String model) throws Exception {
        final AiProviderProfile profile = model.startsWith("deepseek-") ? AiProviderProfiles.deepSeek() : AiProviderProfiles.openAi();
        final JsonNode payload = MAPPER.readTree("{\"tools\":[{\"type\":\"function\"}],\"response_format\":{\"type\":\"json_object\"}}");
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, model, true, null, payload);
        final AiProviderConfig config = config(null, null, null, null, null, Map.of(), Map.of());
        final AiProviderCapabilityValidator.ValidationResult result = new AiProviderCapabilityValidator().validate(AiProviderProfiles.OPENAI_PROTOCOL,
                mapper.map(request, config, profile), profile);
        if ("o1-mini".equals(model)) {
            assertEquals(List.of("tools"), result.unsupported());
            assertEquals(List.of("jsonMode"), result.unknown());
            assertThrows(IllegalArgumentException.class, () -> assembler.assemble(request, config, profile));
        } else {
            assertTrue(result.unsupported().isEmpty());
            assertTrue(result.unknown().isEmpty());
            assertEquals("POST", assembler.assemble(request, config, profile).method());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestBaselines")
    void verifiesRequestBaseline(final String baseline, final String input, final String expected, final String model,
                                final Double temperature, final Long tokens, final Boolean stream, final boolean normalizedStream) throws Exception {
        final JsonNode original = MAPPER.readTree(input);
        final AiProviderConfig configured = config(null, model, temperature, tokens, stream, Map.of(), Map.of());
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, null, normalizedStream, null, original);
        final AiProviderProfile profile = AiProviderProfiles.openAi();
        final AiProviderRequestMapper.MappedRequest mapped = mapper.map(request, configured, profile);
        assertEquals(MAPPER.readTree(expected), MAPPER.readTree(mapped.payload().toString()), baseline);
        assertEquals(MAPPER.readTree(input), original, "Input ownership: " + baseline);
        assertFalse(mapped.payload().has("fallbackConfig"), baseline);
        assertEquals(mapped.payload(), new AiProviderResponseMapper(new AiProviderErrorMapper()).map(200, Map.of(), mapped.payload(), profile).payload(), baseline);
        if (Objects.nonNull(tokens)) {
            final AiProviderRequestMapper.MappedRequest unconfigured = mapper.map(request,
                    config(null, model, temperature, null, stream, Map.of(), Map.of()), profile);
            assertEquals(original.get("max_tokens"), unconfigured.payload().get("max_tokens"), baseline);
            assertEquals(original.get("max_completion_tokens"), unconfigured.payload().get("max_completion_tokens"), baseline);
        }
    }

    private static Stream<Arguments> requestBaselines() {
        return Stream.of(
                Arguments.of("baseline_1_configured_model_temperature_override_body",
                        "{\"model\":\"client\",\"temperature\":0.2,\"stream\":false}",
                        "{\"model\":\"configured\",\"temperature\":0.7,\"stream\":false}", "configured", 0.7, null, null, false),
                Arguments.of("baseline_2_explicit_client_stream_wins_over_configuration_default",
                        "{\"stream\":false}", "{\"stream\":false}", null, null, null, true, true),
                Arguments.of("baseline_3_configured_tokens_override_unconfigured_tokens_are_retained",
                        "{\"max_tokens\":100,\"stream\":false}", "{\"max_completion_tokens\":200,\"stream\":false}",
                        null, null, 200L, null, false),
                Arguments.of("baseline_4_conflicting_client_token_keys_are_retained_without_configuration",
                        "{\"max_tokens\":100,\"max_completion_tokens\":150,\"stream\":false}",
                        "{\"max_tokens\":100,\"max_completion_tokens\":150,\"stream\":false}", null, null, null, null, false),
                Arguments.of("baseline_5_unknown_fields_survive_request_response_round_trip",
                        "{\"messages\":[{\"role\":\"assistant\",\"reasoning_content\":\"retained\"}],\"vendor\":{\"enabled\":true},\"stream\":false}",
                        "{\"messages\":[{\"role\":\"assistant\",\"reasoning_content\":\"retained\"}],\"vendor\":{\"enabled\":true},\"stream\":false}",
                        null, null, null, null, false),
                Arguments.of("baseline_6_provider_does_not_introduce_internal_fields",
                        "{\"messages\":[],\"stream\":false}", "{\"messages\":[],\"stream\":false}", null, null, null, null, false));
    }

    private AiProviderConfig config(final URI endpoint, final String model, final Double temperature, final Long tokens, final Boolean stream,
                                    final Map<String, List<String>> headers, final Map<String, List<String>> query) {
        return new AiProviderConfig("openai", endpoint, "secret", headers, query, model, temperature, tokens, stream, null);
    }

    private AiProviderProfile profile(final AiProviderProfile base, final AiProviderProfile.Authentication authentication, final Map<String, String> models,
                                      final Map<String, String> fields, final JsonNode defaults, final AiProviderCapabilities capabilities) {
        return new AiProviderProfile(base.name(), base.defaultEndpoint(), base.operationPaths(), authentication, models, fields,
                base.responseFieldMappings(), defaults, base.tokenFields(), base.usageMode(), capabilities);
    }
}
