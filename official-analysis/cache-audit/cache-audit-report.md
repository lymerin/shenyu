# Cache inventory and conditional-save audit

## Safety and evidence anchor

Official source anchor: `f00c1571238345f85be5cb6cce15703196b4b50a`. Analysis cutoff: `2026-10-18T02:45:44Z`. API snapshots were collected shortly after this cutoff; HTTP Date is preserved in each response file. All GitHub API requests explicitly used `gh api --method GET`. No GitHub writes, workflow runs, permission changes, token changes, cache deletion, production edits, commits, or pushes were performed. Local writes were confined to this evidence directory.

The attachment and existing `benchmark-report.md` / `benchmark-results/validation-all.json` were read. The benchmark contains 19 valid runs. Its peak save-step overlap is 1 in every batch: simultaneous same-key save and first-writer competition were **not tested**.

## Measured from official repository

`GET /repos/apache/shenyu/actions/caches?per_page=100&page=1` returned HTTP 200 with `total_count=11` and all 11 entries. One page is complete; no pagination was omitted. Every returned entry is a Maven cache. Original response: `inventory-response.txt`; machine-readable data: `inventory.json`, `inventory.csv`, `inventory-summary.json`, `family-summary.json`.

| Metric | Snapshot |
| --- | ---: |
| Maven entries | 11 |
| Maven bytes, summed from entries | 10,505,273,201 |
| Maven GiB | 9.783798 |
| Distinct full keys | 6 |
| Distinct POM hash suffixes | 3 |
| Distinct refs | 8 |
| Distinct versions | 2 |
| Mean entry bytes | 955,024,836.45 |
| Median entry bytes | 776,720,680 |
| Min entry bytes | 206,202,546 |
| Max entry bytes | 2,042,712,499 |
| Surviving entries created in last 1 / 3 / 7 days | 11 / 11 / 11 |
| Surviving entries accessed in last 1 / 3 / 7 days | 11 / 11 / 11 |

The creation/access windows describe **currently surviving entries**, not all uploads or deletions during those windows. They do not prove historical churn or eviction.

Manual `Linux-maven-*`: 8 entries, 6,213,710,622 bytes, 3 keys, 8 refs. `setup-java-Linux-x64-maven-*`: 3 entries, 4,291,562,579 bytes, 3 keys, 3 refs. These are separate key/version families. The setup-java family must not be assumed interchangeable with the manual family.

The independent usage endpoint returned HTTP 200 with `active_caches_size_in_bytes=10505273201` and `active_caches_count=10`. Its byte total agrees with the inventory sum, but its count differs from the 11 returned entries. Both values are preserved; no undocumented consistency guarantee or cause is asserted. See `usage-response.txt` and `usage.json`.

## Direct proof: same key and version occupy separate entries across PR refs

All following rows have exactly the same full key:

`Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`

and exactly the same version:

`41d1ec4cf4a0f14e35637462cb542fdb4ca2f7d94ec87d84b439a859c4ebe98f`.

| Entry ID | Ref | Bytes |
| --- | --- | ---: |
| 9094733854 | refs/heads/master | 776,804,688 |
| 9106318400 | refs/pull/7793/merge | 776,690,893 |
| 9106264157 | refs/pull/7792/merge | 776,725,406 |
| 9105358453 | refs/pull/7785/merge | 776,720,680 |
| 9105347180 | refs/pull/7789/merge | 776,596,704 |
| 9105084294 | refs/pull/7788/merge | 776,640,438 |

Measured conclusion: these are **six independently stored entries**, not one globally deduplicated logical entry. They sum to 4,660,178,809 bytes; the five PR entries beyond master sum to 3,883,374,121 bytes. This is actual metadata proof, independent of the documentation. It does not prove that every one of those PRs would have found master at its earlier build time.

## Current quota rules and access limitation

`GET /repos/apache/shenyu/actions/cache/storage-limit` returned HTTP **402 Payment Required**, with the message requiring a valid payment method to access the service. The exact response is preserved in `storage-limit-response.txt` / `storage-limit.json`. Actual repository configured storage limit and billing budget are **UNAVAILABLE THROUGH THE CURRENT READ-ONLY ACCESS (HTTP 402)**. This is not reported as a successful quota read, and no permission/token workaround was attempted.

