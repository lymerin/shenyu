<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# AI Provider internal adapters (experimental)

This module implements the Provider-internal stage of #7383 with the existing
19 production types and three ShenYu SPI entries: `openai`, `openai-compatible`
and `deepseek`. Profiles express provider differences, mappers adapt payloads,
and the shared base organizes the components without HTTP clients or Spring AI.
These are initial profiles, not a closed list of vendors. Endpoint, authentication,
model aliases, field mappings and declared token keys belong to profiles. A vendor
with semantics that profiles cannot express may supply a ShenYu Provider SPI
implementation. The generic mappers are concrete components rather than subclass hooks.

| Component | Implemented responsibility |
| --- | --- |
| Configuration and profiles | Immutable header/query/map snapshots, copied JSON defaults, sparse profile overrides and provider capability declarations. |
| Registry | SPI discovery and selection, preserved profile identity and selection-bound request preparation. |
| Request pipeline | Model aliases, defaults, field mapping, configured token overrides, capability diagnostics, endpoint/query resolution, authentication and JSON POST assembly. |
| Response pipeline | Status/header preservation, copied provider payloads, model, partial usage, finish reason and error normalization, including non-JSON error bodies. |
| Complete-event pipeline | Content/tool/usage/finish events, retained provider fields and fresh state for each subscription. |

The internal `prepareRequest(request, config)`, profile-bound
`decodeResponse(response, profile)` and `mapDecodedStream(events, profile)` paths
are usable in component tests. The public SPI methods lacking per-call context
or complete-event inputs still fail explicitly with a pending-handoff error.
Gateway integration and end-to-end HTTP execution belong to the next stage;
retry, fallback and client response writing stay outside this module.

## Local adaptation rules

- Protocol `openai-chat` follows the parent task #7351; operation
  `chat-completions` follows the proposed operation naming pending shared contract
  finalization. Initial profiles also accept the earlier internal aliases `openai`
  and `chat.completions`, including operation paths. An undeclared operation is
  UNKNOWN rather than rejected by capability validation, but still needs an
  explicit profile path; the resolver never guesses a route.
- Initial profiles declare only the verified model capabilities listed below.
  Unlisted model and feature support remain UNKNOWN until a profile declares them;
  explicitly unsupported features are rejected, while UNKNOWN is provisionally
  permitted. These declarations do not claim a complete provider model catalog.
- OpenAI uses `/v1/chat/completions`, Bearer authentication and
  DeepSeek uses `/chat/completions` with Bearer authentication. Generic compatibility
  requires a configured endpoint. No default model is assumed.
- Effective model resolution has one implementation in
  `AiProviderRequestMapper.resolveEffectiveModel`, delegated by the Assembler for
  model-specific token selection. It selects configuration, normalized request,
  non-null raw payload, then profile default, and applies aliases once. The Mapper
  uses the same resolver to write the upstream model; no second alias pass follows
  default filling. Profile defaults fill missing fields. Configuration overrides
  temperature. A non-null client `stream` takes priority; otherwise configuration,
  the profile default, and the normalized boolean provide defaults in that order. Field mappings are
  simple canonical-to-provider names, without a JSONPath or strategy engine.
- Token fields declare what this attempt sends, not all aliases a vendor might
  accept. OpenAI defaults to `max_completion_tokens`; DeepSeek and generic
  compatibility default to `max_tokens`. A non-null configured `maxTokens` writes
  every declared key and removes undeclared canonical token keys, even when client
  values differ. For OpenAI,
  `{"max_tokens":100}` with configured `maxTokens=200` becomes
  `{"max_completion_tokens":200}`. Without configuration,
  raw client token fields remain unchanged, including differing values.
  If an extension profile leaves `tokenFields` null (unspecified), raw token fields
  also remain unchanged even with configured `maxTokens`; that limit cannot be
  applied without a field declaration, and the mapper does not guess a spelling.
  The shared normalized token value does not participate in Provider mapping.
  Token spelling is determined by the original payload, configured value and
  effective profile declaration; there is no reserved normalized-token argument.
  Profiles may declare other vendor keys without adding an enum or a vendor branch
  to the mapper. Declared and canonical token fields are handled only by this rule;
  general profile defaults and field renaming cannot change them. Unknown extension
  fields are retained; their names are not guessed to be token fields. Unconfigured
  client token spelling translation belongs to
  #7381 and is outside this stage.
