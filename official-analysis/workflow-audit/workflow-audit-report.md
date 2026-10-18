# Workflow topology and E2E cache evidence for ShenYu #7781

## Safety and evidence scope

- All GitHub API requests in this audit explicitly used `gh api --method GET`. No official or fork write, workflow trigger, rerun, cancellation, cache mutation, branch update, PR change, or benchmark was performed.
- Local evidence was written only beneath `D:/Projects/shenyu-benchmark-7767/official-analysis/workflow-audit/`.
- Production source is pinned to official master `f00c1571238345f85be5cb6cce15703196b4b50a`. All 17 workflow files, setup-java composite action, disk cleanup script, and local Maven artifact restoration script were downloaded. Master does not contain `.github/scripts/maven-with-retry.sh`.
- The GET snapshot of PR #7781 is saved as `pr-7781.diff`. Its CI/E2E/native-SQL preimage blobs equal the downloaded master files.
- Historical window ends at **2026-10-18 02:45:44 UTC**. The complete parent-collected E2E population contains **376 workflow runs** in the preceding seven days. This audit sampled **10 successful runs / 20 jobs**, plus one supplemental storage job. Selection is purposive, not random: latest eligible successful run in each UTC date/event stratum from October 11 through 18, requiring a successful builder and case; prefer HTTP case; additionally two latest PRs cover same/different POM keys. One October 15 documentation-only candidate with skipped builder was excluded and recorded. A rerun on October 14 retained an earlier successful builder; actual job timestamps are preserved.
- `sample-selection.json` records the exact rule, examined candidates, and run identities. `e2e-sample-metrics.csv`/`.json` contain exact keys, head SHAs, step times, byte counts, download-entry counts and job links. Raw logs and compact cache excerpts are under `logs/`; job API responses are under `jobs/`.

## Production cache map

Definitions:

