# #7767 官方 Maven Central 故障与 #7781 覆盖审计

## Safety / anchors

- 官方与 fork 均为只读；所有 GitHub API 调用显式使用 `gh api --method GET`。0 次官方写入、0 次 CI trigger/rerun/cancel、0 次正式源码修改。
- 时间锚点：2026-10-18T02:45:44Z；master：`f00c1571238345f85be5cb6cce15703196b4b50a`；PR #7781 head：`34781a0aea0df7ddd4df10962f814157bb33b304`。
- 分析产物仅写入本目录。压缩原始日志保留时间戳及完整终态信息。

## Measured from official repository：覆盖范围

父代理完整 7 日元数据共 1500 个四工作流 runs。取其中全部 138 个最新结论为 failure 的 runs，以及全部 55 个最新结论非 failure、但 run_attempt > 1 的 runs。193 个 runs 的全部 attempts 共 2668 jobs，327 个失败 jobs。排除 119 个纯聚合状态 jobs 与 10 个纯 artifact-download/无失败 step jobs，完整下载并扫描余下 198 个失败 job 日志。

这不是全仓所有失败 jobs 的穷尽普查：未穷尽 cancelled 且 attempt=1 的 runs 内已失败子 job，也未穷尽 created_at 在窗口前、但之后重跑的 runs。14/30 日只获得四工作流 failure-run 元数据及少量排除样本，不能把未扫描日志报告为零故障。

| 窗口 | 已确认 #7767 型故障 jobs | 说明 |
|---|---:|---|
| 7 日 | 至少 17 | 198 个失败 job 原始日志中的确定事件；5 reset、12 HTTP 502 |
| 14 日 | 至少 17 | 包含上述 7 日事件；未穷尽 8–14 日日志 |
| 30 日 | 至少 17 | 包含上述事件；未穷尽 15–30 日日志 |

在已扫描 198 日志内，没有找到终态 Central HTTP 429/503；另有 1 个 Central `Read timed out`，单独列出，不混入本 PR 的 reset/429/502 匹配范围。148 个日志归为测试/构建失败类，余下 32 个为其他失败；这些类别不是全部根因的人工逐案细分。

初次自动发现器复用了 helper 的 status-code 模式，因此最初只报 12 个。随后逐条审阅所有 Central transfer 终态，发现 5 个裸 `502 Bad Gateway`，修正发现器后得到 17。以下统计均为修正后的结果；这是扫描器漏计与正式 helper 漏判两个独立问题。

## 确定事件