- Token selection combines the implementation default and selected vendor profile,
  then a `modelTokenFields` rule matched against the final upstream model name after
  model configuration, defaults and aliases. An explicit `profileOverride.tokenFields`
  declaration has highest priority. Model rules use exact names; no prefix guessing,
  model catalog, request probing or error-driven spelling retries are introduced.
  OpenAI declares `max_tokens` for `gpt-3.5-turbo` and `gpt-3.5-turbo-0125`.
  Further verified model exceptions can be supplied as profile data, and an explicit
  two-key declaration still emits both.
- **Intentional behavior change:** OpenAI's old dual-key default is replaced by the
  documented preferred field. DeepSeek and generic compatibility send their declared
  `max_tokens` and remove `max_completion_tokens`. Configured overrides follow the
  effective profile rather than client spelling; unconfigured token fields are retained.
- `OpenAI` and `DeepSeek` resolve to their implementations. `ALiYun`, `Moonshot`
  reuse generic compatibility while retaining `aliyun` / `moonshot` profile identities.
  `OpenAPI` / `Open API` selects the generic profile. Other names first
  match registered SPI keys, otherwise fall back to compatibility; no speculative
  vendor endpoints are assigned. Switching to a defined named profile replaces the
  previous vendor defaults before applying sparse overrides. In particular, selecting
  `openai-compatible` clears an OpenAI/DeepSeek default endpoint and requires a configured
  endpoint. Undefined profile names inherit their selected
  implementation's defaults. Unregistered provider names use the generic implementation;
  registered extensions keep their own defaults. An identity is not a claim of vendor-specific support.
  Known aliases are case-insensitive. Custom SPI names are trimmed but otherwise
  match registration keys exactly, including case. An unmatched spelling uses the
  generic compatibility fallback, not a differently cased registered extension.
- Response JSON must consume the complete body. Non-JSON response bytes, including
  bodies such as `502 Bad Gateway` with a valid JSON prefix, are retained as a text payload; empty or whitespace-only
  bodies become an empty object. HTTP errors retain status, headers and payload,
  with a normalized error using the raw text or a status-bearing fallback message.
- Partial usage stays partial. Cumulative/unknown observations replace known
  fields; incremental usage sums them. Finish reasons retain their original
  values. Error `retryable` indicates 429 or 5xx classification; it does not
  schedule retries. Complete-event type labels are provisional; completion is
  a decoded `done` marker, never an SSE frame or client `[DONE]` output.

## Official provider differences (T2)

Checked on 2026-10-05. Model names are documentation examples, not defaults or a
hardcoded capability catalog. `openai-compatible` is a generic profile, not a vendor;
its defaults do not certify an arbitrary vendor's behavior.

