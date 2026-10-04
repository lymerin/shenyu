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

package org.apache.shenyu.plugin.ai.provider.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiStreamEvent;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiTokenUsage;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderErrorMapper;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderResponseMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiProviderStreamEventMapperTest {

    @Test
    void mapsContentToolsUsageAndCompletionWithoutLosingState() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AiProviderStreamEventMapper mapper = new AiProviderStreamEventMapper(new AiProviderResponseMapper(new AiProviderErrorMapper()));
        AiProviderStreamState state = new AiProviderStreamState();
        List<ShenyuAiStreamEvent> events = mapper.map(new AiProviderStreamEventMapper.DecodedEvent("message",
                json.readTree("{\"model\":\"chat\",\"choices\":[{\"index\":1,\"delta\":{\"content\":\"hello\","
                        + "\"tool_calls\":[{\"index\":2,\"function\":{\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],\"vendor\":true}")),
                null, state).collectList().block();
        assertEquals(List.of("content", "tool_call", "finish"), events.stream().map(ShenyuAiStreamEvent::type).toList());
        assertEquals("hello", events.get(0).content());
        assertEquals(1, events.get(0).payload().path("choice_index").asInt());
        assertEquals(2, events.get(1).payload().path("tool_index").asInt());
        assertEquals(true, events.get(1).payload().path("vendor").asBoolean());
        ShenyuAiStreamEvent usage = mapper.map(new AiProviderStreamEventMapper.DecodedEvent("message",
                json.readTree("{\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}")), null, state).blockLast();
        assertEquals("usage", usage.type());
        assertEquals(new ShenyuAiTokenUsage(4L, 2L, null), state.getUsage());
        state.update(null, new ShenyuAiTokenUsage(null, 3L, null), null, AiProviderProfile.UsageMode.CUMULATIVE);
        assertEquals(new ShenyuAiTokenUsage(4L, 3L, null), state.getUsage());
        state.update(null, new ShenyuAiTokenUsage(null, 2L, null), null, AiProviderProfile.UsageMode.INCREMENTAL);
        assertEquals(5L, state.getUsage().outputTokens());
        ShenyuAiStreamEvent done = mapper.map(new AiProviderStreamEventMapper.DecodedEvent("done", null), null, state).blockLast();
        assertEquals("done", done.type());
        assertEquals("chat", done.model());
        assertEquals(new ShenyuAiTokenUsage(4L, 5L, null), done.usage());
        assertEquals(state.getFinishReason(), done.finishReason());
        assertEquals("tool_call", state.getFinishReason().value());
        assertNull(done.payload());
        assertEquals(List.of(), mapper.map(new AiProviderStreamEventMapper.DecodedEvent("done", null), null, state).collectList().block());
    }
}
