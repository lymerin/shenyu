# ShenYu #7767 Official Environment Analysis

**结论：#7781 采用 retry + E2E builder restore + conditional save。exact-hit save 优化判定为 SAFE。当前 PR 尚不应转 Ready：需要补上真实裸 `502 Bad Gateway` 格式，以及三个已有同型故障的 E2E Maven 调用。**

本报告仅分析，没有实施上述修正。生产源码锚点为 master `f00c1571238345f85be5cb6cce15703196b4b50a`；PR head 为 `34781a0aea0df7ddd4df10962f814157bb33b304`；历史窗口截止 **2026-10-18 02:45:44 UTC（北京时间 10:45:44）**。库存是随后取得的单次 GET 快照，不是整周库存历史。

证据标签：**Official measured**＝官方源码/API/日志实际观察；**Fork measured**＝已完成的受控 benchmark；**Documented**＝GitHub 文档及 action 源码；**Inference**＝由注明的证据推导；**Unknown**＝现有数据没有回答。未知值不填零。

## Safety

- `apache/shenyu` **READ ONLY；0 official writes；0 official Actions triggers**。所有 GitHub API 请求均显式指定 `gh api --method GET`；另只读 fetch 固定 master 的 Git 对象。
- 没有 dispatch、rerun、cancel、删除 cache、评论、review、修改 PR、修改权限、切换 token 或修改官方设置；没有 push 到 `7767`。
- 没有修改任何生产代码、workflow 或既有 benchmark 数据；没有重新运行 benchmark。离线 classifier 重放只读历史日志，不执行 Maven。
- 本次新增报告及精简证据只存入 fork 的 `bench-7767`；完整原始日志/API 页保留于本地 `official-analysis/`。本次证据包与原始日志的范围见文末。

## Current production workflow

**Official measured：E2E builder 当前流程为“没有 restore → 构建 → save”。不存在 restore 请求，所以不能把该阶段记录成 cache miss，更不能说其自然命中率为 0%。** master 尚无 `.github/scripts/maven-with-retry.sh`；重试脚本来自待审的 #7781。

源码：[ci.yml](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/ci.yml)、[e2e-k8s.yml](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/e2e-k8s.yml)、[integrated-test.yml](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/integrated-test.yml)、[native-sql-matrix.yml](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/workflows/native-sql-matrix.yml)。

下表中 L＝`Linux-maven-${hashFiles('**/pom.xml')}`，J＝`setup-java-Linux-x64-maven-${hashFiles('**/pom.xml')}`；所有 Maven repository path 均为 `~/.m2/repository`。PR/P 表示 `pull_request` / push master，并受各 workflow 的路径/矩阵条件限制。C 表示 workflow＋PR number 或 ref 分组，`cancel-in-progress: true`。PR 写入 merge ref，push master 写入 master；同 ref 的 workflow 互通、PR 可读 master、不同 PR 的私有 merge-ref entry 不互通。

| Workflow / job | Event / workload | Restore | Save | Key / restore prefix | Concurrency / scope |
|---|---|---|---|---|---|
| ci / pr_build | PR；选定模块或完整测试 | explicit restore v3 | 无 | L / `Linux-maven-` | C；PR/base/master |
| ci / build_local_repo | master；root install | explicit restore | 无 | L / 同上 | C；master |
| ci / test_group、compat_java21 | master；分组测试、Java 21 | explicit restore | 无 | L / 同上 | C；master；另收当前项目 artifact |
| ci / registry_dependencies、rat_check | PR/P；注册边界测试、RAT | 无 | 无 | — | C |
| e2e / build-docker-images | PR/P；root release/docker install | **无** | explicit save v3 | L / 未请求 restore | C；PR 或 master |
| e2e / e2e-case | PR/P；examples＋case reactor | explicit restore | 无 | L / `Linux-maven-` | C；另收两个当前 run artifacts |
| e2e / e2e-storage | PR/P；storage reactor | explicit restore | 无 | L / 同上 | C；另收当前 images |
| integrated-test / build matrix | PR/P；root、examples、IT image/test | setup-java Maven | 成功后 post-save | J / 无 | C；独立 J family |
| integrated-test-k8s / build matrix | PR/P；root、IT、examples、K8s | setup-java Maven | 成功后 post-save | J / 无 | C；独立 J family |
| integrated-test-k8s-gateway-api / build | PR/P；root、完整 IT、examples、K8s | unified cache v3 | 成功后 post-save | L / `Linux-maven-` | **无 concurrency group** |
| k8s-examples-http / 同名 job | PR/P；root、examples | setup-java Maven | 成功后 post-save | J / 无 | C |
| native-sql-matrix / list-contract | PR/P/dispatch；Admin contracts | setup-java Maven | 成功后 post-save | J / 无 | native-sql-matrix＋PR/ref；cancel true |
| docker-publish-ghcr / build | master/tag；root package | explicit restore | 无 | L / `Linux-maven-` | 无 group |
| docker-publish-dockerhub / dockerhub | release；root package | explicit restore | 无 | L / 同上 | 无 group |
| CodeQL / analyze | PR/P；push Maven package | 仅 push restore | 无 | L / 同上 | C |
| wasm-fixtures / rebuild-and-test | PR/P；WASM reactor tests | 无 | 无 | — | C |

