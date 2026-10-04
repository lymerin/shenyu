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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shenyu.plugin.ai.api.model.AiUpstreamRequest;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compose payload mapping, effective capability validation, authentication and URI resolution.
 *
 * <p>Explicitly unsupported capabilities are rejected. UNKNOWN is provisionally permitted,
 * avoiding unsupported assumptions about undocumented provider models.
 */
public final class AiProviderRequestAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProviderRequestMapper requestMapper;

    private final AiProviderCapabilityValidator capabilityValidator;

    private final AiProviderEndpointResolver endpointResolver;

    private final AiProviderAuthenticator authenticator;

    public AiProviderRequestAssembler(
            final AiProviderRequestMapper requestMapper,
            final AiProviderCapabilityValidator capabilityValidator,
            final AiProviderEndpointResolver endpointResolver,
            final AiProviderAuthenticator authenticator) {
        this.requestMapper = requestMapper;
        this.capabilityValidator = capabilityValidator;
        this.endpointResolver = endpointResolver;
        this.authenticator = authenticator;
    }

    /**
     * Delegate model resolution so profile selection and payload mapping share one rule.
     *
     * @param request shared normalized request
     * @param config resolved per-call configuration
     * @param profile effective provider profile
     * @return the final upstream model after alias resolution
     */
    public String resolveEffectiveModel(final ShenyuAiRequest request, final AiProviderConfig config, final AiProviderProfile profile) {
        return requestMapper.resolveEffectiveModel(request, config, profile);
    }

    /**
     * Assemble an upstream request without executing HTTP.
     *
     * @param request shared normalized request
     * @param config resolved per-call configuration
     * @param profile effective provider profile
     * @return the prepared upstream request
     */
    public AiUpstreamRequest assemble(final ShenyuAiRequest request, final AiProviderConfig config, final AiProviderProfile profile) {
        final AiProviderRequestMapper.MappedRequest mapped = requestMapper.map(request, config, profile);
        final String protocol = Objects.isNull(config.getProtocol()) ? AiProviderProfiles.OPENAI_PROTOCOL : config.getProtocol();
        final AiProviderCapabilityValidator.ValidationResult validation = capabilityValidator.validate(protocol, mapped, profile);
        if (!validation.unsupported().isEmpty()) {
            throw new IllegalArgumentException("Unsupported AI capabilities: " + validation.unsupported());
        }
        final AiProviderAuthenticator.AuthenticationParameters authentication = authenticator.authenticate(config, profile);
        final Map<String, List<String>> headers = new LinkedHashMap<>(authentication.headers());
        if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            headers.put("Content-Type", List.of("application/json"));
        }
        final byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(mapped.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize AI request payload", exception);
        }
        return new AiUpstreamRequest(endpointResolver.resolve(mapped.operation(), config, profile, authentication.queryParameters()),
                "POST", Collections.unmodifiableMap(headers), Flux.defer(() -> Flux.just(ByteBuffer.wrap(body).asReadOnlyBuffer())));
    }
}
