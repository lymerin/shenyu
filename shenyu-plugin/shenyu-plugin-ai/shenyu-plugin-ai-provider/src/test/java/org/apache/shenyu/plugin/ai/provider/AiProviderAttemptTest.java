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

package org.apache.shenyu.plugin.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.api.model.AiUpstreamResponse;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiResponse;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiStreamEvent;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.openai.OpenAiProvider;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import org.apache.shenyu.plugin.ai.provider.registry.AiProviderRegistry;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderAuthenticator;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderCapabilityValidator;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderEndpointResolver;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderRequestAssembler;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderRequestMapper;
import org.apache.shenyu.plugin.ai.provider.stream.AiProviderStreamEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-attempt configuration and per-subscription state stay outside shared provider instances.
 */
class AiProviderAttemptTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reusesProviderWithoutRetainingCredentialsOrConsumingResponseBuffers() throws Exception {
        OpenAiProvider provider = new OpenAiProvider();
        AiProviderProfile overrides = new AiProviderProfile(null, null, null, null, Map.of("alias", "upstream-model"),
                null, null, null, null, null, null);
        ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, "alias", false, null,
                JSON.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"));
        AiProviderConfig firstConfig = new AiProviderConfig("openai", URI.create("https://first.test/v1"), "first-secret",
                Map.of(), Map.of(), null, null, null, null, overrides);
        AiProviderConfig secondConfig = new AiProviderConfig("openai", URI.create("https://second.test/v1"), "second-secret",
                Map.of(), Map.of(), null, null, null, null, null);
        AbstractOpenAiCompatibleProvider.PreparedRequest first = provider.prepareRequest(request, firstConfig);
        AbstractOpenAiCompatibleProvider.PreparedRequest second = provider.prepareRequest(request, secondConfig);
        assertEquals("first.test", first.upstreamRequest().uri().getHost());
        assertEquals("second.test", second.upstreamRequest().uri().getHost());
        assertEquals(List.of("Bearer first-secret"), first.upstreamRequest().headers().get("Authorization"));
        assertEquals(List.of("Bearer second-secret"), second.upstreamRequest().headers().get("Authorization"));
        assertEquals("upstream-model", first.profile().modelMappings().get("alias"));
        assertFalse(first.toString().contains("first-secret"));