完整的 **17 个 workflow / 36 个 job**（含不使用 Maven/cache 的 jobs）、精确 events、条件、命令和 action 配置见 [complete-job-cache-map.csv](official-analysis/workflow-audit/complete-job-cache-map.csv) 及 [workflow audit](official-analysis/workflow-audit/workflow-audit-report.md)。L-family 的 writer **不只有 E2E**：Gateway API 的 unified cache 也写入该 family；不能把所有历史 L 缓存认作同一个 root-build workload。setup-java 的 key/version 与 L 不同，不能互换恢复。

### E2E cache 与 artifacts 各自的职责

**Official measured：** builder 成功后保存第三方依赖缓存，导出并上传 `shenyu-images`，再上传当前 `org/apache/shenyu` 的 `shenyu-local-maven-repository` artifact。case 下载两种 artifacts；[restore-shenyu-local-repo.sh](https://github.com/apache/shenyu/blob/f00c1571238345f85be5cb6cce15703196b4b50a/.github/scripts/restore-shenyu-local-repo.sh#L20) 移走 cache 中旧的 ShenYu 项目目录，再复制当前 run 的项目 artifact。

因此，**cache 加速依赖下载，artifact 交付当前代码产物；两者不冗余。** storage 不接收项目 Maven artifact，但其独立 E2E reactor 是 `0.0.1-SNAPSHOT`，通过 `-am` 从当前源码构建 storage/engine/client/common，未依赖产品 `2.7.2-SNAPSHOT` JAR；其被测产品来自当前 images。exact-hit 跳过 save 不改变当前产品的交付。删除所有 save 则删除了 miss/fallback 成功后供下游复用的发布点。

## Official cache inventory

**Official measured：** inventory GET 返回 200，`total_count=11`，单页完整返回 11 entries，均为 Maven cache。原始字段见 [inventory.json](official-analysis/cache-audit/inventory.json) / [inventory.csv](official-analysis/cache-audit/inventory.csv)。

| 指标 | 快照值 |
|---|---:|
| Maven entries / 总字节 | 11 / 10,505,273,201 B |
| 总 GiB | 9.783798 |
| full keys / POM suffixes / refs / versions | 6 / 3 / 8 / 2 |
| 平均 / 中位 entry | 955,024,836.45 / 776,720,680 B |
| 最小 / 最大 entry | 206,202,546 / 2,042,712,499 B |
| 存活 entries 最近 1 / 3 / 7 天创建 | 11 / 11 / 11 |
| 存活 entries 最近 1 / 3 / 7 天访问 | 11 / 11 / 11 |
| L family | 8 entries / 6,213,710,622 B / 3 keys / 8 refs |
| J family | 3 entries / 4,291,562,579 B / 3 keys / 3 refs |

独立 usage GET 的字节总数相同，但 `active_caches_count=10`，与 inventory 的 11 不一致；保留两个实际响应，不推定其内部原因。创建/访问统计只覆盖**当前存活 entries**，不等于这些时间窗口内的全部上传数量，也不证明发生过淘汰。

实际 storage-limit GET 返回 **HTTP 402 Payment Required**。**Unknown / UNAVAILABLE THROUGH CURRENT READ-ONLY ACCESS：官方配置的容量与预算无法读取。** 未提升权限、未换 token、未绕过；不能称已确认官方上限为 10 GB。

## Natural cache hit rate

**Official measured：** 四个重点 workflow 的完整 run 元数据为 7 日 1,500、14 日 2,352、30 日 6,555 条；对超过单查询 1,000 条上限的窗口拆分并分页，没有把第一页当全集。7 日 E2E 共有 376 runs。

E2E 按 UTC 日期/事件分层择取成功 run，并补两个不同 POM-key 的近期 PR，共 **10 runs / 20 jobs**。这是有选择规则的样本，非随机总体抽样；builder 成功与 case 成功是入选条件。详见 [sample-selection.json](official-analysis/workflow-audit/sample-selection.json) 和 [逐 job 表](official-analysis/workflow-audit/e2e-sample-metrics.csv)。

| 观察点 / 分母 | exact | fallback | miss | 可成立的结论 |
|---|---:|---:|---:|---|
| E2E builder，10 jobs | — | — | — | 10 个均未请求 restore；该位置的自然命中率没有被现有 workflow 测量 |
| 同 run 下游 case，10 jobs | 9 | 0 | 1 | 样本恢复成功 9/10；不能当作所有 runs 或 builder 的自然命中率 |
| 补充 CI pr_build，4 jobs | 2 | 2 | 0 | 真实构建前 exact/fallback 均存在；不能把 4/4 外推为总体 hit rate |

抽样 builder 下载 Central **2,912–2,943 条日志记录**，build step **139–194 秒**。下游 exact-hit cache 为 **770,640,132–3,217,174,930 B**，step 恢复 **6–40 秒**；精确日志中的传输包络另有亚秒差异。exact-hit case 仍下载 **172–368 条** Central 记录：根构建缓存没有涵盖整个 E2E 图的依赖。

| Run / job | 角色 / ref | 请求 hash → 恢复 hash | Restore | Restore B / step s | Build s | Save |
|---|---|---|---|---|---:|---|
| 39082825769 / 117543416913 | builder / master | `8a2c41e7` | 未请求 | — | 155 | reservation conflict / 3 s |
| 39082825769 / 117544054978 | case / master | `8a2c41e7` → 同值 | exact | 776,804,688 / 6 | 45 | 无 |
| 39086285140 / 117553473313 | builder / PR7793 | `8a2c41e7` | 未请求 | — | 170 | saved / 6 s / 776,690,893 B |
| 39086285140 / 117554205627 | case / PR7793 | `8a2c41e7` → 同值 | exact | 776,690,893 / 8 | 40 | 无 |
| 38752107174 / 116507374093 | builder / PR6457 | `fc8e3aaa` | 未请求 | — | 187 | saved / 10 s / 776,512,922 B |
| 38752107174 / 116551565753 | case / PR6457 | `fc8e3aaa` → 无 | miss | — / 1 | 88 | 无 |
| 38342163370 / 115121324681 | case / master | `5efa3a0a` → 同值 | exact | 3,217,174,930 / 34 | 34 | 无 |
| 38597023400 / 116480147633 | case / PR | `5efa3a0a` → 同值 | exact | 2,937,530,141 / 40 | 40 | 无 |
| 39086285148 / 117553474924 | CI / PR7793 | `8a2c41e7` → 同值 | exact | 776,804,688 / 7 | 不作同 workload 比较 | 无 |
| 39085490404 / 117551071469 | CI / PR7673 | `2f4add9e` → `8a2c41e7` | fallback | 776,804,688 / 7 | 同上 | 无 |

10 个 builder save：**saved 5、reservation conflict 5、fatal save failure 0、skipped 0**。成功上传 **770,851,207–776,835,933 B**；conflict 花 **2–4 秒**且没有内容上传进度。原文 `Unable to reserve cache` 不能区分已存在 entry 和同时 reservation，所以不把 5 条全部硬改名为“already exists”。这是样本计数，不是全周计数。

真实反例：[builder](https://github.com/apache/shenyu/actions/runs/38752107174/job/116507374093) 于 10 月 15 日 16:50 UTC 保存成功；[同 run case](https://github.com/apache/shenyu/actions/runs/38752107174/job/116551565753) 18:07 UTC 对 exact 和 prefix 均 miss，仍接收当前 artifact、补下载 1,464 条并成功。**成功 save 不保证下游一定 restore；日志没有给出该 entry 后来不可用的原因。**

## Cache scope and refs

**Documented：**缓存匹配受 key、version 和 ref scope 共同约束；PR 可读当前/base/default branch，PR 写入 merge ref，其他 PR 和 master 不能读这个 PR 私有 entry。同 ref 的不同 workflow 可共享缓存。version 由 path、压缩工具等生成；相同 key 不能跨不兼容 version 使用。[GitHub scope/version 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache)

**Official measured：以下 entries 的 key 和 version 完全相同，但 ID、ref 和存储字节独立。答案是 B：分别占用 quota，不是全仓一个 entry。**

共同 key：`Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`；version：`41d1ec4cf4a0f14e35637462cb542fdb4ca2f7d94ec87d84b439a859c4ebe98f`。

| ID | Ref | Bytes |
|---|---|---:|
| 9094733854 | refs/heads/master | 776,804,688 |
| 9106318400 | refs/pull/7793/merge | 776,690,893 |
| 9106264157 | refs/pull/7792/merge | 776,725,406 |
| 9105358453 | refs/pull/7785/merge | 776,720,680 |
| 9105347180 | refs/pull/7789/merge | 776,596,704 |
| 9105084294 | refs/pull/7788/merge | 776,640,438 |

合计 **4,660,178,809 B**，其中 5 份 PR entries 占 **3,883,374,121 B**。这是实际 scope 重复存储，不是容量模型；不据此声称这些 PR 当时全部能 exact-hit master。

### master 提供可继承的 warm cache

**Official measured：** [PR #7793](https://github.com/apache/shenyu/pull/7793) 10 月 18 日 **01:23:00 UTC** 创建；[CI job](https://github.com/apache/shenyu/actions/runs/39086285148/job/117553474924) checkout PR7793 merge ref，01:23:21 开始 restore，01:23:23 exact-hit，恢复 **776,804,688 B**。自己的 [E2E builder](https://github.com/apache/shenyu/actions/runs/39086285140/job/117553473313) 到 **01:26:23** 才保存 776,690,893 B 的 PR entry。master entry 9094733854 创建于前一天16:13:12，字节数精确等于此前 CI 恢复值。

**Inference：** scope 规则＋创建顺序＋字节相同，三方证据指向这次 CI 继承 master entry；action 日志不直接输出 source ref/entry ID，所以不把来源写成日志原生字段。**新 PR 在自己的 builder save 前已发生 exact reuse，是实测事实。**

PR7673 的 CI 请求新 POM key `2f4add9e`，在自己的 builder 发布该 key 前，fallback 到 `8a2c41e7`、恢复相同 master-size 的缓存；另有 Oct11 的跨 key fallback。POM 变化不等于必须全冷启动。PR save 的价值是同 PR 后续 runs/同 ref 消费者复用，不是给其他 PR 发布公共缓存。保留 master miss/fallback 后保存，就保留 canonical 发布；没有证据要求改成 master-only save。

## POM hash churn

**Official measured：**对固定 master 的 first-parent 历史比较每个提交与其第一父提交，实际计算 GitHub `hashFiles('**/pom.xml')` 等价值：按目录组件排序深度遍历 POM，取原始 Git blob 的各文件 SHA-256，拼接二进制 digest 后再 SHA-256。281 个当前 POM；当前 master 和 benchmark 源码结果均与日志实际 `8a2c41e7...` 一致。算法依据 [toolkit hash implementation](https://github.com/actions/toolkit/blob/main/packages/glob/src/internal-hash-files.ts) 和 [glob traversal](https://github.com/actions/toolkit/blob/main/packages/glob/src/internal-globber.ts)。

| 窗口 | first-parent commits | POM 变化且实际 hash 变化的 landed commits | subject 关联的独立 PR numbers | distinct after-hashes |
|---|---:|---:|---:|---:|
| 7 天 | 104 | 15 | 15 | 15 |
| 14 天 | 179 | 32 | 32 | 32 |
| 30 天 | 432 | 42 | 42 | 42 |

计数按独立落地主线提交，不按 POM 文件数；PR number 使用提交标题最后一个 `(#number)`，不是将前面的 issue number 当合并 PR。该关联来自标题，不声称完成全部 PR merge API 认证。结果见 [pom-churn.json](official-analysis/pom-churn/pom-churn.json) / [逐提交 CSV](official-analysis/pom-churn/pom-changing-commits.csv)。

因此，这一周主线实际经历 **15 次 Maven key 状态变化**。这不是全仓未合并 PR 的全部 key 数，也不证明 15 个状态全部上传/同时保留，或从未在更早历史出现过。master 相近时间连续更新且有 cancel-in-progress，减少了实际完成的发布数。

## Cache storage pressure

**Fork measured：**种子压缩大小 **776,772,608 B = 740.79 MiB**；原始 repository 约 880.48 MB，raw/compressed 为 1.1335。

**Documented：**GitHub 当前默认 10 GB，可提高；7 天未访问 entry 过期，达到配置容量按 last access 淘汰旧 entry；另有扩容与预算规则。[当前 limits/eviction 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#usage-limits-and-eviction-policy)

**算术模型，非官方配额：**以明确假设的 **10 GiB** 为分母，当前 10,505,273,201 B 为97.838%；一个 fork-size entry 能放13.823份，即13整份。以十进制10 GB为分母则为105.053%、12整份。单位不同，不能互换，更不能拿任一百分比称已确认达到 Apache 的实际上限。

**Inference：**按15个主线状态各发布且保留一份 fork-size entry 的模型，一周对应11,651,589,120 B（10.85 GiB）写入量；真实数量受取消/保存/淘汰影响，真实大小还包括0.2–3.2GB的不同 workload。该模型只说明 key/ref 的增长方向，不是实测上传量。

**确定判断：**scope 重复占用真实存在；避免已 exact-hit 后的重复 PR save 有实际存储收益依据。**没有证据证明 cache storage 已是当前故障瓶颈**：实际 quota/budget 未知、无淘汰审计、单次库存不能给出淘汰率；真实 Central 502/reset 也不是 cache 配额错误。此次不删除 cache、不改预算、不引入新的缓存架构。

## Runner disk

**Fork measured：**19 个有效 runs 的最低本地 free disk 为 **80.809 GiB**，测试 workload 中不存在磁盘不足。**Official measured：**20 个抽样 jobs 的初始 free disk 为 **85.03–85.92 GiB**；清理脚本阈值20GiB。官方值是开工前，不是全程最低值。

repository cache storage 与 runner 的本地盘是两种资源。此次证据没有出现“cache 配额阻碍 VM 创建”；保存失败/淘汰影响缓存可用性与后续下载，不等于 GitHub 无法启动 runner。保留现有 disk cleanup，不为 #7781 增设磁盘防护。

## Maven Central failures

**Official measured：**7 日四 workflow 1,500 runs 中，取得全部138个最新 failure runs，以及55个最新非failure但已重跑 runs，共193个 runs 的全 attempts metadata，2,668 jobs，其中327 failed jobs。排除119个聚合状态和10个 artifact-only/无失败step jobs，完整扫描 **198 个失败 job 日志**。

确认 **至少17个 #7767 型 Central transfer 终态失败：5 reset、12 HTTP502，14个SHA、3个日期；integrated7、E2E6、CI registry3、native1。不是单次事件。**在这些日志中没有终态Central429/503；另有1个Read timed out单列，不混入当前匹配范围。14/30日只完成run元数据，未穷尽日志，故这两个窗口也只能报告至少17，不能报告完整失败率。

17条逐项时间、Central URL、exception、SHA、重跑结果见 [central-recovery-evidence.csv](official-analysis/transport-audit/central-recovery-evidence.csv) 和 [完整故障表](official-analysis/transport-audit/transport-findings.md)。代表日志：

| UTC / job | 终态 | 当前 classifier |
|---|---|---|
| Oct13 20:33 / [115419077089](https://github.com/apache/shenyu/actions/runs/38374178970/job/115419077089) | MySQL E2E；Central reset；DependencyResolutionException | 接受，但调用未接入 |
| Oct15 06:53 / [116288521275](https://github.com/apache/shenyu/actions/runs/38694499875/job/116288521275) | native；Central裸502；PluginResolutionException | **拒绝，真实漏判** |
| Oct15 17:08 / [116507895457](https://github.com/apache/shenyu/actions/runs/38752107044/job/116507895457) | integrated combination；Central reset | 接受，已接入 |
| Oct15 18:35 / [116574960579](https://github.com/apache/shenyu/actions/runs/38755259885/job/116574960579) | websocket E2E；Central reset | 接受，但调用未接入 |
| Oct16 11:17 / [116946001348](https://github.com/apache/shenyu/actions/runs/38892689678/job/116946001348) | registry；裸502＋build extension/PluginManagerException | 拒绝；调用亦未接入 |
| Oct16 11:24 / [116947697084](https://github.com/apache/shenyu/actions/runs/38892697082/job/116947697084) | Spring Cloud E2E；Central裸502 | 拒绝，调用亦未接入 |

离线使用当前 PR 的原始 awk 对17份完整日志重放：**11接受、5裸502拒绝、1混合 spring-release401拒绝**，见 [classifier-replay.json](official-analysis/transport-audit/classifier-replay.json)。这不等于17个故障已由PR全部解决，也不等于被接受的日志在短间隔重试后必定成功。

Issue 所引另一个 [Sep30 pr_build job](https://github.com/apache/shenyu/actions/runs/35868262759/job/110137674074) 实际是内部 `shenyu-register-client-beat:2.7.2-SNAPSHOT` 在 `apache.snapshots` 不存在，**不是 Central transient**。真实测试断言、wrapper main缺失等亦排除。没有把日志中任意502字符串当作依赖下载故障。

### 当前 matcher 的确定错误

native job 已接入wrapper，但原始终态是：

```text
Could not transfer artifact org.apache.maven.shared:file-management:jar:3.1.0 from/to central (https://repo.maven.apache.org/maven2): Transfer failed for https://repo.maven.apache.org/maven2/org/apache/maven/shared/file-management/3.1.0/file-management-3.1.0.jar 502 Bad Gateway -> [Help 1]
```

其 Help 是 PluginResolutionException。现有脚本仅接受带 `status code:` / `status:` / `Return code is:` 前缀的429/502；**不接受上述真实 Wagon 裸502格式**，完整日志重放退出1。这是确定的格式漏判。

最小修正建议：只在现有 Central artifact-transfer 分段内加入该真实 reason 格式，继续核对 Central URL、终态异常类型、全部错误分组。增加**一个**由该真实日志提炼的必要 fixture；保留已有测试，不重复堆相同正例，不把全日志出现502或测试输出502变成重试条件。混合401继续拒绝；未在本任务实施代码修改。

### 重跑恢复与重试次数

7个失败 job 后续在**同run、同head SHA**成功；成功原始日志包含原失败 artifact 从Central下载及BUILD SUCCESS。失败完成至人工重跑启动为 **27.10–55.50小时**。这是两个离散时刻的恢复证据，**不等于故障持续27–55小时**，也没有证明10秒后二次执行足够。

**结论：保留最多3次执行、10/20秒等待。**没有秒级恢复分布支持降为2次，也没有证据要求更长等待/更多重试。fork benchmark没有自然transient，因此不能用其成功runs证明重试恢复率。

## Retry coverage

**Official measured：**当前#7781覆盖pr_build、integrated root/examples/image/test、E2E builder/examples和native list-contract。其余Maven命令不应因“存在”就全部替换。

**Ready前补三个已确认同型失败的E2E调用：**

| 现有源文件 / 行 | 实际故障证据 | 精确建议 |
|---|---|---|
| `shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-storage/compose/script/e2e-mysql-compose.sh:50` | job115419077089；父E2E依赖解析reset，测试尚未执行 | 仅包装该 `./mvnw ... test` |
| `shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-websocket/compose/script/e2e-websocket-sync-compose.sh:44` | job116574960579；common/engine已通过，websocket模块解析reset | 仅包装该Maven调用 |
| `shenyu-e2e/shenyu-e2e-case/shenyu-e2e-case-spring-cloud/compose/script/e2e-springcloud-sync-compose.sh:63` | job116947697084；surefire依赖解析裸502 | 修正502格式后，仅包装该Maven调用 |

这些脚本在master和PR head内容相同；对应矩阵由E2E requirement聚合消费。**不得把整个Compose生命周期交给重试，也不得用exec替掉有后续down、日志、loop或EXIT cleanup的外层脚本。**Maven重跑会重走同一invocation中已完成的模块；这不是只重跑网络GET。保留原参数、返回值处理、容器生命周期，避免重新部署整个测试环境。

**有证据但不扩入本PR的邻接项：**`ci.yml:129` registry_dependencies第一条Maven调用出现3个裸502。该job不在当前 `requirement/build` 的needs中；本审计未读取branch protection，不能称其是独立required check。第三例还要支持新的build-extension分组/PluginManagerException，超出只补裸502 reason的改动。最终建议将这3例记录为后续独立修复，不扩大#7781的matcher框架；第二条registry invocation没有同型失败证据，不建议替换。

## Official concurrency

**Official measured：**7日ci/e2e/integrated共1,119 runs，822 PR runs、297 push runs，关联164个PR，身份822/822已补证。用job真实started/completed时间做区间并集，跨workflow按PR去重；不使用run.updated_at当结束时间。只统计窗口内创建的run，不含更早创建的carry-in。

latest attempt得到7,205实际jobs，排除184条继承的旧attempt成功job引用；补全可取得的历史attempt后有7,559次去重执行。56个重跑run共有69个旧attempt，40个有记录，另29个GET返回空jobs，因此**全历史仍是观测下界**。action_required的57个run没有查询执行jobs，未伪造执行区间。

| 同时执行的独立PR数 | latest真实job区间 秒（全周%） | 加入可用历史attempt 秒（全周%） |
|---|---:|---:|
| 0个观测PR | 457,576（75.657%） | 451,208（74.604%） |
| 1 | 96,323（15.926%） | 99,604（16.469%） |
| 2 | 32,666（5.401%） | 33,184（5.487%） |
| 3 | 8,118（1.342%） | 9,717（1.607%） |
| 4+ | 10,117（1.673%） | 11,087（1.833%） |

两列均合计604,800秒；0表示该cohort没有观察到PR执行，不证明整个仓库空闲。可用历史口径中，至少2/3/4个PR分别有264/129/63段窗口；阈值重叠不能相加。精确2/精确3/4+为互斥分桶。

实际job区间活跃时的时间加权**median1、p95为4、观察峰值9个PR**；可用历史均值1.665。真实9-PR例子是Oct17 **14:23:56–14:24:00 UTC**，当时49个job区间同时运行；独立的job区间峰值122不是122个PR。latest workflow从run_started到最后job完成的“包络”活跃median4、p95为13、峰值18个PR，包含依赖/调度空隙，**不能称18个PR同时使用VM**。

| 排队指标：job.created_at → started_at | N | median s | p95 s | max s |
|---|---:|---:|---:|---:|
| PR jobs，三个workflow | 5,829 | 55 | 6,129 | 20,655 |
| ci PR jobs | 1,642 | 97.5 | 6,245 | 20,519 |
| e2e PR jobs | 2,409 | 17 | 5,767 | 20,519 |
| integrated PR jobs | 1,778 | 69.5 | 6,232 | 20,655 |
| PR＋push全部jobs | 7,205 | 7 | 5,617 | 20,655 |

这是实际等待时间，不是缓存/配额/runner容量的根因诊断。fork约3秒不能替代官方队列分布；本任务没有证据将长尾归因于cache。观察到9个PR重叠，也不等于9个save步骤同时运行。

取消率：ci14/277 PR runs（5.054%）、e2e19/277（6.859%）、integrated12/268（4.478%），合计**45/822＝5.474%**。全部events为228/1,119＝20.375%，其中push为183/297＝61.616%；不能拿20.375%当PR取消率。

**同PR更新的真实序列：**PR7569旧[E2E run38892017319](https://github.com/apache/shenyu/actions/runs/38892017319)与新[run38892448922](https://github.com/apache/shenyu/actions/runs/38892448922)的SHA不同；新run于Oct16 11:14:20 UTC创建，旧producer日志11:14:23.917报告取消、26秒完成，旧run所有实际jobs到33秒完成。旧SHA的workflow配置同PR group及cancel-in-progress:true。**Inference：**配置解释了这一新commit/旧run取消序列；日志未提供取消actor，不能说服务器actor审计已证自动取消。

取消确实存在，旧构建在save前结束就不完成该次发布；但不能把所有取消run视为已经save，也不能据取消率计算节约字节。保留现有concurrency配置，不为本PR增加新的队列架构。方法、完整边界与精简CSV见[concurrency-report.md](official-analysis/concurrency-audit/concurrency-report.md)。

## Benchmark vs production differences

| Property | Fork benchmark（实测） | Official production（实测/边界） |
|---|---|---|
| ref / event | 同bench ref；workflow_dispatch | PR merge refs和push master；scope隔离 |
| POM | 单一hash | 主线7/14/30日15/32/42次hash变化；另有PR自己的hash |
| hit | 人为warm exact | builder目前无restore；case样本9exact/1miss；CI补样2exact/2fallback |
| cache size | 压缩776,772,608B | 库存0.206–2.043GB；历史恢复最高3.217GB |
| workload | 根构建＋images，不含下游E2E | examples、独立E2E reactor、容器测试、artifacts |
| 下载 | B2943条、C0条 | exact case仍172–368条；`-ntp`下日志0不等于HTTP0 |
| 保存 | 1次初始上传、15次exists、3skip | 样本5saved/5reservation-conflict；不同PR entry真实独立存储 |
| 并发 | 独立1/2/4 warm jobs；save峰值均为1 | 真实历史见上一节；不把workflow envelope当VM并发 |
| 故障恢复 | 无自然reset/429/502 | 至少17个真实reset/502终态，当前helper仍漏裸502 |

**可外推：**同类根构建已有依赖被恢复后，减少远程解析下载；无需增加runner磁盘架构；同key多余save存在可避免的压缩/reservation开销。官方存在构建前exact/fallback和可继承master缓存，支持在builder增加restore。

**不能直接外推：**官方每run固定节省33秒、builder总体命中率90%或100%、完整E2E零下载、全仓entry都741MiB、4-job实验证明官方全量并发容量足够、3次执行保证所有真实故障恢复。

Fork串行每组n=3：A build168.8917s、B159.2407s、C120.2187s、D131.3413s；C restore6.3333s；B→C build减少39.022s（24.5%），计restore净32.6887s，job均值差33s。D build反而比C长，说明小样本存在运行差异，不能把C/D所有差异归因save；直接计时C save均值3s（约job的1.9%）才是保存开销依据。A/B差异也不证明wrapper加速。19有效runs、1个排除的preflight；数据原样来自[既有benchmark报告](benchmark-report.md)与七份指定CSV/JSON，没有重跑。

## Exact-hit save optimization

**SAFE。**为新增restore增加 `id: restore-maven-cache`，然后在既有save加：

```yaml
if: steps.restore-maven-cache.outputs.cache-hit != 'true'
```

这是建议片段，没有修改workflow。保持原save key/path与成功后位置；不加`always()`。当前PR新增restore**没有id**，所以必须同时补id，不能只贴save条件。

| restore结果 | `cache-hit`输出 | 条件结果 | 成功build之后 |
|---|---|---|---|
| exact，包括来自master/base | `'true'` | false | 跳过save；避免压缩/预留及重复PR entry |
| prefix fallback | `'false'` | true | 尝试发布当前exact key |
| 完全miss | unset/空 | true | 尝试发布当前exact key |
| 前置restore/build失败 | 任意 | 隐含success()不成立 | 不保存失败构建 |

**Documented/source verified：**benchmark解析到的actions/cache v3 SHA是 `6f8efc29b200d32929f49075959781ed54ec270c`；[restoreImpl.ts](https://github.com/actions/cache/blob/6f8efc29b200d32929f49075959781ed54ec270c/src/restoreImpl.ts#L52) 完全miss直接返回、不设hit；成功后71–76行按primary与matched key相等性输出字符串true/false。**fallback不会被该条件抑制，因此不会阻止新POM exact-key创建。**

缓存immutable，exact-hit不证明它含后续所有workload依赖；Maven仍补下载缺失依赖。当前产品产物通过artifact/images交付，storage从当前独立reactor构建，所以跳过exact save不改变正确性。缓存保留/下游可用性不是保证，这与生产已存在的best-effort语义相同。

该方案优于always-save：fork直接测得冗余save约2–5s无新上传，官方库存又证实不同ref额外存储。也优于delete-all-save或master-only：保留miss/fallback首次发布及同PR下游加速，不引入额外生命周期规则。

## Final recommendation for #7781

**选项3：retry + restore + skip exact-hit save。当前版本不是“原样Ready”。**

| 选项 | 裁决 | 依据 |
|---|---|---|
| retry-only | 不选 | 保留E2E builder反复冷下载；官方已存在可复用的前置缓存 |
| retry + restore + always save | 不作为最终版 | restore值得保留；exact后继续save有实测开销和跨ref冗余 |
| retry + restore + conditional save | **选择；SAFE** | miss/fallback继续发布、exact跳过；契合当前artifact与dependency-cache职责 |
| 删除save / master-only /新缓存架构 | 不选 | 删除现有发布能力或扩大改动，现有证据未要求这些变化 |

Ready前最小改动：

1. 增加restore step id及上述save条件，保留key/prefix/path、miss/fallback save和当前artifacts。
2. 在现有严格Central-transfer分类内补真实Wagon裸502 reason，增加一个真实日志fixture；保持混合401/真实测试失败拒绝。
3. 仅补MySQL、websocket、Spring Cloud三个已有同型故障的Maven调用，保留Compose清理与结果处理；不全仓替换、不重试外层脚本。
4. 保留最多3次执行及10/20秒等待；registry旁支的新异常类别另案处理。

必要验证范围：既有wrapper测试＋一个真实裸502 fixture；修改调用脚本的语法及现有Compose脚本测试；existing workflow结构检查。**本任务仅分析，没有实施这些改动或宣称其已通过真实CI。**没有必要再跑19个benchmark或写大量重复正例。最终实际恢复率/完整E2E结果仍需在用户另行授权的修复验证中观察。

### Evidence limitations and reproducibility

- builder新restore位置没有官方自然hit分布；消费者样本受成功选择与上游save影响，不能替代该分布。
- 实际quota/budget HTTP402不可读；没有淘汰审计；inventory/count两个响应不一致的原因未知。
- 7日失败日志覆盖198个指定失败jobs；未穷尽cancelled且attempt1中的失败子jobs及窗口前创建后重跑runs；14/30日不报完整故障率。未读Central基础设施内部事件，网络内部节点原因未知。
- 人工rerun间隔不是故障持续时间；classifier离线接受不是实际retry成功。fork没有测同时save/冷first-writer竞争。
- 固定截止与各GET快照时间不同，报告不声称跨endpoint事务快照，也不把截止后状态当周内新运行。
- 精简证据随报告保存：cache inventory/usage/402、完整workflow map、样本metrics、POM逐提交hash、失败coverage/replay/recovery、并发派生summary/episodes。完整分页metadata、固定源码和原始gzip日志在本地 `D:/Projects/shenyu-benchmark-7767/official-analysis/`，未把数百MB原始资料全部写入fork。GitHub job链接与固定commit源码链接提供原始记录入口。
- 原有fork benchmark的七份数据及完整报告保持原样；本报告的快照、分母与推导单独记录。
