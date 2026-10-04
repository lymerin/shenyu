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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiFinishReason;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiStreamEvent;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiTokenUsage;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;
import org.apache.shenyu.plugin.ai.provider.response.AiProviderResponseMapper;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Map complete events; byte buffering and SSE framing remain at the protocol seam.
 *
 * <p>Unknown provider fields remain available in copied payloads.
 */
public final class AiProviderStreamEventMapper {

    private final AiProviderResponseMapper responseMapper;

    /**
     * Create the mapper.
     *
     * @param responseMapper response normalization
     */
    public AiProviderStreamEventMapper(final AiProviderResponseMapper responseMapper) {
        this.responseMapper = responseMapper;
    }

    /**
     * Map one complete decoded provider event to shared events.
     *
     * <p>The framing owner must supply an explicit normal-completion marker.
     * The provisional local {@code done} marker carries accumulated state snapshots,
     * not incremental usage. Finish reasons do not complete the stream; EOF, errors
     * and cancellation do not synthesize a completion marker.
     *
     * @param event complete event supplied by the agreed framing boundary
     * @param profile event mapping rules
     * @param state state owned by this subscription
     * @return the normalized event publisher
     */
    public Flux<ShenyuAiStreamEvent> map(final DecodedEvent event, final AiProviderProfile profile, final AiProviderStreamState state) {
        return Flux.defer(() -> {
            JsonNode payload = event.payload();
            if ("done".equals(event.type()) && Objects.isNull(payload)) {
                return state.markDone() ? Flux.just(mapped("done", null, state.getUsage(), state.getFinishReason(), state, null)) : Flux.empty();
            }
            JsonNode model = responseMapper.field(payload, "model", profile);
            ShenyuAiTokenUsage usage = responseMapper.mapUsage(payload, profile);
            state.update(Objects.nonNull(model) && model.isTextual() ? model.textValue() : null, usage, null,
                    Objects.isNull(profile) ? AiProviderProfile.UsageMode.UNKNOWN : profile.usageMode());
            List<ShenyuAiStreamEvent> events = new ArrayList<>();
            JsonNode choices = responseMapper.field(payload, "choices", profile);
            if (Objects.nonNull(choices) && choices.isArray()) {
                for (JsonNode choice : choices) {
                    mapChoice(choice, payload, profile, state, events);
                }
            }
            if (Objects.nonNull(usage)) {
                events.add(mapped("usage", null, state.getUsage(), null, state, payload.deepCopy()));
            }
            if (events.isEmpty()) {
                events.add(mapped(event.type(), null, null, null, state, Objects.isNull(payload) ? null : payload.deepCopy()));
            }
            return Flux.fromIterable(events);
        });
    }

    private void mapChoice(final JsonNode choice, final JsonNode payload, final AiProviderProfile profile,
                           final AiProviderStreamState state, final List<ShenyuAiStreamEvent> events) {
        JsonNode delta = responseMapper.field(choice, "delta", profile);
        JsonNode content = responseMapper.field(delta, "content", profile);
        if (Objects.nonNull(content) && !content.isNull()) {
            events.add(mapped("content", content.isTextual() ? content.textValue() : null, null, null, state, indexed(payload, choice, null)));
        }
        JsonNode tools = responseMapper.field(delta, "tool_calls", profile);
        if (Objects.nonNull(tools) && tools.isArray()) {
            for (JsonNode tool : tools) {
                events.add(mapped("tool_call", null, null, null, state, indexed(payload, choice, tool)));
            }
        }
        JsonNode function = responseMapper.field(delta, "function_call", profile);
        if (Objects.nonNull(function) && !function.isNull()) {
            events.add(mapped("tool_call", null, null, null, state, indexed(payload, choice, null)));
        }
        ShenyuAiFinishReason finish = responseMapper.mapFinishReason(choice, profile);
        if (Objects.nonNull(finish)) {
            state.update(null, null, finish, Objects.isNull(profile) ? AiProviderProfile.UsageMode.UNKNOWN : profile.usageMode());
            events.add(mapped("finish", null, null, finish, state, indexed(payload, choice, null)));
        }
    }

    private JsonNode indexed(final JsonNode payload, final JsonNode choice, final JsonNode tool) {
        ObjectNode copy = payload.deepCopy();
        if (choice.has("index") && !copy.has("choice_index")) {
            copy.set("choice_index", choice.get("index").deepCopy());
        }
        if (Objects.nonNull(tool) && tool.has("index") && !copy.has("tool_index")) {
            copy.set("tool_index", tool.get("index").deepCopy());
        }
        return copy;
    }

    private ShenyuAiStreamEvent mapped(final String type, final String content, final ShenyuAiTokenUsage usage,
                                       final ShenyuAiFinishReason finish, final AiProviderStreamState state, final JsonNode ownedPayload) {
        return new ShenyuAiStreamEvent(type, state.getModel(), content, usage, finish, ownedPayload);
    }

    /**
     * Internal decoded event input, not a replacement for the shared event contract.
     *
     * @param type upstream event type or completion marker
     * @param payload decoded provider JSON
     */
    public record DecodedEvent(String type, JsonNode payload) {
    }
}
