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

import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Credential placement independent of AI payload transformations.
 *
 * <p>Returned maps and value lists are owned immutable snapshots.
 */
public final class AiProviderAuthenticator {

    /**
     * Prepare authentication headers and query parameters.
     *
     * @param config resolved configuration and credentials
     * @param profile effective authentication rule
     * @return headers and query parameters with authentication applied
     */
    public AuthenticationParameters authenticate(final AiProviderConfig config, final AiProviderProfile profile) {
        final Map<String, List<String>> headers = copy(config.getHeaders());
        final Map<String, List<String>> query = copy(config.getQueryParameters());
        final AiProviderProfile.Authentication authentication = profile.authentication();
        if (Objects.nonNull(config.getApiKey()) && authentication.mode() != AiProviderProfile.AuthenticationMode.NONE) {
            if ((authentication.mode() == AiProviderProfile.AuthenticationMode.HEADER || authentication.mode() == AiProviderProfile.AuthenticationMode.QUERY)
                    && Objects.isNull(authentication.parameterName())) {
                throw new IllegalArgumentException("AI " + authentication.mode() + " authentication requires a parameter name");
            }
            final String value = (Objects.isNull(authentication.prefix()) ? "" : authentication.prefix()) + config.getApiKey();
            if (authentication.mode() == AiProviderProfile.AuthenticationMode.QUERY) {
                query.put(authentication.parameterName(), List.of(value));
            } else {
                final String name = authentication.mode() == AiProviderProfile.AuthenticationMode.BEARER ? "Authorization" : authentication.parameterName();
                headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
                headers.put(name, List.of(value));
            }
        }
        return new AuthenticationParameters(Collections.unmodifiableMap(headers), Collections.unmodifiableMap(query));
    }

    private Map<String, List<String>> copy(final Map<String, List<String>> source) {
        final Map<String, List<String>> result = new LinkedHashMap<>();
        if (Objects.nonNull(source)) {
            source.forEach((key, values) -> result.put(key, List.copyOf(values)));
        }
        return result;
    }

    /**
     * Authentication output; values may contain credentials and must not be logged.
     *
     * @param headers upstream headers
     * @param queryParameters upstream query parameters
     */
    public record AuthenticationParameters(Map<String, List<String>> headers, Map<String, List<String>> queryParameters) {

        @Override
        public String toString() {
            return "AuthenticationParameters{headers=[REDACTED], queryParameters=[REDACTED]}";
        }
    }
}
