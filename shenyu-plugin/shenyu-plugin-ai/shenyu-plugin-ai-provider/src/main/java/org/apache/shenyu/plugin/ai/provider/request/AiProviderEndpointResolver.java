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

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoint and operation path resolution without network access.
 *
 * <p>Base paths are retained when appending operation paths.
 */
public final class AiProviderEndpointResolver {

    /**
     * Resolve the final upstream URI after authentication query placement.
     *
     * @param operation normalized operation
     * @param config resolved per-call configuration
     * @param profile effective provider profile
     * @param queryParameters query parameters including authentication
     * @return the final upstream URI
     */
    public URI resolve(final String operation, final AiProviderConfig config, final AiProviderProfile profile, final Map<String, List<String>> queryParameters) {
        final URI endpoint = Objects.isNull(config.getEndpoint()) ? profile.defaultEndpoint() : config.getEndpoint();
        if (Objects.isNull(endpoint) || Objects.isNull(endpoint.getHost()) || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                || Objects.nonNull(endpoint.getRawFragment()) || Objects.nonNull(endpoint.getRawUserInfo())) {
            throw new IllegalArgumentException("AI endpoint must be an absolute HTTP(S) URI without user info or a fragment");
        }
        final String operationPath = profile.operationPaths().get(operation);
        if (Objects.isNull(operationPath) || !operationPath.startsWith("/") || operationPath.contains("?") || operationPath.contains("#")) {
            throw new IllegalArgumentException("AI operation " + operation + " requires an absolute path mapping");
        }
        final String basePath = Objects.isNull(endpoint.getRawPath()) ? "" : endpoint.getRawPath().replaceAll("/+$", "");
        final String path = join(basePath, operationPath);
        final URI target = createUri(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
        final StringBuilder query = new StringBuilder();
        if (Objects.nonNull(endpoint.getRawQuery())) {
            for (String segment : endpoint.getRawQuery().split("&")) {
                final int separator = segment.indexOf('=');
                final String name = URLDecoder.decode(separator < 0 ? segment : segment.substring(0, separator), StandardCharsets.UTF_8);
                if (!queryParameters.containsKey(name)) {
                    if (!query.isEmpty()) {
                        query.append('&');
                    }
                    query.append(segment);
                }
            }
        }
        queryParameters.forEach((name, values) -> values.forEach(value -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(name)).append('=').append(encode(value));
        }));
        return query.isEmpty() ? target : createUri(target + "?" + query);
    }

    private URI createUri(final String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            // The parser's message and cause may contain authentication query values.
            throw new IllegalArgumentException("AI endpoint and operation path must form a valid URI");
        }
    }

    private String join(final String basePath, final String operationPath) {
        int overlap = 0;
        for (int index = 1; index <= Math.min(basePath.length(), operationPath.length()); index++) {
            if (basePath.endsWith(operationPath.substring(0, index)) && (index == operationPath.length() || operationPath.charAt(index) == '/')) {
                overlap = index;
            }
        }
        return basePath + operationPath.substring(overlap);
    }

    private String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
