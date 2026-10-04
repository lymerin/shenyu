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

import org.apache.shenyu.plugin.ai.api.model.ShenyuAiFinishReason;
import org.apache.shenyu.plugin.ai.api.model.ShenyuAiTokenUsage;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfile;

import java.util.Objects;

/**
 * Subscription-local mapping state; never held by a shared Provider instance.
 *
 * <p>Reported usage fields accumulate according to the provider usage mode.
 */
public final class AiProviderStreamState {

    private String model;

    private ShenyuAiTokenUsage usage;

    private ShenyuAiFinishReason finishReason;

    private boolean doneEmitted;

    /**
     * Get the subscription model.
     *
     * @return subscription model
     */
    public String getModel() {
        return model;
    }

    /**
     * Get the subscription usage.
     *
     * @return subscription usage
     */
    public ShenyuAiTokenUsage getUsage() {
        return usage;
    }

    /**
     * Get the subscription finishReason.
     *
     * @return subscription finishReason
     */
    public ShenyuAiFinishReason getFinishReason() {
        return finishReason;
    }

    /**
     * Update state according to the agreed usage timing policy.
     *
     * @param model event model
     * @param usage event usage
     * @param finishReason event completion reason
     * @param usageMode incremental or cumulative usage interpretation
     */
    public void update(final String model, final ShenyuAiTokenUsage usage, final ShenyuAiFinishReason finishReason, final AiProviderProfile.UsageMode usageMode) {
        if (Objects.nonNull(model)) {
            this.model = model;
        }
        if (Objects.nonNull(finishReason)) {
            this.finishReason = finishReason;
        }
        if (Objects.nonNull(usage)) {
            this.usage = Objects.isNull(this.usage) ? usage : new ShenyuAiTokenUsage(
                    merge(this.usage.inputTokens(), usage.inputTokens(), usageMode),
                    merge(this.usage.outputTokens(), usage.outputTokens(), usageMode),
                    merge(this.usage.totalTokens(), usage.totalTokens(), usageMode));
        }
    }

    boolean markDone() {
        if (doneEmitted) {
            return false;
        }
        doneEmitted = true;
        return true;
    }

    private Long merge(final Long previous, final Long observed, final AiProviderProfile.UsageMode mode) {
        if (Objects.isNull(observed)) {
            return previous;
        }
        return mode == AiProviderProfile.UsageMode.INCREMENTAL && Objects.nonNull(previous) ? previous + observed : observed;
    }
}
