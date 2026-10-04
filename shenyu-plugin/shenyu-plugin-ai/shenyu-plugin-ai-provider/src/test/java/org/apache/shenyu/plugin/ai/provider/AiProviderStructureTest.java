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

import org.apache.shenyu.plugin.ai.api.spi.ShenyuAiProvider;
import org.apache.shenyu.spi.ExtensionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Structural checks for provider SPI discovery and dependency boundaries.
 */
class AiProviderStructureTest {

    @ParameterizedTest
    @CsvSource({
        "openai, org.apache.shenyu.plugin.ai.provider.openai.OpenAiProvider",
        "openai-compatible, org.apache.shenyu.plugin.ai.provider.compatible.OpenAiCompatibleProvider",
        "deepseek, org.apache.shenyu.plugin.ai.provider.deepseek.DeepSeekProvider"
    })
    void discoversInitialProviders(final String name, final String implementation) {
        ShenyuAiProvider provider = ExtensionLoader.getExtensionLoader(ShenyuAiProvider.class).getJoin(name);
        assertEquals(implementation, provider.getClass().getName());
        assertEquals(name, provider.getName());
    }

    @Test
    void excludesSpringAiModelAndClientTypes() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.ai.chat.model.ChatModel"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.ai.openai.api.OpenAiApi"));
    }

    @Test
    void excludesHttpClientTypes() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.web.reactive.function.client.WebClient"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("okhttp3.OkHttpClient"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("reactor.netty.http.client.HttpClient"));
    }
}