        byte[] bytes = ("!{\"model\":\"upstream-model\",\"choices\":[{\"message\":{\"content\":\"你好\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2},\"vendor\":true}").getBytes(StandardCharsets.UTF_8);
        int split = bytes.length / 2;
        ByteBuffer start = ByteBuffer.wrap(bytes, 1, split - 1);
        ByteBuffer end = ByteBuffer.wrap(bytes, split, bytes.length - split);
        AiUpstreamResponse raw = new AiUpstreamResponse(200, Map.of("X-Upstream", List.of("test")), Flux.just(start, end));
        ShenyuAiResponse response = provider.decodeResponse(raw, first.profile()).block();
        assertNotNull(response);
        assertEquals("upstream-model", response.model());
        assertEquals("你好", response.payload().path("choices").get(0).path("message").path("content").asText());
        assertEquals(2L, response.usage().inputTokens());
        assertEquals("stop", response.finishReason().value());
        assertEquals(1, start.position());
        assertEquals(split, end.position());
        assertEquals(response, provider.decodeResponse(raw, first.profile()).block());
    }

    @Test
    void createsFreshStreamStateForEverySubscription() throws Exception {
        OpenAiProvider provider = new OpenAiProvider();
        AiProviderProfile profile = AiProviderProfiles.merge(AiProviderProfiles.openAi(),
                new AiProviderProfile(null, null, null, null, null, null, null, null, null, AiProviderProfile.UsageMode.INCREMENTAL, null));
        Flux<AiProviderStreamEventMapper.DecodedEvent> input = Flux.just(
                new AiProviderStreamEventMapper.DecodedEvent("message", JSON.readTree("{\"model\":\"chat\",\"usage\":{\"completion_tokens\":1}}")),
                new AiProviderStreamEventMapper.DecodedEvent("message", JSON.readTree("{\"usage\":{\"completion_tokens\":2}}")),
                new AiProviderStreamEventMapper.DecodedEvent("done", null));
        Flux<ShenyuAiStreamEvent> mapped = provider.mapDecodedStream(input, profile);
        List<ShenyuAiStreamEvent> first = mapped.collectList().block();
        List<ShenyuAiStreamEvent> second = mapped.collectList().block();
        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(List.of("usage", "usage", "done"), first.stream().map(ShenyuAiStreamEvent::type).toList());
        assertEquals(3L, first.get(2).usage().outputTokens());
        assertEquals("chat", first.get(2).model());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>Bad Gateway</html>", "502 Bad Gateway", "{} trailing text", "", "   "})
    void normalizesNonJsonAndEmptyErrorResponses(final String body) {
        final Map<String, List<String>> headers = Map.of("X-Upstream", List.of("gateway"));
        AiUpstreamResponse raw = new AiUpstreamResponse(502, headers, body.isEmpty() ? Flux.empty()
                : Flux.just(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
        ShenyuAiResponse response = new OpenAiProvider().decodeResponse(raw, AiProviderProfiles.openAi()).block();
        assertNotNull(response);
        assertEquals(502, response.statusCode());
        assertEquals(headers, response.headers());
        assertNotNull(response.error());
        assertEquals("upstream_error", response.error().type());
        assertTrue(response.error().retryable());
        assertEquals(response.payload(), response.error().payload());
        if (body.isBlank()) {
            assertTrue(response.payload().isObject());
            assertTrue(response.payload().isEmpty());
            assertTrue(response.error().message().contains("502"));
        } else {
            assertEquals(body, response.payload().textValue());
            assertEquals(body, response.error().message());
        }
    }

    @Test
    void replacesKnownProfilesInsteadOfInheritingThePreviousVendorEndpoint() throws Exception {
        final AiProviderRegistry registry = new AiProviderRegistry();
        final AiProviderProfile generic = new AiProviderProfile("openai-compatible", null, null, null, null, null, null, null, null, null, null);
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, "gpt-3.5-turbo", false, null,
                JSON.readTree("{\"max_completion_tokens\":100}"));
        final AiProviderConfig missingEndpoint = new AiProviderConfig(null, null, "secret", Map.of(), Map.of(), null, null, 200L, null, generic);
        for (String provider : List.of("OpenAI", "deepseek", "openai-compatible")) {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> registry.resolve(provider).prepareRequest(request, missingEndpoint));
            assertTrue(failure.getMessage().contains("endpoint"));
            final AiProviderConfig configured = new AiProviderConfig(null, URI.create("https://vendor.test/v1"), "secret", Map.of(), Map.of(),
                    null, null, 200L, null, generic);
            final AbstractOpenAiCompatibleProvider.PreparedRequest prepared = registry.resolve(provider).prepareRequest(request, configured);
            assertEquals("openai-compatible", prepared.profile().name());
            assertNull(prepared.profile().defaultEndpoint());
            assertEquals("vendor.test", prepared.upstreamRequest().uri().getHost());
            assertTrue(prepared.profile().modelTokenFields().isEmpty());
            assertTrue(prepared.profile().capabilities().models().isEmpty());
            assertEquals(200, payload(prepared).path("max_tokens").asInt());
            assertFalse(payload(prepared).has("max_completion_tokens"));
        }
        final AiProviderProfile deepSeek = new AiProviderProfile("deepseek", null, null, null, null, null, null, null, null, null, null);
        final AbstractOpenAiCompatibleProvider.PreparedRequest switched = registry.resolve("OpenAI").prepareRequest(request,
                new AiProviderConfig(null, null, "secret", Map.of(), Map.of(), null, null, 200L, null, deepSeek));
        assertEquals("api.deepseek.com", switched.upstreamRequest().uri().getHost());
        assertTrue(switched.profile().modelTokenFields().isEmpty());
    }

    @Test
    void appliesSelectedProfilesAndModelRulesWithoutMutatingTheSharedProvider() throws Exception {
        AiProviderRegistry registry = new AiProviderRegistry();
        final AiProviderRegistry.ProviderSelection primary = registry.resolve("Aliyun");
        final AiProviderRegistry.ProviderSelection fallback = registry.resolve("Moonshot");
        assertSame(primary.provider(), fallback.provider());
        final Map<String, Set<String>> modelRules = Map.of("upstream-model", Set.of("max_tokens", "max_completion_tokens"));
        final AiProviderProfile modelOverride = new AiProviderProfile(null, null, null, null, Map.of("alias", "upstream-model"),
                null, null, null, null, null, null, modelRules);
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, "alias", false, null,
                JSON.readTree("{\"max_tokens\":100,\"max_completion_tokens\":150,\"vendor\":true}"));
        final AiProviderConfig primaryConfig = new AiProviderConfig(null, URI.create("https://primary.test/v1"), "primary-secret",
                Map.of(), Map.of(), null, null, 200L, null, modelOverride);
        AbstractOpenAiCompatibleProvider.PreparedRequest prepared = primary.prepareRequest(request, primaryConfig);
        assertEquals("aliyun", prepared.profile().name());
        assertEquals(modelRules.get("upstream-model"), prepared.profile().tokenFields());
        assertEquals("upstream-model", payload(prepared).path("model").asText());
        assertEquals(200, payload(prepared).path("max_tokens").asInt());
        assertEquals(200, payload(prepared).path("max_completion_tokens").asInt());
        assertTrue(payload(prepared).path("vendor").asBoolean());

        AiProviderProfile explicit = new AiProviderProfile(null, null, null, null, modelOverride.modelMappings(), null, null, null,
                Set.of("max_tokens"), null, null, modelRules);
        AiProviderConfig explicitConfig = new AiProviderConfig(null, primaryConfig.getEndpoint(), "explicit-secret",
                Map.of(), Map.of(), null, null, 200L, null, explicit);
        AbstractOpenAiCompatibleProvider.PreparedRequest overridden = primary.prepareRequest(request, explicitConfig);
        assertEquals(Set.of("max_tokens"), overridden.profile().tokenFields());
        assertFalse(payload(overridden).has("max_completion_tokens"));
        AiProviderConfig fallbackConfig = new AiProviderConfig(null, URI.create("https://fallback.test/v1"), "fallback-secret",
                Map.of(), Map.of(), "different-model", null, 300L, null, null);
        AbstractOpenAiCompatibleProvider.PreparedRequest fallbackAttempt = fallback.prepareRequest(request, fallbackConfig);
        assertEquals("moonshot", fallbackAttempt.profile().name());
        assertEquals(300, payload(fallbackAttempt).path("max_tokens").asInt());
        assertFalse(payload(fallbackAttempt).has("max_completion_tokens"));
        assertEquals("fallback.test", fallbackAttempt.upstreamRequest().uri().getHost());
        assertEquals(List.of("Bearer fallback-secret"), fallbackAttempt.upstreamRequest().headers().get("Authorization"));
        assertEquals(modelRules.get("upstream-model"), prepared.profile().tokenFields());
        assertEquals(100, request.payload().path("max_tokens").asInt());
        assertNull(primaryConfig.getProfileOverride().tokenFields());

        AiProviderRegistry.ProviderSelection selected = new AiProviderRegistry.ProviderSelection(primary.provider(), "openai");
        AbstractOpenAiCompatibleProvider.PreparedRequest named = selected.prepareRequest(request,
                new AiProviderConfig(null, null, "named-secret", Map.of(), Map.of(), null, null, 200L, null, null));
        assertEquals("api.openai.com", named.upstreamRequest().uri().getHost());
        assertEquals("openai", named.profile().name());
        assertEquals(Set.of("max_completion_tokens"), named.profile().tokenFields());
        assertFalse(payload(named).has("max_tokens"));
        AiProviderRegistry.ProviderSelection undefined = new AiProviderRegistry.ProviderSelection(registry.getProvider("OpenAI"), "custom-profile");
        AbstractOpenAiCompatibleProvider.PreparedRequest inherited = undefined.prepareRequest(request,
                new AiProviderConfig(null, null, "custom-secret", Map.of(), Map.of(), null, null, 200L, null, null));
        assertEquals("custom-profile", inherited.profile().name());
        assertEquals("api.openai.com", inherited.upstreamRequest().uri().getHost());
        assertEquals(Set.of("max_completion_tokens"), inherited.profile().tokenFields());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("effectiveModelSources")
    void usesTheSameEffectiveModelForPayloadAndTokenRules(final String source, final String model) throws Exception {
        final OpenAiProvider provider = new OpenAiProvider();
        final AiProviderProfile override = new AiProviderProfile(null, null, null, null, Map.of("selected", model, model, "mapped-twice"),
                null, null, JSON.createObjectNode().put("model", "selected"), null, null, null);
        final AiProviderConfig config = new AiProviderConfig(null, null, "secret", Map.of(), Map.of(),
                "config".equals(source) ? "selected" : null, null, 200L, null, override);
        final String normalizedModel = switch (source) {
            case "request" -> "selected";
            case "config" -> "lower-priority";
            default -> null;
        };
        final ObjectNode original = JSON.createObjectNode().put("max_tokens", 100).put("max_completion_tokens", 150);
        if (!"defaults".equals(source)) {
            original.put("model", "payload".equals(source) ? "selected" : "lower-priority");
        }
        final JsonNode snapshot = original.deepCopy();
        final ShenyuAiRequest request = new ShenyuAiRequest(AiProviderProfiles.CHAT_COMPLETIONS, normalizedModel, false, 999L, original);
        final AiProviderProfile profile = AiProviderProfiles.merge(AiProviderProfiles.openAi(), override);
        final AiProviderRequestMapper mapper = new AiProviderRequestMapper();
        final AiProviderRequestAssembler assembler = new AiProviderRequestAssembler(mapper, new AiProviderCapabilityValidator(),
                new AiProviderEndpointResolver(), new AiProviderAuthenticator());
        final String resolved = mapper.resolveEffectiveModel(request, config, profile);
        assertEquals(model, resolved);
        assertEquals(resolved, assembler.resolveEffectiveModel(request, config, profile));
        final AbstractOpenAiCompatibleProvider.PreparedRequest prepared = provider.prepareRequest(request, config);
        final JsonNode payload = payload(prepared);
        assertEquals(resolved, payload.path("model").asText());
        final Set<String> expectedFields = "gpt-3.5-turbo".equals(model) ? Set.of("max_tokens") : Set.of("max_completion_tokens");
        assertEquals(expectedFields, prepared.profile().tokenFields());
        assertEquals(expectedFields, Stream.of("max_tokens", "max_completion_tokens").filter(payload::has).collect(Collectors.toSet()));
        expectedFields.forEach(field -> assertEquals(200, payload.path(field).asInt()));
        assertEquals(snapshot, original);
    }

    private static Stream<Arguments> effectiveModelSources() {
        return Stream.of("config", "request", "payload", "defaults").flatMap(source ->
                Stream.of("gpt-4.1", "gpt-3.5-turbo").map(model -> Arguments.of(source, model)));
    }

    @Test
    void requiresAnExplicitCompletionMarkerAndStopsAfterIt() throws Exception {
        final OpenAiProvider provider = new OpenAiProvider();
        final AiProviderProfile profile = AiProviderProfiles.openAi();
        final AiProviderStreamEventMapper.DecodedEvent data = new AiProviderStreamEventMapper.DecodedEvent("message",
                JSON.readTree("{\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":2}}"));
        final AiProviderStreamEventMapper.DecodedEvent done = new AiProviderStreamEventMapper.DecodedEvent("done", null);
        List<ShenyuAiStreamEvent> incomplete = provider.mapDecodedStream(Flux.just(data), profile).collectList().block();
        assertNotNull(incomplete);
        assertEquals(List.of("finish", "usage"), incomplete.stream().map(ShenyuAiStreamEvent::type).toList());
        List<ShenyuAiStreamEvent> completed = provider.mapDecodedStream(Flux.just(data, done, done, data), profile).collectList().block();
        assertNotNull(completed);
        assertEquals(List.of("finish", "usage", "done"), completed.stream().map(ShenyuAiStreamEvent::type).toList());
        assertEquals(2L, completed.get(2).usage().outputTokens());
        assertEquals("stop", completed.get(2).finishReason().value());
        List<ShenyuAiStreamEvent> observed = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> provider.mapDecodedStream(
                Flux.concat(Flux.just(data), Flux.error(new IllegalStateException("upstream interrupted"))), profile).doOnNext(observed::add).blockLast());
        assertEquals(List.of("finish", "usage"), observed.stream().map(ShenyuAiStreamEvent::type).toList());
        List<ShenyuAiStreamEvent> cancelled = provider.mapDecodedStream(Flux.concat(Flux.just(data), Flux.never()), profile).take(1).collectList().block();
        assertNotNull(cancelled);
        assertEquals(List.of("finish"), cancelled.stream().map(ShenyuAiStreamEvent::type).toList());
    }

    private JsonNode payload(final AbstractOpenAiCompatibleProvider.PreparedRequest request) throws Exception {
        ByteBuffer buffer = request.upstreamRequest().body().blockFirst();
        assertNotNull(buffer);
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return JSON.readTree(bytes);
    }
}
