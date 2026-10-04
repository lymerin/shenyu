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

import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-call configuration supplied by the execution layer; never stored on SPI singletons.
 * Credentials must not be logged. Header and query values are immutable snapshots.
 *
 * <p>Headers are resolved upstream inputs, not raw client headers. The caller owns client-header
 * filtering and gateway credential isolation; explicitly configured upstream credentials may remain.
 */
public final class AiProviderConfig {

    private final String protocol;

    private final URI endpoint;

    private final String apiKey;

    private final Map<String, List<String>> headers;

    private final Map<String, List<String>> queryParameters;

    private final String model;

    private final Double temperature;

    private final Long maxTokens;

    private final Boolean stream;

    private final AiProviderProfile profileOverride;

    public AiProviderConfig(
            final String protocol,
            final URI endpoint,
            final String apiKey,
            final Map<String, List<String>> headers,
            final Map<String, List<String>> queryParameters,
            final String model,
            final Double temperature,
            final Long maxTokens,
            final Boolean stream,
            final AiProviderProfile profileOverride) {
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.headers = snapshot(headers);
        this.queryParameters = snapshot(queryParameters);
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.stream = stream;
        this.profileOverride = profileOverride;
    }

    private static Map<String, List<String>> snapshot(final Map<String, List<String>> values) {
        if (Objects.isNull(values)) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((name, entries) -> result.put(name, List.copyOf(entries)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Get the resolved protocol.
     *
     * @return resolved protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Get the resolved endpoint.
     *
     * @return resolved endpoint
     */
    public URI getEndpoint() {
        return endpoint;
    }

    /**
     * Get the raw API key for upstream authentication; never log this value.
     *
     * @return the raw API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Get resolved upstream headers supplied after caller-side client-header filtering.
     *
     * @return resolved headers
     */
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * Get the resolved queryParameters.
     *
     * @return resolved queryParameters
     */
    public Map<String, List<String>> getQueryParameters() {
        return queryParameters;
    }

    /**
     * Get the resolved model.
     *
     * @return resolved model
     */
    public String getModel() {
        return model;
    }

    /**
     * Get the resolved temperature.
     *
     * @return resolved temperature
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Get the resolved maxTokens.
     *
     * @return resolved maxTokens
     */
    public Long getMaxTokens() {
        return maxTokens;
    }

    /**
     * Get the resolved stream.
     *
     * @return resolved stream
     */
    public Boolean getStream() {
        return stream;
    }

    /**
     * Get the resolved profileOverride.
     *
     * <p>A non-null profile name selects the attempt's vendor profile. Other values are sparse
     * explicit overrides; a token field declaration takes priority over model-specific rules.
     *
     * @return resolved profileOverride
     */
    public AiProviderProfile getProfileOverride() {
        return profileOverride;
    }

    /**
     * Describe numeric and Boolean settings while redacting all other configuration values.
     *
     * <p>URI, header, query and profile values can contain credentials, as can arbitrary string settings.
     *
     * @return a configuration description with potential credentials redacted
     */
    @Override
    public String toString() {
        return "AiProviderConfig{"
                + "protocol=[REDACTED], endpoint=[REDACTED], apiKey=[REDACTED], headers=[REDACTED]"
                + ", queryParameters=[REDACTED], model=[REDACTED], temperature=" + temperature
                + ", maxTokens=" + maxTokens
                + ", stream=" + stream
                + ", profileOverride=[REDACTED]}";
    }
}
