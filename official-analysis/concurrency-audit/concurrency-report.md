# Official seven-day concurrency audit

## Scope and method

Window: **2026-10-11T02:45:44Z through 2026-10-18T02:45:44Z**, exactly **604,800 seconds**. Workflow IDs: ci=4847795, e2e=62441018, it=10341968. Native SQL and other workflows are excluded. No GitHub writes or Actions operations were performed. Every GitHub API call explicitly used `gh api --method GET`.

The fixed input `history/runs-7d.json` contains 1,119 applicable runs: 822 pull_request and 297 push. They represent 164 identified PRs. All 822 PR runs have evidenced identities: 5 from run metadata, 335 from commit-to-PR association plus matching source ref, and 482 from matching immutable source repository ID, exact head branch, master base, and the unique PR record open at original run creation. No PR number is inferred from an Issue number embedded in a branch name. The original PR cards and association queries are retained locally.

Job metadata for all non-action_required runs is available. The 57 action_required runs were not queried for execution jobs. There are **7,205 actual latest-attempt jobs** after excluding skipped/unassigned/zero-duration rows and **184 inherited job references** whose start precedes the latest attempt. There are **7,559 distinct available executions across retained attempts**, deduplicated by run, runner ID, timestamps and job name. These include 354 prior executions beyond actual latest-attempt execution. The 56 rerun runs have 69 prior attempts: records exist for 40; explicit GET requests for the remaining 29 returned empty job arrays. Empty historical responses are treated as unavailable, not as proof that no VM executed.

Three separate interval definitions are retained:

1. **Latest workflow envelope:** latest `run_started_at` to the last completed actual latest-attempt job. It includes scheduling/dependency gaps and is not VM usage.
2. **Latest actual runner jobs:** union of actual started/completed job intervals, with distinct PRs deduplicated across jobs and workflows.
3. **All available actual runner jobs:** adds retained historical executions and inherited references exactly once. This is the best available observed execution coverage, but remains a lower bound because 29 prior attempts are unavailable.

`run.updated_at` is **never used as a job completion timestamp**. All intervals are clipped to the fixed window. Zero-observed-PR segments are explicitly added at both window ends; every distribution below sums to 604,800 seconds. The cohort includes runs created inside the seven-day window; earlier-created carry-in runs are not collected. Thus zero means **no observed PR execution in this cohort**, not proof of repository-wide idleness.

## Distinct PR time distribution

Percentages use the full 604,800-second window, including zero-observed-PR time.

| Observed distinct PRs | Latest workflow envelope seconds (%) | Latest runner jobs seconds (%) | All available runner jobs seconds (%) |
| --- | ---: | ---: | ---: |
| 0 | 300,164 (49.630%) | 457,576 (75.657%) | 451,208 (74.604%) |
| 1 | 68,807 (11.377%) | 96,323 (15.926%) | 99,604 (16.469%) |
| 2 | 38,923 (6.436%) | 32,666 (5.401%) | 33,184 (5.487%) |
| 3 | 27,024 (4.468%) | 8,118 (1.342%) | 9,717 (1.607%) |
| 4+ | 169,882 (28.089%) | 10,117 (1.673%) | 11,087 (1.833%) |

| Basis, active time only | Weighted mean PRs | Weighted median PRs | Weighted p95 PRs | Observed peak PRs |
| --- | ---: | ---: | ---: | ---: |
| Latest workflow envelopes | 5.371 | 4 | 13 | 18 |
| Latest actual runner jobs | 1.647 | 1 | 4 | 9 |
| All available actual runner jobs | 1.665 | 1 | 4 | 9 |

The all-available execution series has 264 episodes with at least 2 PRs (53,988 seconds), 129 episodes with at least 3 PRs (20,804 seconds), and 63 episodes with at least 4 PRs (11,087 seconds). Their longest episodes are 3,246 / 1,820 / 1,531 seconds respectively. Thresholds overlap; their durations must not be added together. Exact 2 / exact 3 / 4+ buckets above are disjoint.

An actual 9-PR overlap is directly evidenced at **2026-10-17T14:23:56Z–14:24:00Z**: PRs 6457, 7088, 7387, 7661, 7666, 7668, 7691, 7699, 7776. There are 49 concurrent job intervals at that instant. The independent peak job-interval count in the observed PR execution series is 122; it is not 122 PRs. Latest workflow envelopes reach 18 PRs at **2026-10-14T15:42:35Z**. That envelope peak must not be described as 18 simultaneous running VMs or builds.

## Scheduling delay

Queue here is precisely **job.started_at minus job.created_at** for actual latest-attempt jobs. It is measured elapsed waiting, not a diagnosis of its cause. Approval/policy/scheduler/capacity effects are not individually identified. Queue values are not attributed to Maven cache, cache storage, or any account capacity setting.