- **L** = `Linux-maven-${hashFiles('**/pom.xml')}` on current Ubuntu runners; path `~/.m2/repository`; restore prefix `Linux-maven-` where restore is configured.
- **J** = `setup-java-Linux-x64-maven-${hashFiles('**/pom.xml')}` on current Ubuntu/x64 runners; path resolved to `${HOME}/.m2/repository`; **no restore-key prefix**. This is the actual `actions/setup-java@v4` implementation, not the L namespace. An explicit dependency path of `**/pom.xml` and its default Maven pattern cover the same source set here. Exact hit suppresses setup-java post-save; a cache miss is saved after a successful job, best effort. Sources: [cache.ts](https://github.com/actions/setup-java/blob/v4/src/cache.ts), [cleanup-java.ts](https://github.com/actions/setup-java/blob/v4/src/cleanup-java.ts).
- **C** = workflow-wide concurrency `${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}`, `cancel-in-progress: true`. It serializes/replaces runs of the **same workflow and same PR/ref**; it does not serialize different PRs, different workflows, or all users of a cache key.
- **PR/P** = `pull_request` plus `push` to master. Event/path gates and job predicates remain in the source map; a listed restore executes only when its step/job is reached.

| Workflow / job | Event | Restore | Save | Key / prefix | Concurrency | Maven workload |
|---|---|---|---|---|---|---|
| ci / pr_build | PR only | explicit cache/restore v3 | none | L / Linux-maven- | C | selected modules with -am, or full release test reactor |
| ci / build_local_repo | master push only | explicit restore | none | L / Linux-maven- | C | full root release install, skip tests; upload current project Maven artifact |
| ci / test_group | master push only, after build_local_repo | explicit restore | none | L / Linux-maven- | C | six module-group test jobs; current project artifact replaces cached project artifacts |
| ci / compat_java21 | master push only, after build_local_repo | explicit restore | none | L / Linux-maven- | C | full tests on Java 21; current project artifact replacement |
| ci / registry_dependencies | PR/P with code scope | none | none | — | C | registration tests and dependency boundary package/tree checks |
| ci / rat_check | PR/P with license scope | none | none | — | C | Maven Apache RAT |
| e2e / build-docker-images | PR/P with resolved E2E cases | **none** | explicit cache/save v3 after build | L / no restore requested | C | root `clean install -Prelease,docker`, tests skipped; export images and current project Maven artifact |
| e2e / e2e-case | PR/P; after builder | explicit restore | none | L / Linux-maven- | C | selected examples install, then E2E case reactor; downloads both images and current project Maven artifact |
| e2e / e2e-storage | PR/P; after builder | explicit restore | none | L / Linux-maven- | C | storage E2E reactor through Compose script; downloads images, not project Maven artifact |
| it / build matrix | PR/P with resolved integration cases | setup-java Maven cache | successful-job setup-java post-save after miss | J / none | C | root release/docker install, all examples, selected integrated test image and test |
| it-k8s / build matrix | PR/P with ingress cases | setup-java Maven cache | same | J / none | C | root install, full IT subreactor, examples, K8s test |
| it-k8s-gateway-api / build | PR/P with matching paths | unified actions/cache v3 | automatic successful-job post-save after miss/fallback | L / Linux-maven- | **none** | root install, full IT subreactor, examples, K8s test |
| k8s-examples-http / k8s-examples-http | PR/P with resolved HTTP scope | setup-java Maven cache | successful-job post-save after miss | J / none | C | root release/docker install and examples install |
| native-sql-matrix / list-contract | PR/P or workflow_dispatch; Admin/SQL scope | setup-java Maven cache | same | J / none | literal native-sql-matrix-PR/ref group; cancel true | selected Admin mapper/service/controller contract tests with -am |
| docker-publish-ghcr / build | master push or v*.*.* tag, paths-ignore | explicit restore | none | L / Linux-maven- | **none** | root release package, tests skipped |
| docker-publish-dockerhub / dockerhub | release: released | explicit restore | none | L / Linux-maven- | **none** | root release package, tests skipped |
| CodeQL / analyze | PR/P on Java/workflow paths | explicit restore only on push | none | L / Linux-maven- | C | push: Maven package for manual extraction; PR: CodeQL no-build mode |
| wasm-fixtures / rebuild-and-test | PR/P with WASM/root-POM scope | none | none | — | C | script indirectly invokes WASM modules test with -am |

The remaining jobs perform scope resolution, Python-only SQL/container checks, aggregation, license-header inspection, or issue/PR administration and have no Maven cache operation. The machine-readable `complete-job-cache-map.json` and `.csv` enumerate **all 36 jobs**, including those jobs and all predicates, cache step text, events, concurrency settings, and direct/indirect Maven commands.

The only active explicit `actions/cache/save` step in the downloaded workflows is the E2E builder. Gateway API's unified cache action is another L-family writer. Setup-java jobs write the independent J family. Therefore: Maven cache users share repository storage, but L restore cannot consume J entries. Claiming that all Maven jobs share one interchangeable key family is false.

## E2E lifecycle and artifact roles

Current production builder lifecycle is **no restore request → root build/install → cache save attempt → export/upload images → stage/upload `org/apache/shenyu`**. Describing this as an observed cache "miss" is incorrect: no cache restore was requested.

Consumers need the builder to succeed. A failing builder causes normal downstream jobs to be skipped; the always-running aggregate reports failure. The cache save action is best effort: a reservation warning does not fail the builder. Both sampled reservation-conflict builders and their consumers succeeded.

`e2e-case` restores the L dependency cache, downloads `shenyu-images`, downloads `shenyu-local-maven-repository`, then executes `restore-shenyu-local-repo.sh`. That script moves any cached `~/.m2/repository/org/apache/shenyu` aside and copies the current builder's artifact in its place. It then builds selected examples from the current checkout. The dependency cache accelerates third-party resolution; the artifact delivers the current run's ShenYu project artifacts. **The artifact does not make the dependency cache redundant.** Conversely, the cache is not a reliable delivery mechanism for current project outputs.

`e2e-storage` downloads images but no project Maven artifact. The H2 script invokes `./mvnw -B -f ./shenyu-e2e/pom.xml -pl shenyu-e2e-case/shenyu-e2e-case-storage -am test`. Its dependency chain is `storage → e2e-engine → e2e-client → e2e-common`, all version `0.0.1-SNAPSHOT` and all built from the current independent E2E reactor. The E2E parent inherits Apache parent 21 rather than the ShenYu product root. This dependency chain has no product `shenyu-common:2.7.2-SNAPSHOT` or other product module dependency. Storage's cache use is for third-party dependencies; its product under test comes from the current image artifact.

Relevant pinned source links: [E2E builder and consumers](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/e2e-k8s.yml#L97), [artifact replacement](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/scripts/restore-shenyu-local-repo.sh#L20), [storage H2 command](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-storage/compose/script/e2e-h2-compose.sh#L44), [E2E independent parent/modules](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/shenyu-e2e/pom.xml#L23).

## What the production logs establish

Every one of the 10 sampled builders omitted restore and downloaded **2,912–2,943** Central entries. Their build steps took **139–194 seconds**. Five cache save attempts succeeded and uploaded **770,851,207–776,835,933 bytes**; five reported `Unable to reserve cache` and showed no cache content upload progress. Those warnings do **not** prove exactly whether the other entry already existed or a contemporaneous reservation was in progress; the literal action log is retained.

Of the 10 sampled HTTP consumers: **9 exact hits, 0 fallback hits, 1 miss**. This is an observed **9/10 sample restore success**, not the population's natural hit rate and not the counterfactual E2E builder hit rate. Conditioning selection on successful runs and sampling consumers after their builder has attempted to populate the cache biases interpretation. No builder-level restore hit-rate observation exists in unchanged production.

Historical exact cache sizes vary materially. October 13 restored **3,217,174,930 bytes / 34 seconds**, and October 14 restored **2,937,530,141 bytes / 40 seconds**. Current ~776 MB entries restore in **6–8 seconds** in these job-step timestamps. Applying the fork's 740.79 MiB size or 6.333-second restore to every official cache is unsupported. The L family has heterogeneous writer workloads; the log alone does not identify which historical writer created each large entry.

The 20 sampled official jobs log initial free disk of **89,163,796–90,091,372 KiB**, or **85.03–85.92 GiB**, and the production cleanup threshold is 20 GiB. These are measured pre-workload availability observations, not peak or minimum-during-build measurements. They support distinguishing adequate runner startup disk from repository cache storage pressure; the fork's measured 80.809 GiB minimum remains a separate controlled-workload measurement.

Even exact-hit HTTP consumers downloaded **172–368 Central entries**, because selected examples and the independent E2E test reactor extend the builder's dependency workload. **The fork's root-build warm-cache zero-download result does not extrapolate to zero downloads for the entire E2E graph.**

The October 15 example is decisive about delivery: builder [116507374093](https://github.com/apache/shenyu/actions/runs/38752107174/job/116507374093) saved `Linux-maven-fc8e3aaa...` at 16:50:33 UTC, while same-run consumer [116551565753](https://github.com/apache/shenyu/actions/runs/38752107174/job/116551565753) at 18:07:22 UTC reported no cache for both the exact key and prefix. The consumer still downloaded/restored the current local Maven artifact, downloaded 1,464 Central entries, and succeeded. This proves that a successful earlier save does not guarantee a later restore. The reason the entry was unavailable cannot be separated into eviction/deletion/version/scope causes from these logs alone; no such unobserved mechanism is asserted.

One supplemental current-master storage job [117544054807](https://github.com/apache/shenyu/actions/runs/39082825769/job/117544054807) restored the same L key and ran its independent E2E build, with 192 Central downloaded entries. It is not included in the 10 HTTP-consumer denominator.

The metric table below is generated from job API timestamps (integer-second step granularity). Central counts are log-entry counts, not unique artifacts or total HTTP requests. Save conflict is nonfatal and has no inferred upload bytes beyond the absence of transfer progress.

| Run / UTC date / ref | Role / job | Key hash prefix | Restore | Restore bytes / s | Build s | Central downloaded entries | Save / s / uploaded bytes |
|---|---|---|---|---|---:|---:|---|
| 38154958945 / 2026-10-11 / feat/agent-gateway-structured-result-filter | [builder 114515962136](https://github.com/apache/shenyu/actions/runs/38154958945/job/114515962136) | `981f2c96` | not requested | — / — | 159 | 2912 | saved / 8 / 770,851,207 |
| 38154958945 / 2026-10-11 / feat/agent-gateway-structured-result-filter | [case 114516749453](https://github.com/apache/shenyu/actions/runs/38154958945/job/114516749453) | `981f2c96` | exact | 770,851,207 / 6 | 37 | 345 | — |
| 38207071985 / 2026-10-12 / master | [builder 114672867391](https://github.com/apache/shenyu/actions/runs/38207071985/job/114672867391) | `981f2c96` | not requested | — / — | 139 | 2912 | reservation_conflict / 2 / 0 |
| 38207071985 / 2026-10-12 / master | [case 114673575684](https://github.com/apache/shenyu/actions/runs/38207071985/job/114673575684) | `981f2c96` | exact | 770,640,132 / 8 | 35 | 345 | — |
| 38342163370 / 2026-10-13 / master | [builder 115118648651](https://github.com/apache/shenyu/actions/runs/38342163370/job/115118648651) | `5efa3a0a` | not requested | — / — | 180 | 2917 | reservation_conflict / 3 / 0 |
| 38342163370 / 2026-10-13 / master | [case 115121324681](https://github.com/apache/shenyu/actions/runs/38342163370/job/115121324681) | `5efa3a0a` | exact | 3,217,174,930 / 34 | 34 | 172 | — |
| 38597023400 / 2026-10-14 / fix_IPV6AddressParseIncorrectly | [builder 116480146369](https://github.com/apache/shenyu/actions/runs/38597023400/job/116480146369) | `5efa3a0a` | not requested | — / — | 183 | 2917 | reservation_conflict / 3 / 0 |
| 38597023400 / 2026-10-14 / fix_IPV6AddressParseIncorrectly | [case 116480147633](https://github.com/apache/shenyu/actions/runs/38597023400/job/116480147633) | `5efa3a0a` | exact | 2,937,530,141 / 40 | 40 | 172 | — |
| 38752107174 / 2026-10-15 / fix_EmailAlertReportFail | [builder 116507374093](https://github.com/apache/shenyu/actions/runs/38752107174/job/116507374093) | `fc8e3aaa` | not requested | — / — | 187 | 2941 | saved / 10 / 776,512,922 |
| 38752107174 / 2026-10-15 / fix_EmailAlertReportFail | [case 116551565753](https://github.com/apache/shenyu/actions/runs/38752107174/job/116551565753) | `fc8e3aaa` | miss | — / 1 | 88 | 1464 | — |
| 38924255915 / 2026-10-16 / master | [builder 117053657213](https://github.com/apache/shenyu/actions/runs/38924255915/job/117053657213) | `fc8e3aaa` | not requested | — / — | 172 | 2941 | reservation_conflict / 4 / 0 |
| 38924255915 / 2026-10-16 / master | [case 117057704093](https://github.com/apache/shenyu/actions/runs/38924255915/job/117057704093) | `fc8e3aaa` | exact | 776,689,141 / 8 | 46 | 335 | — |
| 39048178784 / 2026-10-17 / master | [builder 117441088135](https://github.com/apache/shenyu/actions/runs/39048178784/job/117441088135) | `8a2c41e7` | not requested | — / — | 194 | 2943 | saved / 6 / 776,804,688 |
| 39048178784 / 2026-10-17 / master | [case 117442038822](https://github.com/apache/shenyu/actions/runs/39048178784/job/117442038822) | `8a2c41e7` | exact | 776,804,688 / 8 | 36 | 368 | — |
| 39082825769 / 2026-10-18 / master | [builder 117543416913](https://github.com/apache/shenyu/actions/runs/39082825769/job/117543416913) | `8a2c41e7` | not requested | — / — | 155 | 2943 | reservation_conflict / 3 / 0 |
| 39082825769 / 2026-10-18 / master | [case 117544054978](https://github.com/apache/shenyu/actions/runs/39082825769/job/117544054978) | `8a2c41e7` | exact | 776,804,688 / 6 | 45 | 368 | — |
| 39086285140 / 2026-10-18 / fix/7637-chain-subscription-lifecycle | [builder 117553473313](https://github.com/apache/shenyu/actions/runs/39086285140/job/117553473313) | `8a2c41e7` | not requested | — / — | 170 | 2943 | saved / 6 / 776,690,893 |
| 39086285140 / 2026-10-18 / fix/7637-chain-subscription-lifecycle | [case 117554205627](https://github.com/apache/shenyu/actions/runs/39086285140/job/117554205627) | `8a2c41e7` | exact | 776,690,893 / 8 | 40 | 368 | — |
| 39085490377 / 2026-10-18 / feat/7652-file-configuration | [builder 117551071036](https://github.com/apache/shenyu/actions/runs/39085490377/job/117551071036) | `2f4add9e` | not requested | — / — | 174 | 2943 | saved / 6 / 776,835,933 |
| 39085490377 / 2026-10-18 / feat/7652-file-configuration | [case 117551874920](https://github.com/apache/shenyu/actions/runs/39085490377/job/117551874920) | `2f4add9e` | exact | 776,835,933 / 6 | 39 | 368 | — |

## Natural-hit measurement boundary

The existing E2E workflow measures downstream restore outcomes after a save attempt. It does not measure the proposed new builder restore point. The seven-day workflow-run population of 376 does not itself expose cache hit values; only examined logs do. This report does not turn the 10 successful sampled cases into a repository-wide percentage. Prospective builder savings must therefore combine: measured fork same-workload exact-hit benefit; official existence/scope of eligible caches; measured official root downloads with no restore; cache sizes, churn and scope evidence; and explicit uncertainty about the unmeasured natural distribution at that new restore point.

No new benchmark or official workflow run is needed or authorized to close that observational gap here.

### Supplemental pre-build CI restores

Four CI `pr_build` jobs were selected by matching the same PR head SHAs as the October 11, October 15 and two October 18 E2E samples. This existing L-family restore runs before CI's own Maven test step. The actual observed results are **2 exact and 2 prefix fallback**, with no miss in this purposive four-job supplement. These are not added to the ten E2E-consumer denominator and do not constitute a population hit rate. October 15 CI started after its own E2E builder saved, so that one is not evidence of a pre-producer cache opportunity.

| CI run / job | Checked-out PR ref | Requested → restored hash prefix | Result | Bytes / step s | Restore start UTC |
|---|---|---|---|---|---|
| [38154959049 / 114515963233](https://github.com/apache/shenyu/actions/runs/38154959049/job/114515963233) | refs/pull/7549/merge | `981f2c96` → `95187325` | fallback | 770,259,741 / 6 | 2026-10-11T16:19:31Z |
| [38752106763 / 116508275837](https://github.com/apache/shenyu/actions/runs/38752106763/job/116508275837) | refs/pull/6457/merge | `fc8e3aaa` → `fc8e3aaa` | exact | 776,512,922 / 7 | 2026-10-15T16:59:27Z |
| [39086285148 / 117553474924](https://github.com/apache/shenyu/actions/runs/39086285148/job/117553474924) | refs/pull/7793/merge | `8a2c41e7` → `8a2c41e7` | exact | 776,804,688 / 7 | 2026-10-18T01:23:21Z |
| [39085490404 / 117551071469](https://github.com/apache/shenyu/actions/runs/39085490404/job/117551071469) | refs/pull/7673/merge | `2f4add9e` → `8a2c41e7` | fallback | 776,804,688 / 7 | 2026-10-18T01:10:35Z |

The strongest independent reuse example is [new PR #7793](https://github.com/apache/shenyu/pull/7793): GET metadata records creation at **01:23:00 UTC on October 18**. Its CI checks out `refs/remotes/pull/7793/merge`; the restore starts at 01:23:21 and records an exact match at 01:23:23 for the current master hash `8a2c41e7...`, restoring **776,804,688 bytes**. Its own E2E builder only saves at **01:26:23**, uploading **776,690,893 bytes**. The inventory's master entry ID **9094733854** was created at **October 17 16:13:12 UTC**, has the same `8a2c41e7...` key, and is **776,804,688 bytes**, exactly matching the earlier CI restore. The PR-local entry ID **9106318400** is created at 01:26:23 and has the different 776,690,893-byte size. This is real pre-build exact reuse on a just-opened PR. Attribution to the inherited master entry is a **triangulation** from documented scope, creation chronology and matching bytes; the action log itself prints no source-ref or cache-ID field.

PR #7673 supplies an actual different-POM-key fallback: CI requests `2f4add9e...` at 01:10:36 and restores current master's `8a2c41e7...` at the same 776,804,688-byte size. Its own E2E builder only publishes the `2f4add9e...` key at 01:13:38. This establishes that prefix fallback provides an existing production pre-build reuse opportunity when the PR's exact POM key is absent. October 11 similarly falls back `981f2c96...` to `95187325...`. No blanket exact-hit assumption is required to justify retaining the prefix.

All four CI commands use `-ntp`; zero `Downloaded from central` log entries are therefore **not** interpreted as zero remote requests. CI's changing selected-module test workload is not used as a controlled timing comparison for E2E root builds. Exact metrics and PR refs are in `ci-restore-sample-metrics.csv`/`.json`.

## Conditional-save conclusion and boundaries

**SAFE for the current E2E dependency-cache role:** add `id: restore-maven-cache` to the proposed restore step, then gate the existing save with `if: steps.restore-maven-cache.outputs.cache-hit != 'true'` while retaining its normal success condition, key, path and placement after successful build.

- Exact match: output true; skip save. This avoids re-compression/reservation work and also avoids creating a redundant PR-local entry when the exact hit came from master.
- Prefix fallback: output false; save the current exact key. The proposed condition does not suppress publication of a new POM-hash key.
- Miss: output is not true; save the current exact key after successful build.
- A prior restore/build failure still suppresses the save through the implicit success condition. Do not add `always()`.
- The current #7781 restore step has **no id** and the current save has **no condition**. Both the id and condition are needed for the intended optimization.

Safety does not mean guaranteed future availability or a complete dependency set. Same-key caches are immutable, exact matches can lack subsequently needed third-party dependencies, and retained entries can disappear before a consumer starts. Current project artifacts remain delivered through the dedicated artifact for cases; storage rebuilds its independent reactor and uses current images. Existing production already succeeds through a real cache miss without replacing that contract.

The production logs do not isolate the exact-hit save branch after the proposed restore, because production builders do not restore. The fork directly measures that redundant-save overhead. The production sample independently shows five nonfatal reservation-conflict save attempts costing 2–4 seconds, but those are not claimed to be exact-hit restore→save experiments.

## Recommendation contributed by this audit

Prefer **retry + E2E builder restore + conditional exact-hit save**, preserve fallback/miss saves and both existing artifact deliveries, and keep changes confined to the small existing CI path. Official evidence establishes a real no-restore producer gap, several eligible cache key generations, duplicate-ref storage consumption (see sibling cache audit), and useful dependency restoration at the actual consumer. It does not establish a population-wide builder hit percentage or a guaranteed 33-second official end-to-end saving.

Do not replace the dedicated current-project artifact with a cache, remove the E2E save entirely, conflate L and J namespaces, or disable PR saves solely from these samples. Retaining saves after fallback/miss preserves the existing producer→consumer acceleration and future canonical master entries. Assess cache pressure using the measured inventory and actual size heterogeneity, not only a 741 MiB theoretical entry.

PR #7781 currently wraps pr_build, integration build/examples/image/test, E2E builder/examples, and native-SQL list-contract. It does not wrap every Maven command in production; no recommendation to replace all Maven invocations follows from source presence alone.
