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
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderCapabilities;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Capability validation against the actual upstream request.
 *
 * <p>Diagnostics distinguish explicit lack of support from missing declarations.
 */
public final class AiProviderCapabilityValidator {

    /**
     * Validate protocol, operation and final effective feature parameters.
     *
     * @param protocol selected protocol identifier
     * @param request effective mapped request
     * @param profile declared provider capabilities
     * @return unsupported and unknown capability diagnostics
     */
    public ValidationResult validate(final String protocol, final AiProviderRequestMapper.MappedRequest request, final AiProviderProfile profile) {
        final List<String> unsupported = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        final AiProviderCapabilities capabilities = profile.capabilities();
        checkDeclaration("protocol:" + protocol, protocol, capabilities.protocols(), unsupported, unknown);
        if (Objects.isNull(request.operation()) || !capabilities.operations().contains(request.operation())) {
            unknown.add("operation:" + request.operation());
        }
        final AiProviderCapabilities.ModelCapabilities model = capabilities.forModel(request.model());
        if (Objects.isNull(request.model()) || !capabilities.models().containsKey(request.model())) {
            unknown.add("model");
        }
        if (request.stream()) {
            checkFeature("stream", model.stream(), unsupported, unknown);
        }
        final JsonNode tools = field(request, profile, "tools");
        if (Objects.nonNull(tools) && !tools.isNull() && !tools.isEmpty()) {
            checkFeature("tools", model.tools(), unsupported, unknown);
        }
        final JsonNode format = field(request, profile, "response_format");
        if (Objects.nonNull(format) && !format.isNull() && !"text".equals(format.path("type").asText())) {
            checkFeature("jsonMode", model.jsonMode(), unsupported, unknown);
        }
        return new ValidationResult(List.copyOf(unsupported), List.copyOf(unknown));
    }

    private JsonNode field(final AiProviderRequestMapper.MappedRequest request, final AiProviderProfile profile, final String name) {
        return request.payload().get(profile.requestFieldMappings().getOrDefault(name, name));
    }

    private void checkDeclaration(final String diagnostic, final String value, final Set<String> declarations, final List<String> unsupported, final List<String> unknown) {
        if (declarations.isEmpty() || Objects.isNull(value)) {
            unknown.add(diagnostic);
        } else if (!declarations.contains(value)) {
            unsupported.add(diagnostic);
        }
    }

    private void checkFeature(final String name, final AiProviderCapabilities.Support support, final List<String> unsupported, final List<String> unknown) {
        if (support == AiProviderCapabilities.Support.UNSUPPORTED) {
            unsupported.add(name);
        } else if (Objects.isNull(support) || support == AiProviderCapabilities.Support.UNKNOWN) {
            unknown.add(name);
        }
    }

    /**
     * Capability diagnostics; assemblers currently permit unknown declarations provisionally.
     *
     * @param unsupported unsupported capabilities
     * @param unknown capabilities without known support
     */
    public record ValidationResult(List<String> unsupported, List<String> unknown) {
    }
}