| Item | OpenAI | DeepSeek | `openai-compatible` |
| --- | --- | --- | --- |
| Base URL and operation path | `https://api.openai.com/v1` + `/chat/completions`. [Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | Quickstart base `https://api.deepseek.com`, path `/chat/completions`. [Quickstart](https://api-docs.deepseek.com/), [API](https://api-docs.deepseek.com/api/create-chat-completion/). An official integration uses a `/v1` base. [Example](https://api-docs.deepseek.com/quick_start/agent_integrations/nanobot/) | Configured base URL; `/v1` placement is pending verification with the selected vendor. |
| Authentication | `Authorization: Bearer <key>`. [Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | `Authorization: Bearer <key>`. [Quickstart](https://api-docs.deepseek.com/) | Bearer is the profile default; actual vendor authentication is pending verification. |
| Real model examples | `gpt-4.1`, `gpt-4.1-mini`. [Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | Current examples: `deepseek-flash`, `deepseek-v4-pro`. [Quickstart](https://api-docs.deepseek.com/) | Pending verification with the selected vendor. |
| Token keys | `max_completion_tokens`; deprecated `max_tokens` is incompatible with o-series. Simultaneous acceptance of both keys is not established. [Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | Documents `max_tokens`; no documented `max_completion_tokens` guarantee. [Reference](https://api-docs.deepseek.com/api/create-chat-completion/) | Pending verification. The declared `max_tokens` is an initial profile convention, not a universal guarantee. |
| Streaming usage | `include_usage=true` requests a final totals chunk with empty `choices`; other usage values are null. Interrupted streams may lack totals. Whole-request totals, not increments. [Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | English reference: final content chunk contains whole-request totals regardless of `include_usage`; no separate usage-only chunk. Earlier chunks have null/absent usage. [Reference](https://api-docs.deepseek.com/api/create-chat-completion/) | Pending verification: return conditions and cumulative/incremental semantics are vendor-specific. |
| `finish_reason` | `stop`, `length`, `content_filter`, `tool_calls`, deprecated `function_call`; intermediate values may be null. [Schema](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events) | `stop`, `length`, `content_filter`, `tool_calls`, `insufficient_system_resource`, `aborted`; intermediate values may be null. [Reference](https://api-docs.deepseek.com/api/create-chat-completion/) | Pending verification; unfamiliar vendor values remain in the payload and raw reason. |

The documented DeepSeek token key confirms its single-key declaration. OpenAI uses
the documented preferred `max_completion_tokens`; simultaneous acceptance of both
keys is not assumed. A model-specific rule or explicit override can choose another
verified spelling. The generic profile needs an override when a vendor differs.
DeepSeek's [Chinese reference](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)
describes a separate usage-only chunk, unlike the current English page. The target
deployment's event shape remains pending verification; this stage does not enforce
either shape or implement SSE framing.

### Verified initial model declarations

These are exact-name profile data, not model-family guesses or a complete catalog.
Declarations describe documented request capabilities, not current model availability.
The generic compatible profile cannot inherit capability claims about an arbitrary vendor.

| Profile / model | Configured token key | Stream | Tools | JSON mode | Official evidence |
| --- | --- | --- | --- | --- | --- |
| OpenAI / `gpt-3.5-turbo`, `gpt-3.5-turbo-0125` | `max_tokens` | UNKNOWN | UNKNOWN | UNKNOWN | [Output limits](https://help.openai.com/en/articles/5072518-controlling-the-length-of-openai-model-responses), [documented snapshot request](https://developers.openai.com/api/docs/guides/batch) |
| OpenAI / `gpt-4.1` | `max_completion_tokens` default | SUPPORTED | SUPPORTED | SUPPORTED | [Model](https://developers.openai.com/api/docs/models/gpt-4.1), [JSON mode](https://developers.openai.com/api/docs/guides/structured-outputs) |
| OpenAI / `o1-mini` | `max_completion_tokens` default | SUPPORTED | UNSUPPORTED | UNKNOWN | [Model](https://developers.openai.com/api/docs/models/o1-mini); lack of Structured Outputs does not establish lack of JSON mode |
| DeepSeek / `deepseek-flash`, `deepseek-v4-pro` | `max_tokens` default | SUPPORTED | SUPPORTED | SUPPORTED | [Chat API](https://api-docs.deepseek.com/api/create-chat-completion/) |

The older OpenAI rule selects an officially documented spelling; it does not claim
that `max_completion_tokens` is rejected by every legacy deployment. The API reference
does not provide that model-by-model guarantee. Other old snapshots need verified
data or an explicit override before their token spelling is certified. No live calls
were made to confirm deployment-specific behavior.

Capabilities are intentionally partial. Unknown models remain permitted; documented
unsupported tools are rejected for `o1-mini`. JSON mode here establishes basic
`json_object` support, not every format variant, schema constraint, tool-choice option
or combination with thinking mode. Those refinements are outside this profile table.

**Intentional behavior change requiring maintainer review:** a feature explicitly
declared UNSUPPORTED is rejected before any upstream request is executed. The current
initial model table introduces this rejection only for `o1-mini` requests containing
nonempty `tools`; the legacy proxy forwarded such requests. No strict-mode switch
is introduced. UNKNOWN capability diagnostics do not reject requests, and unlisted
models do not acquire new model-capability rejections. This does not bypass existing
protocol, payload or endpoint validation.

The Assembler reports unsupported capabilities with `IllegalArgumentException`.
The integration layer must translate that capability-validation failure into a
client-facing error such as HTTP 400, rather than an unhandled HTTP 500. The Provider
does not choose the client status or write the response. This requirement applies
to capability failures, not an instruction to treat every `IllegalArgumentException`
from unrelated execution code as a client error.

### Endpoint and field-mapping constraints

Endpoints reject user info as well as fragments. Credentials must use the configured
authentication policy; rejection messages do not echo the credential-bearing URI.
Operation paths are validated before authentication query values are appended. URI
construction failures expose neither the full URI nor the original parser cause.
Header/query snapshots preserve key insertion order and the order of values within
each key. This preserves caller input order without promising HTTP wire ordering.
Applying HEADER or QUERY authentication requires a non-null parameter name; missing
names produce an `IllegalArgumentException` at the authentication boundary without
including credentials. BEARER uses `Authorization` directly.

Request field mappings perform one simultaneous rename from original source values;
they do not recursively apply target names as new sources. With `a -> b` and `b -> c`,
`{"a":1,"b":2}` becomes `{"b":1,"c":2}` regardless of mapping order. Each target
should have one source; precedence for multiple sources targeting the same field is
not part of the supported profile contract. When a target field already exists,
the mapped source value replaces it. For example, the same mappings applied to
`{"a":1,"b":2,"c":3}` produce `{"b":1,"c":2}`; the original `c` value is overwritten.

## Execution-layer input checklist (T6)

Provider configuration is already resolved when passed to `prepareRequest`.
Providers do not read global configuration, selectors, Proxy Key caches or request
fallback metadata. The table distinguishes verified legacy sources from pending
integration policies; it states Provider inputs rather than implementing another
issue's configuration or filtering modules.

| `AiProviderConfig` field | Source to provide for an attempt |
| --- | --- |
| `protocol` | No equivalent resolved field in the old proxy. Select the protocol at execution/protocol integration; provider identity is a separate concept. Mapping is pending #7381. |
| `endpoint` | Legacy `baseUrl`: optional global value, overlaid by Selector; the selected admin or dynamic fallback can override it. URI conversion belongs to the caller. |
| `apiKey` | Global then Selector; with proxy mode enabled, resolve `X_API_KEY` through the Selector-scoped Proxy Key cache before creating the primary attempt. Fallback credentials can override that resolved primary key. |
| `headers` | Resolved upstream headers, never a wholesale copy of client headers. The integration owner filters client credentials before this boundary. Explicit server-configured upstream headers remain valid inputs. General source mapping is pending #7381. |
| `queryParameters` | No general source established by the old proxy. Caller-provided query configuration and precedence are pending #7381. |
| `model` | Global then Selector, overlaid by the selected fallback. The Provider applies the resolved model and profile aliases. |
| `temperature` | Global then Selector, overlaid by the selected fallback; non-null configuration overrides the body. |
| `maxTokens` | Global then Selector, overlaid by the selected fallback; configured limits use the effective profile's token declaration. |
| `stream` | Global/Selector default. A non-null client value wins. Legacy fallback extraction has no independent stream setting; preserve the chosen request mode across fallback. |
| `profileOverride` | Sparse per-attempt profile overrides. Its name selects the vendor profile; token fields explicitly override model rules. Global/Selector/fallback source mapping remains integration work. |

Source evidence: [config resolver](../shenyu-plugin-ai-proxy/src/main/java/org/apache/shenyu/plugin/ai/proxy/enhanced/service/AiProxyConfigService.java),
[proxy execution](../shenyu-plugin-ai-proxy/src/main/java/org/apache/shenyu/plugin/ai/proxy/enhanced/AiProxyPlugin.java),
[legacy protocol adapter](../shenyu-plugin-ai-common/src/main/java/org/apache/shenyu/plugin/ai/common/protocol/OpenAiProtocolAdapter.java).
The resolver can consume an existing global Singleton value, but the current proxy
plugin handler does not populate it through global plugin updates. AI settings are
read from Selector + `DEFAULT_RULE`; rule matching still runs, but rule-handle AI
configuration is not consumed by this execution path.

Attempt timing for the future caller:

1. Resolve request-scoped primary inputs and Proxy Key credentials; select dynamic
   fallback before admin fallback, as the current proxy does.
2. Before each physical attempt, construct an immutable attempt-local config from
   the appropriate primary or fallback inputs and call `prepareRequest`. Never
   derive fallback configuration by mutating the previous mapped payload or a
   shared Provider. Retain the returned effective profile for response mapping.
3. On retry, use the primary inputs; on fallback, rebuild from the original
   normalized request with the fallback inputs. Fresh attempt configuration does
   not imply rereading live configuration: refresh/snapshot policy is pending
   #7381. Legacy retries reuse the primary API/request and only fallback rebuilds
   the request; per-attempt construction is a proposed integration requirement.

The first-chunk gate belongs in the execution layer. In the
[legacy executor](../shenyu-plugin-ai-proxy/src/main/java/org/apache/shenyu/plugin/ai/proxy/enhanced/service/AiProxyExecutorService.java),
`doOnNext` marks the first decoded upstream chunk before plugin SSE serialization
and client writing. It is not the arrival of HTTP headers or confirmed client
delivery. Retry/fallback is allowed only before that mark; subsequent failures
propagate. The new execution layer must settle the equivalent observation point
with #7381 and the framing owner. No gate or retry policy is implemented here.

Dynamic `fallbackConfig` currently allows client-controlled `baseUrl` and `apiKey`.
Retaining or restricting these inputs is an explicit #7381 integration decision;
supporting fallback does not settle client authority over destinations or credentials.

## Agreed Provider boundaries

| Decision | Provider responsibility | Pending integration responsibility |
| --- | --- | --- |
| Token adaptation | Select emitted fields from the effective vendor/model profile, apply configured values, remove other canonical token keys; preserve unconfigured tokens. | Supply resolved attempt configuration and verified vendor/model data. |
| Stream completion | Map an explicit local completion marker once, with final observed state; finish and usage remain separate events. | Agree the shared marker representation, framing owner and client encoding. |
| Profile selection | `registry.resolve(name).prepareRequest(request, config)` uses the selected identity and sparse overrides, retaining the effective profile for the attempt. | Finalize per-call configuration in the public SPI. |
| Headers | Generate vendor authentication and replace its header case-insensitively; consume resolved upstream headers. | Filter raw client credentials and gateway Proxy Key headers before this boundary. |
| Fallback metadata | Adapt the clean business payload and attempt configuration; never create fallback metadata in upstream JSON. | Extract top-level `fallbackConfig` into execution context and remove it before Provider invocation. |

The selected profile is not assigned to a shared SPI instance. Initial built-in
providers reuse the selection-bound internal preparation path. Third-party SPI
implementations remain discoverable; this local path explicitly reports a pending
configuration handoff for implementations without the internal configuration entry
point. It does not substitute a generic implementation for a registered extension.

The local `DecodedEvent("done", null)` is provisional, not a shared contract.
Data frames containing usage or `finish_reason` already emit those observations.
Only an explicit terminal marker emits completion, exactly once, and ends local
event consumption. Its usage is a snapshot, not a further increment. Missing
usage or finish information stays missing. EOF without a marker, errors and
cancellation do not manufacture successful completion. Vendor-specific EOF
semantics and the public marker type require joint agreement before integration.
No SSE framing or client `[DONE]` encoding is added here.

The Provider deliberately does not maintain a raw-client-header blacklist:
`Authorization`, `Proxy-Authorization`, `Cookie` and gateway Proxy Key headers from
clients must be filtered by the integration owner. Explicit upstream headers from
server configuration are different inputs and are not blindly removed. Query
authentication therefore preserves explicitly supplied upstream headers. This
states an input precondition, not proof that gateway filtering has been implemented.

Provider business payloads must already exclude gateway-owned top-level
`fallbackConfig`. Fallback configuration belongs in execution context. Both primary
and fallback mapping must start from the clean original business payload, paired
with the appropriate attempt configuration, rather than the raw body containing
backup credentials or a previously mapped vendor request. Other unknown vendor
fields remain intact. Extraction and filtering are integration acceptance conditions:
neither primary nor fallback upstream bodies may contain these backup credentials.
No filtering or fallback execution code is added to the Provider. The old config
service contains stripping logic, but the active direct path forwards the raw body;
the new Provider cannot inherit the old DTO's implicit unknown-field dropping.

## Protocol-aware plugin collaboration (proposed integration)

**Provider-only implementation scope:** consume the caller's resolved protocol,
validate it against the effective profile, and adapt provider-specific fields in
an owned copy of the original payload. Provider selection does not infer a protocol
from the provider or model name. The built-in profiles reject explicitly selected
Responses and Anthropic Messages protocols; they do not convert those formats to
Chat Completions. A missing protocol retains the existing internal Chat Completions
default, rather than triggering automatic detection. Request tests cover independent
protocol/provider selection and retain image content blocks, tool calls, tool
definitions and vendor fields through upstream body serialization.

Administration protocol selection, early request context, semantic prompt/text
operations and migration of other AI plugins are outside this Provider task. The
following describes their proposed handoff only; it does not expand implementation
scope or introduce shared interfaces.

The agreed design direction separates the client API format from the upstream
provider identity. For example, `openai-chat` with `deepseek` selects the Chat
Completions format and DeepSeek-specific adaptation; a provider name is not a
protocol detector. Explicit administration/route configuration takes priority.
Automatic detection remains future integration work and must not depend on model
names alone, since models can be aliased. The current Provider implementations
support Chat Completions; they do not implement Responses or Anthropic Messages.

The proposed request order is:

1. Resolve the client protocol before any AI plugin that needs protocol semantics.
   The execution/configuration owner makes the resolved identity available through
   request-scoped context; no shared Provider instance stores that context.
2. Protocol-aware plugins use narrowly scoped semantic operations, such as adding
   a system message or extracting text for inspection. The Protocol adapter owns
   the format-specific JSON access. These operations are design requirements,
   not newly declared SPI methods or a promise of a universal message DTO.
3. Keep the original JSON as the business payload and modify only the intended
   fields. A text projection for inspection must not replace the original request
   or discard images, tool calls and unknown vendor fields. The integration owner
   separately removes gateway metadata and resolves upstream credentials.
4. The execution layer selects the Provider and supplies attempt-local configuration
   and a clean payload. Provider adaptation, Transport HTTP execution, Provider
   response mapping and Protocol client encoding follow their existing boundaries.

The first integration stage forwards the same API format to a Provider declaring
support for it. Cross-protocol conversion is deferred. When conversion is designed,
client and upstream protocol identities need to be distinguished explicitly; a
provider name must not silently request a conversion. This stage adds neither a
converter nor shared context fields.

| Integration concern | Proposed owner / next step |
| --- | --- |
| Protocol/provider configuration and early request context | Execution/configuration owner with the shared API owner; settle availability before protocol-aware plugins run. |
| Format-specific semantic operations and raw JSON preservation | Protocol owner with affected AI plugin owners; agree the smallest usable operations. |
| Prompt, inspection and other AI behavior | Respective plugin owners; consume protocol operations instead of duplicating format switches. |
| Endpoints, authentication, model aliases and vendor field differences | Provider owner; continue using profiles and provider SPI extensions. |
| HTTP, cancellation and raw byte delivery | Transport owner; no prompt, model or vendor field mapping. |
| Retry/fallback and client error handling | Execution owner; retain the per-attempt and first-chunk boundaries described above. |

The existing [prompt plugin](../shenyu-plugin-ai-prompt/src/main/java/org/apache/shenyu/plugin/ai/prompt/AiPromptPlugin.java)
directly accesses `messages`; its future migration is other-module integration work.
This document does not claim that protocol-aware plugin execution is implemented
or that its public methods have been approved by maintainers. Stream termination
and decoded-error ownership remain deferred handoffs below.

Reference patterns reviewed on 2026-10-05:

- APISIX's [protocol registry](https://github.com/apache/apisix/blob/master/apisix/plugins/ai-protocols/init.lua)
  dispatches message operations to protocol adapters, and its
  [prompt decorator](https://github.com/apache/apisix/blob/master/apisix/plugins/ai-prompt-decorator.lua)
  consumes those operations. Borrow this collaboration pattern; text projections
  in individual adapters are not a lossless request representation.
- Envoy's [API protocol definition](https://www.envoyproxy.io/docs/envoy/latest/api-v3/type/ai/v3/api_protocol.proto)
  distinguishes the wire API format from the provider organization.
- Kong's [prompt decorator policy](https://developer.konghq.com/ai-gateway/policies/ai-prompt-decorator/)
  illustrates prompt enhancement as a separately composable capability.

## Experimental shared API boundary

The shared API comes from [#7397](https://github.com/apache/shenyu/pull/7397), pinned
to `ea800fc8d1dd1f68aa1354fb199c074ce00a8c09`. It is not merged into this branch.
The parent `ai-provider` profile is opt-in and accepts an external API module
through `ai-provider.api.module`. No shared contracts are redeclared here.
After the API is merged and registered normally, remove this temporary profile
wiring and register the Provider module in the regular module list.

Pending handoffs (initial questions were [raised on #7381](https://github.com/apache/shenyu/issues/7381#issuecomment-5981894535)):

- Per-call configuration entering `createRequest` without singleton mutation.
- Raw upstream bytes becoming complete events at the Protocol / Provider boundary.
- Explicit successful completion, incomplete EOF, interruption and cancellation
  semantics; the internal `done` marker is not a finalized inter-module type.
- Whether a decoded upstream error terminates the publisher before a subsequent
  `done` marker, and whether that termination belongs to the decoder or Provider.
- Resolved upstream header provenance and clean payload ownership, including removal
  of gateway fallback metadata before Provider invocation.
- Token value provenance in the shared DTO. Existing non-null configuration
  precedence is already defined and preserved; it does not await this handoff.

Internal overloads and nested records are provisional component boundaries,
not additions to the shared SPI. `PreparedRequest` retains the effective profile
for one attempt so response mapping can reuse it. The internal `DecodedEvent`
represents a complete event; it does not implement SSE framing. The ordinary JSON
decoder copies each buffer during delivery without changing its position or
retaining it afterwards. Final public ownership expectations remain experimental.
Prepared request bodies provide independent read-only buffers per subscription;
mapped response/event payloads are owned copies of their inputs, while the shared
Draft DTOs themselves remain shallowly mutable.
`AiProviderConfig.toString()` redacts all string, URI, header, query and profile
values, retaining only numeric and Boolean settings. Authentication and prepared
attempt descriptions are also redacted. The raw API key accessor and shared
upstream DTOs still contain credentials and must not be serialized for logs.

## Reproduce the structural verification

Run from the repository root in PowerShell. Prepare only the pinned API module
under `.archify` (Java sources remain unchanged); adjust its temporary parent path:

```powershell
git fetch upstream refs/pull/7397/head
New-Item -ItemType Directory -Force .archify | Out-Null
git archive --format=zip --output=.archify/provider-reference-api.zip ea800fc8d1dd1f68aa1354fb199c074ce00a8c09 shenyu-plugin/shenyu-plugin-ai/shenyu-plugin-ai-api
Expand-Archive -LiteralPath .archify/provider-reference-api.zip -DestinationPath .archify/provider-reference-api -Force
$apiPom = Join-Path (Get-Location) '.archify/provider-reference-api/shenyu-plugin/shenyu-plugin-ai/shenyu-plugin-ai-api/pom.xml'
$aiParent = (Join-Path (Get-Location) 'shenyu-plugin/shenyu-plugin-ai/pom.xml').Replace('\', '/')
$apiText = [IO.File]::ReadAllText($apiPom)
$apiText = $apiText.Replace('<artifactId>shenyu-plugin-ai</artifactId>', "<artifactId>shenyu-plugin-ai</artifactId>`n        <relativePath>$aiParent</relativePath>")
[IO.File]::WriteAllText($apiPom, $apiText, [Text.UTF8Encoding]::new($false))

$apiModule = '-Dai-provider.api.module=../../.archify/provider-reference-api/shenyu-plugin/shenyu-plugin-ai/shenyu-plugin-ai-api'
$providerModule = 'shenyu-plugin/shenyu-plugin-ai/shenyu-plugin-ai-provider'
.\mvnw.cmd -B -Pai-provider $apiModule -pl $providerModule -am test '-Dmaven.javadoc.skip=true' '-Drat.skip=true' '-Djacoco.skip=true'
.\mvnw.cmd -B -Pai-provider $apiModule -pl $providerModule apache-rat:check
.\mvnw.cmd -B -Pai-provider $apiModule -pl $providerModule -am dependency:tree '-Dscope=compile'
```

The reactor test run skips RAT over the workspace containing local design
artifacts; the second command runs RAT over the Provider module itself.
Checkstyle runs during validation. The structural suite verifies real ShenYu SPI
discovery for all three implementations and checks that `ChatModel`, `OpenAiApi`,
`WebClient`, `okhttp3.OkHttpClient` and `reactor.netty.http.client.HttpClient` cannot
be loaded. These checks guard dependency boundaries; they do not alone prove
AC7's prohibition on executing HTTP requests. Configuration tests cover redacted
descriptions, including credentials in the URI, headers, query and profile.
Focused component tests cover request assembly across all three profiles,
authentication/query merging, field preservation, UNKNOWN capability handling,
declared token overrides and unconfigured raw token preservation, ordinary
responses, errors and complete events. Attempt-level tests cover shared
Provider reuse, unchanged upstream buffer positions and fresh stream state on
resubscription, selected profiles, model rules and explicit token priority, stream
completion lifecycle, defined-profile switches, and HTML, JSON-prefixed text and
empty upstream error bodies. They make no end-to-end
gateway compatibility claim.
Six named, data-driven request baselines cover configured model/temperature,
client stream priority, configured versus unconfigured tokens, unconfigured
conflicting token keys, unknown-field round trips and absence of introduced
internal metadata.
