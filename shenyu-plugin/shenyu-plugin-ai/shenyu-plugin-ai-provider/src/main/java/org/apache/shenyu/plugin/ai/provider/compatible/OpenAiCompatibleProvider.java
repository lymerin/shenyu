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

package org.apache.shenyu.plugin.ai.provider.compatible;

import org.apache.shenyu.plugin.ai.provider.AbstractOpenAiCompatibleProvider;
import org.apache.shenyu.plugin.ai.provider.profile.AiProviderProfiles;
import org.apache.shenyu.spi.Join;

/**
 * Thin openai-compatible entry point backed by shared adaptation components.
 *
 * <p>Provider rules are supplied by its profile; common adaptation is inherited.
 */
@Join
public final class OpenAiCompatibleProvider extends AbstractOpenAiCompatibleProvider {

    public OpenAiCompatibleProvider() {
        super(AiProviderProfiles::openAiCompatible);
    }

    @Override
    public String getName() {
        return "openai-compatible";
    }
}