[GitHub current rules](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#usage-limits-and-eviction-policy): default capacity is 10 GB, configurable upward; older unused entries expire and storage pressure causes least-recently-accessed eviction. Increased capacity/budget changes the effective storage behavior. Consequently, the official configured limit cannot be hardcoded as 10 GB.

For an explicitly labelled **10 GiB illustration**, current bytes are 97.838% of that capacity. The fork seed size of 776,772,608 bytes fits 13.823 times, or 13 whole same-size entries, into 10 GiB. This is arithmetic, not the measured configured quota or an eviction experiment. Current inventory demonstrates substantial duplicated scope storage; it does not alone establish that cache eviction is the present CI failure cause.

## Cache semantics, checked against the exact benchmark action revision

Primary sources:

- [GitHub dependency cache scope/version rules](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache).
- [restoreImpl.ts at action revision 6f8efc29b200d32929f49075959781ed54ec270c](https://github.com/actions/cache/blob/6f8efc29b200d32929f49075959781ed54ec270c/src/restoreImpl.ts).
- [saveImpl.ts at that revision](https://github.com/actions/cache/blob/6f8efc29b200d32929f49075959781ed54ec270c/src/saveImpl.ts).
- [stateProvider.ts at that revision](https://github.com/actions/cache/blob/6f8efc29b200d32929f49075959781ed54ec270c/src/stateProvider.ts).

The corresponding source files are saved locally as `actions-cache-src__*.ts`.

`restoreImpl.ts:52-65` returns on complete miss without setting cache-hit. `:71-76` sets cache-hit to the string form of exact key equality after successful restore. Therefore exact hit is `'true'`, fallback is `'false'`, complete miss is unset/empty. The proposed `!= 'true'` condition admits both fallback and miss. Version compatibility is checked by the cache service before a cache key is returned; a same-name incompatible-version entry does not masquerade as an exact compatible restore.

An exact match restored from master is still an exact hit. The output does not identify the source ref. PR merge-ref caches are isolated from sibling PRs; default/base-branch caches are readable by PR runs. In a PR with no local cache, conditional save can therefore avoid creating a redundant PR-scoped copy after a successful master exact hit.

The condition is implicitly combined with the default `success()` check by Actions because it contains no status-check function. It does not turn a failed Maven build into a cache publisher. The restore step must be given the exact referenced id, `restore-maven-cache`; restore and save must retain identical path/version-compatible settings and current primary-key expression.

## Verdict: SAFE for the proposed E2E topology

| Restore result | cache-hit output | Proposed condition | Result |
| --- | --- | --- | --- |
| Exact, current ref | true | false | Skip compression/reservation for an immutable existing key |
| Exact, master/base ref | true | false | Reuse readable canonical cache; no redundant PR copy |
| Prefix fallback | false | true | Successful build still attempts publishing current exact key |
| Complete miss | empty | true | Successful build still attempts publishing current exact key |
| Build failure | any | default success() false | No save |

Official-source E2E consumer check:

- `e2e-k8s.yml:149-153` currently saves the full Maven repository; proposed restore/condition are evaluated against this producer.
- `:177-187` stages and uploads current `org/apache/shenyu` separately as `shenyu-local-maven-repository`.
- `e2e-case` downloads that artifact at `:326-329`, then applies it at `:331-332`. `restore-shenyu-local-repo.sh:29-32` moves aside cached ShenYu coordinates and installs the current artifact. Skipping exact-hit cache save does **not** remove that artifact delivery path.
- `e2e-storage` has no Maven artifact download. Its test Maven command uses the `shenyu-e2e` 0.0.1-SNAPSHOT reactor with `-am`. Its storage -> case -> engine -> client -> common dependencies are all within that reactor. The audited parents/common/client/engine POMs contain no dependency on product `shenyu-common:2.7.2-SNAPSHOT`. Current product behavior is exercised through the freshly built Docker images, which retain their existing producer/consumer path.

Cache remains an optimization rather than a delivery guarantee. The condition does not guarantee that an entry survives eviction until a later consumer, or that an existing exact cache contains every new remote artifact. Those limitations also apply to immutable same-key caches generally; consumers retain Maven resolution and current-project artifact delivery.

SAFE does **not** mean deleting all saves is safe. Fallback/miss must retain save, master cache creation must remain enabled, current-project artifact uploads/downloads must remain intact, and the step id must be wired correctly. A guard referencing a missing restore id would fail open and lose the optimization, so wiring validation is required.

## Recommendation

For the narrowly scoped #7781 E2E producer change, use **retry + restore + skip exact-hit save**, retaining save on fallback/miss and retaining existing artifact handling. Fork benchmark directly measured the avoidable exact-hit save attempt (mean 3 seconds in serial C; no repeated upload). Official inventory directly shows repeated same-key/version entries across PR refs. These support the small condition; they do not support a cache backend redesign, master-only saving, deleting all saves, or a claim that official cache churn/eviction has already been measured.

Natural cache hit rates and production log samples are being audited separately. This report does not manufacture those results from the inventory snapshot.
