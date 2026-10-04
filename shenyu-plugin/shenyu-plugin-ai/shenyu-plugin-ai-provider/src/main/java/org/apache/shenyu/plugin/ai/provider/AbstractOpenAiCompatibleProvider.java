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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.shenyu.plugin.ai.api.model.AiUpstreamRequest;
import org.apache.shenyu.plugin.ai.api.model.AiUpstreamResponse;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiResponse;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiStreamEvent;
import org.apache.shenyu.plugin.ai.api.spi.ShenyuAiProvider;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderAuthenticator;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderCapabilityValidator;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderEndpointResolver;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderRequestAssembler;
import org.apache.shenyu.plugin.ai.provider.request.AiProviderRequestMapper;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderErrorMapper;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderResponseMapper;
import org.apache.shenyu.plugin.ai.provider.stream.AiProviderStreamEventMapper;
import org.apache.shenyu.plugin.ai.provider.stream.AiProviderStreamState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared provider organization and experimental SPI-to-component handoff points.
 *
 * <p>Per-call configuration and complete-event handoffs remain experimental pending the shared SPI contract.
 */
public abstract class AbstractOpenAiCompatibleProvider implements ShenyuAiProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Supplier<AiProviderProfile> profileSupplier;

    private final AiProviderRequestAssembler requestAssembler;

    private final AiProviderResponseMapper responseMapper;

    private final AiProviderStreamEventMapper streamEventMapper;

    protected AbstractOpenAiCompatibleProvider(final Supplier<AiProviderProfile> profileSupplier) {
        this.profileSupplier = profileSupplier;
        this.requestAssembler = new AiProviderRequestAssembler(new AiProviderRequestMapper(), new AiProviderCapabilityValidator(),
                new AiProviderEndpointResolver(), new AiProviderAuthenticator());
        this.responseMapper = new AiProviderResponseMapper(new AiProviderErrorMapper());
        this.streamEventMapper = new AiProviderStreamEventMapper(responseMapper);
    }

    protected AbstractOpenAiCompatibleProvider(
            final Supplier<AiProviderProfile> profileSupplier,
            final AiProviderRequestAssembler requestAssembler,
            final AiProviderResponseMapper responseMapper,
            final AiProviderStreamEventMapper streamEventMapper) {
        this.profileSupplier = profileSupplier;
        this.requestAssembler = requestAssembler;
        this.responseMapper = responseMapper;
        this.streamEventMapper = streamEventMapper;
    }

    @Override
    public Set<String> getSupportedProtocols() {
        return profileSupplier.get().capabilities().protocols();
    }

    @Override
    public AiUpstreamRequest createRequest(final ShenyuAiRequest request) {
        throw new UnsupportedOperationException("Pending #7381: per-call configuration handoff");
    }

    /**
     * Assemble a request with explicit per-call configuration.
     *
     * @param request normalized request
     * @param config resolved per-call configuration
     * @return the prepared upstream request
     */
    public AiUpstreamRequest createRequest(final ShenyuAiRequest request, final AiProviderConfig config) {
        return prepareRequest(request, config).upstreamRequest();
    }

    /**
     * Prepare an attempt while retaining its effective profile for response mapping.
     *
     * <p>The input payload must already exclude gateway fallback metadata. Header provenance
     * and fallback extraction belong to the caller; provider mapping retains vendor fields.
     *
     * @param request normalized request
     * @param config resolved per-call configuration
     * @return the resolved value
     */
    public PreparedRequest prepareRequest(final ShenyuAiRequest request, final AiProviderConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        AiProviderProfile profile = resolveRequestProfile(request, config);
        return new PreparedRequest(requestAssembler.assemble(request, config, profile), profile);
    }

    @Override
    public Mono<ShenyuAiResponse> decodeResponse(final AiUpstreamResponse response) {
        return Mono.error(new UnsupportedOperationException("Pending #7381: attempt profile and JSON ownership handoff"));
    }

    /**
     * Decode an ordinary response with the same effective profile used for the attempt.
     *
     * @param response raw upstream response
     * @param profile effective attempt profile
     * @return the normalized response publisher
     */
    public Mono<ShenyuAiResponse> decodeResponse(final AiUpstreamResponse response, final AiProviderProfile profile) {
        return Mono.defer(() -> {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(profile, "profile");
            return response.body().reduceWith(ByteArrayOutputStream::new, (output, buffer) -> {
                ByteBuffer readable = buffer.asReadOnlyBuffer();
                byte[] bytes = new byte[readable.remaining()];
                readable.get(bytes);
                output.writeBytes(bytes);
                return output;
            }).map(output -> responseMapper.map(response.statusCode(), response.headers(), readPayload(output.toByteArray()), profile));
        });
    }

    @Override
    public Flux<ShenyuAiStreamEvent> decodeStream(final AiUpstreamResponse response) {
        return Flux.error(new UnsupportedOperationException("Pending #7381: protocol framing and provider event handoff"));
    }

    /**
     * Map complete decoded events with subscription-local state.
     *
     * @param events complete decoded upstream events
     * @param profile effective attempt profile
     * @return the normalized event publisher
     */
    public Flux<ShenyuAiStreamEvent> mapDecodedStream(final Flux<AiProviderStreamEventMapper.DecodedEvent> events, final AiProviderProfile profile) {
        return Flux.defer(() -> {
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(profile, "profile");
            AiProviderStreamState state = new AiProviderStreamState();
            return events.takeUntil(event -> "done".equals(event.type()) && Objects.isNull(event.payload()))
                    .concatMap(event -> streamEventMapper.map(event, profile, state));
        });
    }

    /**
     * Resolve the effective profile without retaining per-call configuration.
     *
     * @param config resolved per-call overrides
     * @return the effective provider profile
     */
    protected AiProviderProfile resolveProfile(final AiProviderConfig config) {
        AiProviderProfile base = profileSupplier.get();
        AiProviderProfile override = config.getProfileOverride();
        if (Objects.nonNull(override) && Objects.nonNull(override.name()) && !Objects.equals(base.name(), override.name())) {
            final AiProviderProfile selected = AiProviderProfiles.getProfile(override.name());
            // Defined profiles replace vendor defaults; identity-only profiles have no capabilities and inherit them.
            base = Objects.isNull(selected.capabilities()) ? AiProviderProfiles.merge(base, selected) : selected;
        }
        return AiProviderProfiles.merge(base, override);
    }

    private AiProviderProfile resolveRequestProfile(final ShenyuAiRequest request, final AiProviderConfig config) {
        final AiProviderProfile profile = resolveProfile(config);
        AiProviderProfile override = config.getProfileOverride();
        if (Objects.nonNull(override) && Objects.nonNull(override.tokenFields())) {
            return profile;
        }
        final String model = requestAssembler.resolveEffectiveModel(request, config, profile);
        if (Objects.isNull(model)) {
            return profile;
        }
        Set<String> fields = profile.modelTokenFields().get(model);
        if (Objects.isNull(fields)) {
            return profile;
        }
        return new AiProviderProfile(profile.name(), profile.defaultEndpoint(), profile.operationPaths(), profile.authentication(),
                profile.modelMappings(), profile.requestFieldMappings(), profile.responseFieldMappings(), profile.requestDefaults(), fields,
                profile.usageMode(), profile.capabilities(), profile.modelTokenFields());
    }

    private JsonNode readPayload(final byte[] bytes) {
        if (bytes.length == 0) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode payload = OBJECT_MAPPER.readTree(bytes);
            return Objects.isNull(payload) || payload.isMissingNode() ? OBJECT_MAPPER.createObjectNode() : payload;
        } catch (IOException exception) {
            return TextNode.valueOf(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /**
     * Internal attempt handoff; the execution layer owns its lifetime.
     *
     * @param upstreamRequest prepared request for the transport
     * @param profile effective profile to reuse when mapping the response
     */
    public record PreparedRequest(AiUpstreamRequest upstreamRequest, AiProviderProfile profile) {

        @Override
        public String toString() {
            return "PreparedRequest[upstreamRequest=<redacted>, profile=<redacted>]";
        }
    }
}