| Job population | N | Mean s | Median s | p95 s | Max s |
| --- | ---: | ---: | ---: | ---: | ---: |
| All three workflows, PR only | 5,829 | 1,562.057 | 55 | 6,129 | 20,655 |
| All three workflows, PR + push | 7,205 | 1,319.857 | 7 | 5,617 | 20,655 |
| ci, PR only | 1,642 | 1,725.918 | 97.5 | 6,245 | 20,519 |
| e2e, PR only | 2,409 | 1,343.081 | 17 | 5,767 | 20,519 |
| it, PR only | 1,778 | 1,707.419 | 69.5 | 6,232 | 20,655 |

The long tail is present in original metadata. Example: it run **38409435337**, changes job **115337554891**, created **2026-10-13T13:58:50Z**, started **19:43:05Z**, completed **19:43:14Z**, giving **20,655 seconds**. This is a measured queue interval. Its cause is not identified from those timestamps. It is incorrect to substitute the fork benchmark's approximately 3-second scheduling delay for these official observations.

Workflow-attempt-start to first actual job start is separately retained in `summary.json`: median ci/e2e/it **42/20/20 seconds**, p95 **6,926/6,812/6,345 seconds**. This metric is different from per-job queue and does not use the original run creation time for reruns.

## Cancellation

| Workflow | All runs | All cancelled | PR runs | PR cancelled | PR cancelled rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| ci | 376 | 80 | 277 | 14 | 5.054% |
| e2e | 376 | 78 | 277 | 19 | 6.859% |
| it | 367 | 70 | 268 | 12 | 4.478% |
| Combined | 1,119 | 228 | 822 | 45 | 5.474% |

The combined all-run cancellation fraction is 228/1,119=20.375%; **it is not the PR cancellation rate**. Push cancellation is 183/297=61.616%. The data describes latest run conclusions; unavailable previous-attempt conclusions are not reconstructed.

Concrete same-PR/new-commit cancellation sequence, **PR #7569**:

- Head association query returned exactly one matching master-base PR for source repository ID **1332021642**, `yangjj-iso/shenyu:fix/7561-wasm-data-bridge`. The PR was open at both runs' creation. Raw response: `head-pulls-1332021642-567d29914033.json`.
- [Old e2e run 38892017319](https://github.com/apache/shenyu/actions/runs/38892017319), SHA `6a175afa75cb2157e3049f69d534b10048579d36`, created **2026-10-16T11:10:24Z**, conclusion cancelled.
- [New e2e run 38892448922](https://github.com/apache/shenyu/actions/runs/38892448922), different SHA `0b0f780fb02c8b65cdbf15b98764a9f430dac1ae`, created **11:14:20Z**, conclusion success.
- Old producer job **116943592458** records `##[error]The operation was canceled.` at **11:14:23.917Z**, completes **11:14:26Z**; all actual old-run jobs complete by **11:14:33Z**.
- The workflow read at the old head SHA contains `group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}` and `cancel-in-progress: true`. The new commit therefore selects the same configured PR group.

This directly verifies the new-commit/old-run-cancel sequence and the applicable concurrency configuration. The runner log does **not** name the cancellation actor; the automatic-concurrency cause is the configuration-based explanation, not a separately available server actor audit. Fifteen such close-timestamp candidate pairs are retained; they are not all labelled independently actor-proven cancellations.

## Evidence and compact deliverables

Compact derived tables suitable for the combined report:

- `concurrency-buckets-compact.csv`
- `concurrency-active-compact.csv`
- `queue-compact.csv`
- `cancellations-compact.csv`

Full local derivations: `summary.json`, `runs-audited.csv`, `jobs-audited.csv`, `all-available-job-executions.csv`, time-segment/episode CSVs, `attempt-coverage.json`, `new-commit-cancellation-candidates.csv`. Raw metadata/logs remain local. Reproduction: `analyze.py` reads saved files only and makes no network requests.

Collection used **1,264 explicit GET API calls**: 883 additional latest-job requests, 272 commit associations, 79 source-head PR lookups, 29 prior-attempt job requests, and one cancellation log. Existing transport-audit job files were reused. Maximum request parallelism was four. No additional collection is required for this report.

## Interpretation for #7781

Official history demonstrates multi-PR overlap and materially longer scheduling tails than the controlled fork experiment. The fork's four warm builds cannot establish official queue or simultaneous-save behavior. Neither workflow-envelope overlap nor VM overlap proves that cache save steps themselves overlap. These findings support keeping the implementation small and bounded; they do not attribute official queueing to cache storage or establish a need for a new cache architecture.
