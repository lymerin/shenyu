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

import java.util.Locale;
import java.util.Objects;

/**
 * Single location for provider aliases and profile selection.
 */
public final class AiProviderNameResolver {

    /**
     * Resolve configured names and select an owned provider profile.
     *
     * @param configuredName configured provider name
     * @return the canonical provider and selected profile
     */
    public ResolvedProviderName resolve(final String configuredName) {
        String name = Objects.isNull(configuredName) ? "openai-compatible" : configuredName.trim();
        String normalized = name.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai", "open_ai" -> new ResolvedProviderName("openai", "openai");
            case "deepseek", "deep_seek" -> new ResolvedProviderName("deepseek", "deepseek");
            case "aliyun", "moonshot" -> new ResolvedProviderName("openai-compatible", normalized);
            case "openapi", "open api", "open_api", "openai-compatible", "" ->
                    new ResolvedProviderName("openai-compatible", "openai-compatible");
            default -> new ResolvedProviderName(name, name);
        };
    }

    /**
     * Resolved provider and profile identity after canonicalizing known aliases.
     *
     * @param providerName canonical SPI name
     * @param profileName profile selection
     */
    public record ResolvedProviderName(String providerName, String profileName) {
    }
}
