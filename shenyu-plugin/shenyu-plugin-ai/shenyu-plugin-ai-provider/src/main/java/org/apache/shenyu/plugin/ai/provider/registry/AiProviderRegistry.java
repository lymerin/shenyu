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

package org.apache.shenyu.plugin.ai.provider.registry;

import org.apache.shenyu.plugin.ai.api.spi.ShenyuAiProvider;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiRequest;
import org.apache.shenyu.plugin.ai.provider.AbstractOpenAiCompatibleProvider;
import org.apache.shenyu.plugin.ai.provider.config.AiProviderConfig;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.spi.ExtensionLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SPI discovery and selection, ready for later centralized Spring assembly.
 *
 * <p>Unknown names select a matching custom SPI extension when present, otherwise
 * the generic compatible provider. Custom extensions retain their profile identity.
 */
public final class AiProviderRegistry {

    private final ExtensionLoader<ShenyuAiProvider> extensionLoader;

    private final AiProviderNameResolver nameResolver;

    public AiProviderRegistry() {
        this(ExtensionLoader.getExtensionLoader(ShenyuAiProvider.class), new AiProviderNameResolver());
    }

    public AiProviderRegistry(final ExtensionLoader<ShenyuAiProvider> extensionLoader, final AiProviderNameResolver nameResolver) {
        this.extensionLoader = extensionLoader;
        this.nameResolver = nameResolver;
    }

    /**
     * Look up a provider using the agreed name resolution policy.
     *
     * @param configuredName configured provider name
     * @return the selected provider
     */
    public ShenyuAiProvider getProvider(final String configuredName) {
        return resolve(configuredName).provider();
    }

    /**
     * Discover providers through ShenYu SPI.
     *
     * @return the discovered providers by canonical name
     */
    public Map<String, ShenyuAiProvider> getProviders() {
        Map<String, ShenyuAiProvider> providers = new LinkedHashMap<>();
        extensionLoader.getExtensionClasses1().keySet().forEach(name -> providers.put(name, extensionLoader.getJoin(name)));
        return Map.copyOf(providers);
    }

    /**
     * Resolve both provider implementation and profile selection.
     *
     * @param configuredName configured provider name
     * @return the resolved value
     */
    public ProviderSelection resolve(final String configuredName) {
        AiProviderNameResolver.ResolvedProviderName name = nameResolver.resolve(configuredName);
        if (extensionLoader.getExtensionClasses1().containsKey(name.providerName())) {
            return new ProviderSelection(extensionLoader.getJoin(name.providerName()), name.profileName());
        }
        return new ProviderSelection(extensionLoader.getJoin("openai-compatible"), name.profileName());
    }

    /**
     * Attempt selection without credentials or mutable stream state.
     *
     * @param provider selected SPI implementation
     * @param profileName selected profile identity
     */
    public record ProviderSelection(ShenyuAiProvider provider, String profileName) {

        /**
         * Prepare a request with this selection's profile through the local configuration entry point.
         *
         * <p>Third-party SPI implementations remain discoverable, but their public configuration
         * handoff is pending. No configuration is stored on the shared provider instance.
         *
         * @param request normalized request
         * @param config per-attempt configuration
         * @return prepared request and its effective profile
         */
        public AbstractOpenAiCompatibleProvider.PreparedRequest prepareRequest(final ShenyuAiRequest request, final AiProviderConfig config) {
            if (!(provider instanceof AbstractOpenAiCompatibleProvider compatible)) {
                throw new UnsupportedOperationException("Pending #7381: third-party provider configuration handoff");
            }
            final AiProviderProfile override = config.getProfileOverride();
            final String name = Objects.isNull(override) || Objects.isNull(override.name()) ? profileName : override.name();
            final AiProviderProfile selectedOverride = Objects.isNull(override)
                    ? new AiProviderProfile(name, null, null, null, null, null, null, null, null, null, null, null)
                    : new AiProviderProfile(name, override.defaultEndpoint(), override.operationPaths(), override.authentication(), override.modelMappings(),
                            override.requestFieldMappings(), override.responseFieldMappings(), override.requestDefaults(), override.tokenFields(),
                            override.usageMode(), override.capabilities(), override.modelTokenFields());
            final AiProviderConfig selectedConfig = new AiProviderConfig(config.getProtocol(), config.getEndpoint(), config.getApiKey(), config.getHeaders(),
                    config.getQueryParameters(), config.getModel(), config.getTemperature(), config.getMaxTokens(), config.getStream(), selectedOverride);
            return compatible.prepareRequest(request, selectedConfig);
        }
    }
}