| UTC 终态时间 | Workflow / job | Error | Job | 当前 classifier |
|---|---|---|---|---|
| 2026-10-13T20:33:26.5381455Z | integrated-test.yml / build (shenyu-integrated-test-apache-dubbo) | reset / DependencyResolutionException | [115416696261](https://github.com/apache/shenyu/actions/runs/38387172668/job/115416696261) | 接受 |
| 2026-10-13T20:33:28.5973693Z | e2e-k8s.yml / e2e-storage (shenyu-e2e-case-storage, e2e-mysql-compose) | reset / DependencyResolutionException | [115419077089](https://github.com/apache/shenyu/actions/runs/38374178970/job/115419077089) | 接受 |
| 2026-10-15T06:53:59.6656393Z | native-sql-matrix.yml / list-contract | 502 / PluginResolutionException | [116288521275](https://github.com/apache/shenyu/actions/runs/38694499875/job/116288521275) | 拒绝 |
| 2026-10-15T06:54:55.2991302Z | e2e-k8s.yml / build-docker-images | 502 / PluginResolutionException | [116288980192](https://github.com/apache/shenyu/actions/runs/38694499916/job/116288980192) | 接受 |
| 2026-10-15T07:44:51.9043048Z | e2e-k8s.yml / build-docker-images | 502 / DependencyResolutionException | [116302690862](https://github.com/apache/shenyu/actions/runs/38430032924/job/116302690862) | 接受 |
| 2026-10-15T15:58:13.0122200Z | e2e-k8s.yml / build-docker-images | reset / PluginResolutionException | [116492293535](https://github.com/apache/shenyu/actions/runs/38748896396/job/116492293535) | 接受 |
| 2026-10-15T16:08:10.7303576Z | integrated-test.yml / build (shenyu-integrated-test-grpc) | 502 / DependencyResolutionException | [116494460956](https://github.com/apache/shenyu/actions/runs/38748896377/job/116494460956) | 接受 |
| 2026-10-15T17:08:48.0531071Z | integrated-test.yml / build (shenyu-integrated-test-combination) | reset / DependencyResolutionException | [116507895457](https://github.com/apache/shenyu/actions/runs/38752107044/job/116507895457) | 接受 |
| 2026-10-15T18:35:44.1302169Z | e2e-k8s.yml / e2e-case (e2e-websocket-sync-compose, shenyu-e2e-case-websocket, :shenyu-example-spring-native-we... | reset / DependencyResolutionException | [116574960579](https://github.com/apache/shenyu/actions/runs/38755259885/job/116574960579) | 接受 |
| 2026-10-16T11:16:33.0481918Z | integrated-test.yml / build (shenyu-integrated-test-spring-cloud) | 502 / DependencyResolutionException | [116943487469](https://github.com/apache/shenyu/actions/runs/38891986408/job/116943487469) | 拒绝 |
| 2026-10-16T11:11:11.0790935Z | ci.yml / registry_dependencies | 502 / PluginResolutionException | [116943544972](https://github.com/apache/shenyu/actions/runs/38891993166/job/116943544972) | 拒绝 |
| 2026-10-16T11:11:55.3381198Z | integrated-test.yml / build (shenyu-integrated-test-http) | 502 / PluginResolutionException | [116943548215](https://github.com/apache/shenyu/actions/runs/38892001360/job/116943548215) | 接受 |
| 2026-10-16T11:12:13.3604103Z | integrated-test.yml / build (shenyu-integrated-test-grpc) | 502 / PluginResolutionException | [116943558563](https://github.com/apache/shenyu/actions/runs/38892009311/job/116943558563) | 接受 |
| 2026-10-16T11:11:15.5379535Z | ci.yml / registry_dependencies | 502 / PluginResolutionException | [116943614315](https://github.com/apache/shenyu/actions/runs/38892017092/job/116943614315) | 拒绝 |
| 2026-10-16T11:18:35.4898734Z | integrated-test.yml / build (shenyu-integrated-test-rewrite) | 502 / PluginResolutionException | [116945812524](https://github.com/apache/shenyu/actions/runs/38892697047/job/116945812524) | 接受 |
| 2026-10-16T11:17:36.2064165Z | ci.yml / registry_dependencies | 502 / PluginManagerException;ProjectBuildingException | [116946001348](https://github.com/apache/shenyu/actions/runs/38892689678/job/116946001348) | 拒绝 |
| 2026-10-16T11:24:36.5461089Z | e2e-k8s.yml / e2e-case (e2e-springcloud-sync-compose, shenyu-e2e-case-spring-cloud, :shenyu-examples-eureka,:sh... | 502 / PluginResolutionException | [116947697084](https://github.com/apache/shenyu/actions/runs/38892697082/job/116947697084) | 拒绝 |

17 事件分布：integrated 7、e2e 6、ci registry_dependencies 3、native list-contract 1；涉及 14 个不同 head SHA，UTC 10 月 13 日 2 个、15 日 7 个、16 日 8 个。这不是只观察到一次偶发失败。11 份日志被现有 classifier 接受；5 份裸 502 被拒绝；另 1 份同时含 spring-release 401，被严格仓库白名单拒绝。

## 必要修正：真实 Wagon 裸 502 格式

native list-contract job 116288521275，run 38694499875，2026-10-15T06:53:59.6656393Z：

```text
2026-10-15T06:53:59.6656393Z [ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent (default) on project shenyu: Execution default of goal org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent failed: Plugin org.jacoco:jacoco-maven-plugin:0.8.12 or one of its dependencies could not be resolved: Could not transfer artifact org.apache.maven.shared:file-management:jar:3.1.0 from/to central (https://repo.maven.apache.org/maven2): Transfer failed for https://repo.maven.apache.org/maven2/org/apache/maven/shared/file-management/3.1.0/file-management-3.1.0.jar 502 Bad Gateway -> [Help 1]
```

其 Help 类型为 `PluginResolutionException`。PR head 中 awk 原文离线重放该完整日志，退出码 1。源码只识别 `status code:` / `status:` / `Return code is:` 前缀，漏掉真实 `Transfer failed for https://... 502 Bad Gateway`。最小建议：在既有每条 Central artifact-transfer 分段内增加这一真实 502 reason 格式，并新增这条原始终态的一个必要 fixture。不要改成全日志搜索任何 502。

## 已有真实失败的遗漏调用

1. MySQL storage E2E：`shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-storage/compose/script/e2e-mysql-compose.sh:50`。master 与 PR head 为同一 blob `5e9d117d8c64eccff57432356619cb30aeef7b25`。job 115419077089 在父 shenyu-e2e 的 dependency collection 下载 apache:pom:23 时 reset；该 Maven invocation 未执行测试。
2. Websocket E2E：`shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-websocket/compose/script/e2e-websocket-sync-compose.sh:44`。master 与 PR head 为同一 blob `5f55416b89ed021d57cdd3c95cc0d9b1077e69af`。job 116574960579 的 common 3 tests 与 engine 2 tests 全通过，之后 websocket 模块在 Java-WebSocket:pom:1.5.3 依赖解析 reset，该模块测试未执行。
3. Spring Cloud E2E：`shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-spring-cloud/compose/script/e2e-springcloud-sync-compose.sh:63`。job 116947697084 在解析 surefire plugin dependency 时发生裸 502，不是测试断言失败。

这些 Run E2E Tests 矩阵路径由 e2e requirement 聚合消费。MySQL 与 websocket 两份原始完整 job 日志离线重放现有 classifier 均退出 0；Spring Cloud 需先补裸 502 格式。只包住实际 Maven 调用，不重跑整个 compose 脚本，也不能用 exec 替掉有后续日志、down、loop 或 EXIT cleanup 的外层脚本。

CI registry_dependencies 的第一条 Maven invocation（ci.yml:129）另有三次同型裸 502：116943544972、116943614315、116946001348，当前 PR 未覆盖。该 job 不在 master 的 requirement/build needs 中，不能未经 branch-protection 证据称其阻塞 required build 聚合。可列为有证据的邻接覆盖取舍；没有证据要求扩展 ci.yml:138 的第二条 invocation。第三例还属于 Unresolveable build extension / PluginManagerException，现有 classifier 的分组和 Help 白名单均不支持。不要为了修一个裸 502 而无说明地扩成通用 Maven 异常恢复框架。

## 排除项与严格分类边界

- Issue 引用的 pr_build job 110137674074（2026-09-30）实际是内部 `shenyu-register-client-beat:2.7.2-SNAPSHOT` 在 apache.snapshots 不存在。原始证据位于 `older-sample/job-110137674074.log.gz`；不计入 Central transient。
- job 116943487469 同一 artifact 同时报告 Central 502 与 spring-release 401。classifier 当前拒绝整次执行，不应说现有 PR 自动恢复所有 17 个事件。
- job 116475415968 的 Central `Read timed out` 是另一个实际传输故障；它不在当前 PR 明确匹配范围内，不能悄悄计成 reset，也不能据此泛化重试所有 Maven 失败。
- list-contract job 114681694979 是 Surefire `There are test failures`；117393141642 是 MavenWrapperMain 缺失；sql-matrix 的两个下游失败是状态聚合断言。这些均不是 Central 502。

## Same SHA / same run 的真实重跑对照

| 失败 job | 后续成功 job | 原 attempt → 后续 attempt | 失败完成至重跑开始 |
|---|---|---|---:|
| [116288521275](https://github.com/apache/shenyu/actions/runs/38694499875/job/116288521275) | [117419150886](https://github.com/apache/shenyu/actions/runs/38694499875/job/117419150886) | 1 → 2 | 55.50 h |
| [116288980192](https://github.com/apache/shenyu/actions/runs/38694499916/job/116288980192) | [117419159206](https://github.com/apache/shenyu/actions/runs/38694499916/job/117419159206) | 1 → 2 | 55.49 h |
| [116302690862](https://github.com/apache/shenyu/actions/runs/38430032924/job/116302690862) | [117419134270](https://github.com/apache/shenyu/actions/runs/38430032924/job/117419134270) | 5 → 6 | 54.65 h |
| [116492293535](https://github.com/apache/shenyu/actions/runs/38748896396/job/116492293535) | [117303194201](https://github.com/apache/shenyu/actions/runs/38748896396/job/117303194201) | 1 → 2 | 36.01 h |
| [116494460956](https://github.com/apache/shenyu/actions/runs/38748896377/job/116494460956) | [117303197346](https://github.com/apache/shenyu/actions/runs/38748896377/job/117303197346) | 1 → 2 | 35.85 h |
| [116507895457](https://github.com/apache/shenyu/actions/runs/38752107044/job/116507895457) | [117418790781](https://github.com/apache/shenyu/actions/runs/38752107044/job/117418790781) | 1 → 2 | 45.23 h |
| [116943487469](https://github.com/apache/shenyu/actions/runs/38891986408/job/116943487469) | [117418823578](https://github.com/apache/shenyu/actions/runs/38891986408/job/117418823578) | 1 → 2 | 27.10 h |

上述 7 对均使用相同 run / head SHA。成功 job 的完整原始日志另存为 recovered-job-*.log.gz，并可见原失败 artifact 后续从 Central 下载成功及 BUILD SUCCESS；不使用 fork benchmark 作为真实恢复证据。

人工重跑启动距离原失败 27.10–55.50 小时。这只证明之后同一 SHA、同一 job 恢复，不证明 Central 故障持续了这些小时，也不证明 10 秒后二次执行必定足够。没有在 10/20 秒粒度观测同一故障的恢复边界，因此没有证据把最多 3 executions 改为 2；保留 3 与 delay 10/20。

## 可追溯数据

- `failure-coverage-summary.json`：分母、排除项及范围限制。
- `scanned-jobs.csv` / `log-evidence.json`：198 日志的分类及终态原文/行号。
- `central-recovery-evidence.csv/json`：17 个确定事件与 same-run 成功对照。
- `classifier-replay.json`：从当前 PR diff 原文提取 awk，离线对原始日志重放，未执行 Maven。
- `jobs-<run>.json`：193 个 runs 的 all-attempt job metadata；`job-<id>.log.gz`：198 份失败日志。
- `pr-7781.json` / `pr-7781-files.json`：只读 API 得到的 PR metadata 与实际 diff。

## Unknown / limitation

- 未获得远端服务基础设施内部事件，不能断言具体 Central/CDN 节点故障原因。
- 未穷尽 14/30 日日志，不报告这些窗口完整故障率；当前确认的 17 均发生于 7 日窗口。
- 原始日志证明失败时与重跑时的离散状态，不给出秒级故障持续时间。
- PR matcher 离线接受日志不等于证明部署后重试必定成功。
