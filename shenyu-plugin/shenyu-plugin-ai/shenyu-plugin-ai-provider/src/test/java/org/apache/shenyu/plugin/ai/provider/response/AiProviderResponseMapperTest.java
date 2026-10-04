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

package org.apache.shenyu.plugin.ai.provider.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiError;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderResponseMapperTest {

    private final ObjectMapper json = new ObjectMapper();

    private final AiProviderErrorMapper errors = new AiProviderErrorMapper();

    private final AiProviderResponseMapper mapper = new AiProviderResponseMapper(errors);

    @Test
    void normalizesResponseAndSnapshotsProviderData() throws Exception {
        JsonNode payload = json.readTree("{\"model\":\"deepseek-chat\",\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3},"
                + "\"choices\":[{\"finish_reason\":\"tool_calls\"}],\"vendor\":{\"trace\":\"a\"}}");
        List<String> values = new ArrayList<>(List.of("trace-a"));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("x-trace", values);
        ShenyuAiResponse response = mapper.map(201, headers, payload, null);
        values.add("trace-b");
        ((ObjectNode) payload).put("model", "changed");
        assertEquals(201, response.statusCode());
        assertEquals(List.of("trace-a"), response.headers().get("x-trace"));
        assertEquals("deepseek-chat", response.model());
        assertEquals(8L, response.usage().inputTokens());
        assertNull(response.usage().totalTokens());
        assertEquals("tool_call", response.finishReason().value());
        assertEquals("tool_calls", response.finishReason().rawValue());
        assertEquals("a", response.payload().path("vendor").path("trace").asText());
        assertEquals("deepseek-chat", response.payload().path("model").asText());
        assertEquals("vendor_stop", mapper.mapFinishReason(json.readTree("{\"finish_reason\":\"vendor_stop\"}"), null).value());
    }

    @Test
    void classifiesErrorsAndRetainsStructuredCodes() throws Exception {
        JsonNode payload = json.readTree("{\"error\":{\"type\":\"rate_limit\",\"message\":\"slow down\",\"code\":{\"detail\":42}}}");
        ShenyuAiError error = mapper.map(429, Map.of(), payload, null).error();
        assertTrue(error.retryable());
        assertEquals("rate_limit", error.type());
        assertEquals("slow down", error.message());
        assertNull(error.code());
        assertEquals(42, error.payload().path("error").path("code").path("detail").asInt());
        assertTrue(errors.map(503, payload, null).retryable());
        assertFalse(errors.map(401, payload, null).retryable());
        ShenyuAiError textError = errors.map(502, json.getNodeFactory().textNode("<html>Bad Gateway</html>"), null);
        assertEquals("<html>Bad Gateway</html>", textError.message());
        assertEquals("upstream_error", textError.type());
        ShenyuAiError emptyError = errors.map(503, json.createObjectNode(), null);
        assertEquals("Upstream HTTP error 503", emptyError.message());
        assertEquals("upstream_error", emptyError.type());
    }
}
