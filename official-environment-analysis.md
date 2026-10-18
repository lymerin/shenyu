# ShenYu #7767: Official CI Evidence

This report explains why #7781 uses **retry + restore + conditional save**. Official CI logs establish the download failures and cache reuse opportunities; the [fork benchmark](benchmark-report.md) measures build time and runner disk usage.

## Recurring Central failures

A seven-day review of **198 failed-job logs** found at least **17 Maven Central transfer failures**: 5 connection resets and 12 HTTP 502 responses, across 14 commit SHAs and three dates.

| Workflow | Confirmed failures |
|---|---:|
| integrated-test | 7 |
| e2e-k8s | 6 |
| ci / registry_dependencies | 3 |
| native-sql-matrix | 1 |

The final helper was replayed offline against all 198 logs. It accepted **15 of the 17 Central-transfer failures** and rejected all **181 other failures**. The two remaining Central cases contained a mixed spring-release 401 error or an unsupported registry build-extension error.

The review also identified three E2E Maven calls with real transfer failures: MySQL storage, WebSocket, and Spring Cloud. These calls are now wrapped individually; the surrounding Compose startup, logging, and cleanup remain in place. Registry dependency checks remain outside this PR's retry coverage. Classification replay does not measure recovery after an actual retry.

Evidence: [failure records and job links](official-analysis/transport-audit/central-recovery-evidence.csv), [scan coverage](official-analysis/transport-audit/failure-coverage-summary.json), [final classifier replay](official-analysis/transport-audit/classifier-replay-final.json).

## Restore before the E2E build

At the audited master revision, the image builder ran **build → save**, without requesting restore. All ten sampled builders downloaded **2,912–2,943 Central entries** and took **139–194 seconds** for the build step.

Existing official jobs demonstrate reuse before a build:

| Job | Requested → restored key suffix | Result | Restored bytes |
|---|---|---|---:|
| [PR7793 CI](https://github.com/apache/shenyu/actions/runs/39086285148/job/117553474924) | `8a2c41e7` → `8a2c41e7` | exact | 776,804,688 |
| [PR7673 CI](https://github.com/apache/shenyu/actions/runs/39085490404/job/117551071469) | `2f4add9e` → `8a2c41e7` | fallback | 776,804,688 |

PR7793 was opened at 01:23:00 UTC. Its CI restored the exact key at 01:23:23, before its own E2E builder saved a PR-local entry at 01:26:23. The restored size matches the master entry created the previous day. Scope rules, timing, and size point to inheritance from master; the action log does not print the source entry ID.

The ten sampled downstream cases had **9 exact hits and 1 miss**. Four additional pre-build CI jobs had **2 exact hits and 2 fallbacks**. These are selected samples, not a repository-wide hit-rate estimate. The new builder restore position has no historical hit rate because the existing builder did not request restore.

Official sampled restores ranged from approximately **0.77–3.22 GB** and **6–40 seconds**. The fork's 6.3-second restore therefore describes its own 740.79 MiB archive. Downstream cases still downloaded additional dependencies after exact hits.

Evidence: [E2E sample metrics](official-analysis/workflow-audit/e2e-sample-metrics.csv), [pre-build CI metrics](official-analysis/workflow-audit/ci-restore-sample-metrics.csv), [sample selection](official-analysis/workflow-audit/sample-selection.json).

## Avoid duplicate exact-hit saves

The inventory snapshot returned **11 Maven cache entries totaling 10,505,273,201 bytes (9.78 GiB)**, across 8 refs and 6 full keys.

The following entries have the same `Linux-maven-8a2c41e7…` key and the same cache version, but occupy separate storage:

| Entry ID | Ref | Bytes |
|---|---|---:|
| 9094733854 | master | 776,804,688 |
| 9106318400 | PR7793 merge | 776,690,893 |
| 9106264157 | PR7792 merge | 776,725,406 |
| 9105358453 | PR7785 merge | 776,720,680 |
| 9105347180 | PR7789 merge | 776,596,704 |
| 9105084294 | PR7788 merge | 776,640,438 |

The five PR copies consume **3,883,374,121 bytes (about 3.88 GB)** beyond the master copy. PRs can restore master/base caches; a PR-created cache is scoped to its merge ref and is not shared with other PRs. See [GitHub cache scope rules](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache).

The fork measured **2–5 seconds** for redundant exact-key save attempts, with **zero new cache-content upload**. This supports the existing save step's new condition:

| Restore result | `cache-hit` output | Save after successful build |
|---|---|---|
| Exact hit | `true` | Skip |
| Prefix fallback | `false` | Publish the current exact key |
| Miss | empty/unset | Publish the current exact key |

The [cache action source](https://github.com/actions/cache/blob/6f8efc29b200d32929f49075959781ed54ec270c/src/restoreImpl.ts#L52) confirms these output values. Fallback and miss still create the new key; an inherited exact hit avoids another PR-local copy.

Mainline POM changes produced **15, 32, and 42 hash transitions** over 7, 14, and 30 days. Keeping fallback restore and publication of new exact keys accommodates those changes. These transition counts are not cache-upload counts.

Evidence: [full inventory](official-analysis/cache-audit/inventory.csv), [POM hash history](official-analysis/pom-churn/pom-changing-commits.csv).

## Resource conclusions

| Resource | Finding |
|---|---|
| Dependency downloads | Repeated cold downloads are an observed avoidable cost and the source of the reported reset/502 failures. |
| Runner disk | The fork's minimum sampled free disk was **80.8 GiB**, including cache restore and both image exports. Local disk exhaustion was not a bottleneck in that workload. |
| Cache storage | Cross-ref duplicate storage is confirmed. The configured quota/budget endpoint returned **HTTP 402**, so the 9.78 GiB snapshot does not establish quota exhaustion or eviction. |
| Cache save work | Exact-hit archive/reservation work took 2–5 seconds without new upload. Conditional saving removes that repeated work. |
| Current build outputs | Cases receive current ShenYu Maven artifacts and Docker images through artifacts. Storage builds its independent E2E reactor and uses current images. Skipping an exact-hit dependency-cache save preserves these delivery paths. |

The usage endpoint reported the same byte total as inventory but a count of 10 instead of 11. Both [responses](official-analysis/cache-audit/usage.json) are retained; inventory statistics use the 11 returned entries.

## Scope and source data

- Historical window: seven days ending **2026-10-18 02:45:44 UTC / 10:45:44 Beijing time**. Inventory is a snapshot collected afterward.
- Official workflow source: master [`f00c1571`](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/e2e-k8s.yml). Final helper results refer to PR commit `5a2f0470f`; its helper is unchanged from the locally replayed revision.
- Failure scanning covered 198 jobs from failed runs and reruns. Cancelled-run child failures and older-created runs were not exhaustively scanned, so 17 is a lower bound.
- The final implementation passed **21 local retry behavior cases**, the existing five-runner storage Compose script test, shell syntax checks, and Git whitespace checks. No complete Docker E2E result is claimed by this report.
- Additional source data: [workflow cache map](official-analysis/workflow-audit/complete-job-cache-map.csv), [window counts](official-analysis/history/window-summary.json), [storage-limit response](official-analysis/cache-audit/storage-limit-response.txt), and [fork benchmark](benchmark-report.md).
