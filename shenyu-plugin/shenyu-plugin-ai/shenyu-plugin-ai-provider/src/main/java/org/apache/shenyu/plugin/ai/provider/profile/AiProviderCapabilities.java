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

package org.apache.shenyu.plugin.ai.provider.profile;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider capability declarations with an explicit unknown state.
 *
 * @param protocols supported protocols
 * @param operations supported operations
 * @param models model-specific capabilities
 */
public record AiProviderCapabilities(
        Set<String> protocols,
        Set<String> operations,
        Map<String, ModelCapabilities> models) {

    public AiProviderCapabilities {
        protocols = Objects.isNull(protocols) ? Set.of() : Set.copyOf(protocols);
        operations = Objects.isNull(operations) ? Set.of() : Set.copyOf(operations);
        models = Objects.isNull(models) ? Map.of() : Map.copyOf(models);
    }

    /**
     * Resolve model capabilities without assuming support for unlisted models.
     *
     * @param model configured model
     * @return declared capabilities or unknown support
     */
    public ModelCapabilities forModel(final String model) {
        return Objects.isNull(model) ? new ModelCapabilities(Support.UNKNOWN, Support.UNKNOWN, Support.UNKNOWN)
                : models.getOrDefault(model, new ModelCapabilities(Support.UNKNOWN, Support.UNKNOWN, Support.UNKNOWN));
    }

    /**
     * Model-specific feature declarations.
     *
     * @param stream stream support
     * @param tools tool calling support
     * @param jsonMode JSON mode support
     */
    public record ModelCapabilities(Support stream, Support tools, Support jsonMode) {
    }

    /**
     * Capability knowledge, independent of the eventual rejection policy.
     */
    public enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }
}
