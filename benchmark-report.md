# ShenYu #7767 CI Benchmark

状态：**完成：12 个串行样本 + 1/2/4 并发的 7 个样本**。北京时间 2026-10-18 03:00 启动；时间戳原始数据使用 UTC。仅在 `lymerin/shenyu:bench-7767` 执行。

有效 workload 起止（UTC）：`2026-10-17T19:06:33Z` → `2026-10-17T19:59:27Z`。有效 benchmark job 时长累计 `55.25` runner-minutes，不包含两个轻量注册 job 和已排除预检；这不是账单金额测量。

## Environment

- 修复基线：`34781a0aea0df7ddd4df10962f814157bb33b304`。
- 所有有效 workload 的同一源码 commit：`2cc3b3c8c48d47a880fae7a297f9170ab3134130`；结果提交之后的分支 HEAD 不等于实验源码 SHA。
- runner 标签 `ubuntu-latest`，镜像 `ubuntu24` / `20261012.334.1`，每 VM `4` 个 CPU。
- Temurin JDK 17.0.20.1；mvnd 1.0.6 / Maven 3.9.16；Docker Engine 28.0.4。
- workflow：`.github/workflows/7767-benchmark.yml`；采样：`.github/scripts/7767-benchmark.py`；采集与统计脚本位于同目录。
- checkout@v3、submodules=true、默认 depth=1。setup-java 的 Maven cache 关闭。执行原有 `free-disk-space.sh`，未添加额外清理。
- root 文件系统实测 `154,894,188,544` bytes（144.256 GiB）。不要把 repository 的缓存预算当作 VM 磁盘容量。

## Experiment design

| 组 | Retry | Restore | Save | 含义 |
| --- | --- | --- | --- | --- |
| A | 否 | 否 | 是 | 旧方案 |
| B | 是 | 否 | 是 | retry-only |
| C | 是 | 是 | 是 | #7781 当前方案 |
| D | 是 | 是 | 否 | 额外测旧 save 策略的成本 |

cache save 是旧流程原有行为，不是 #7781 新增变量。四组执行相同的完整 Maven/Docker workload：

```sh
mvnd -B clean install -Prelease,docker -Dmaven.javadoc.skip=true -Drat.skip=true -Dmaven.test.skip=true -Djacoco.skip=true -DskipITs -DskipTests -T1C
```

A 直接调用 mvnd；B/C/D 使用原有 `maven-with-retry.sh` 包裹同一参数。所有组均构建相同模块和 Docker 镜像、执行两次 docker save。没有执行下游 E2E 用例，也没有上传 Docker tar；仅上传测量数据。没有修改 production Java。

首轮顺序 A/B/C/D，核对正常后，第二轮 B/D/A/C，第三轮 D/C/B/A。A1 首次生成缓存种子，后续使用同一个 key/version/ref。A/B 构建前 Maven repository 为 0 bytes，C/D 精确命中种子。因此这是“冷构建 vs 已有完整缓存”的受控比较，不是生产命中率估计。

key 为 `Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`，表达式和正式流程相同：`${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}`，前缀 fallback 为 `${{ runner.os }}-maven-`。

并发阶段为同一 benchmark ref 上的独立 workflow_dispatch run，分别 1/2/4 个 VM，模式 C。没有使用会相互取消的 concurrency group。它测已有同 key 缓存的并发读取/保存尝试，不测不同 PR merge ref 的隔离、空 key 的首次竞争、POM churn 或 eviction。

预检失败 [39060920795](https://github.com/lymerin/shenyu/actions/runs/39060920795) / job 117478527117 已排除：旧 probe 在默认浅克隆中检查历史祖先失败，尚未运行 Maven/缓存。仅修正 benchmark 检查后，所有有效样本统一使用上述新 SHA。旧 probe commit 为 `01e3e8bef42ace79859ab5ebfe6c413fb3bb4d23`。注册 workflow 的 push run 没有 Maven workload，也不计入性能数据。

## Raw runs

时间单位秒，目录/缓存/镜像/tar 单位 bytes，最低磁盘单位 GiB。`not_requested` 表示未请求恢复，不是缓存 miss。每一行 compressed size 都指同一个 A1 种子归档，不表示本行重新上传了一个归档。

| Run / Job | Mode / Batch / Worker | Hit | Build | Restore | Save | Save result | m2 raw | Cache compressed | Min free GiB | Admin image | Bootstrap image | Admin tar | Bootstrap tar | Exit |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [39061018046](https://github.com/lymerin/shenyu/actions/runs/39061018046) / 117478815859 | A1 / serial / w1 | not_requested | 182.657 | 0 | 5.0 | saved | 880,480,662 | 776,772,608 | 80.824 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39061334821](https://github.com/lymerin/shenyu/actions/runs/39061334821) / 117479757043 | B1 / serial / w1 | not_requested | 166.199 | 0 | 5.0 | already_exists | 880,472,731 | 776,772,608 | 81.088 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39061620432](https://github.com/lymerin/shenyu/actions/runs/39061620432) / 117480598820 | C1 / serial / w1 | true | 114.679 | 6.0 | 2.0 | already_exists | 880,471,322 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39061832433](https://github.com/lymerin/shenyu/actions/runs/39061832433) / 117481204391 | D1 / serial / w1 | true | 134.019 | 9.0 | 0 | skipped | 880,480,484 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39062082358](https://github.com/lymerin/shenyu/actions/runs/39062082358) / 117481940195 | B2 / serial / w1 | not_requested | 167.214 | 0 | 3.0 | already_exists | 880,480,099 | 776,772,608 | 81.086 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39062365270](https://github.com/lymerin/shenyu/actions/runs/39062365270) / 117482777683 | D2 / serial / w1 | true | 136.142 | 7.0 | 0 | skipped | 880,468,530 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39062606495](https://github.com/lymerin/shenyu/actions/runs/39062606495) / 117483503188 | A2 / serial / w1 | not_requested | 166.760 | 0 | 3.0 | already_exists | 880,506,669 | 776,772,608 | 81.087 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39062894672](https://github.com/lymerin/shenyu/actions/runs/39062894672) / 117484343285 | C2 / serial / w1 | true | 136.130 | 6.0 | 5.0 | already_exists | 880,473,868 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39063137277](https://github.com/lymerin/shenyu/actions/runs/39063137277) / 117485084760 | D3 / serial / w1 | true | 123.863 | 7.0 | 0 | skipped | 880,479,152 | 776,772,608 | 80.809 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39063373638](https://github.com/lymerin/shenyu/actions/runs/39063373638) / 117485830074 | C3 / serial / w1 | true | 109.847 | 7.0 | 2.0 | already_exists | 880,483,686 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39063576920](https://github.com/lymerin/shenyu/actions/runs/39063576920) / 117486432760 | B3 / serial / w1 | not_requested | 144.309 | 0 | 2.0 | already_exists | 880,469,303 | 776,772,608 | 81.088 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39063814084](https://github.com/lymerin/shenyu/actions/runs/39063814084) / 117487137098 | A3 / serial / w1 | not_requested | 157.258 | 0 | 3.0 | already_exists | 880,474,703 | 776,772,608 | 81.088 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064094335](https://github.com/lymerin/shenyu/actions/runs/39064094335) / 117487966305 | C1 / concurrent-1 / w1 | true | 121.865 | 5.0 | 3.0 | already_exists | 880,482,172 | 776,772,608 | 81.019 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064308663](https://github.com/lymerin/shenyu/actions/runs/39064308663) / 117488602214 | C1 / concurrent-2 / w1 | true | 136.170 | 9.0 | 4.0 | already_exists | 880,469,212 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064314593](https://github.com/lymerin/shenyu/actions/runs/39064314593) / 117488620111 | C1 / concurrent-2 / w2 | true | 126.439 | 6.0 | 3.0 | already_exists | 880,494,225 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064565425](https://github.com/lymerin/shenyu/actions/runs/39064565425) / 117489379304 | C1 / concurrent-4 / w1 | true | 132.688 | 6.0 | 4.0 | already_exists | 880,475,761 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064574790](https://github.com/lymerin/shenyu/actions/runs/39064574790) / 117489406367 | C1 / concurrent-4 / w2 | true | 105.891 | 6.0 | 2.0 | already_exists | 880,479,675 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064584890](https://github.com/lymerin/shenyu/actions/runs/39064584890) / 117489434573 | C1 / concurrent-4 / w3 | true | 133.529 | 7.0 | 4.0 | already_exists | 880,480,490 | 776,772,608 | 81.105 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |
| [39064594884](https://github.com/lymerin/shenyu/actions/runs/39064594884) / 117489465203 | C1 / concurrent-4 / w4 | true | 137.743 | 7.0 | 3.0 | already_exists | 880,477,197 | 776,772,608 | 81.104 | 444,878,060 | 686,013,429 | 446,292,480 | 696,203,264 | 0 |

完整开始/结束时间、queue、total、image ID、日志计数、恢复物理空间增量等见 [runs.csv](benchmark-results/runs.csv)。每个 run 的 `job.log.gz`、Maven 原始日志、`metrics.jsonl`、原始 `METRIC|` / `DISK_SAMPLE|` 行及 GitHub API 元数据均保存在对应 run 目录。

## Group statistics

仅使用串行样本，各组目标 3 次、实际次数见 n，不把并发 C 混入串行平均。标准差为样本标准差；未作统计显著性或未来故障率推断。Job total 是 benchmark job 的耗时，包含探针与测量 artifact 上传，不等于正式 e2e workflow 总耗时。

| Mode | n | Build mean | Median | Min | Max | Stddev | Restore mean | Save mean | Job total mean | Central downloaded mean |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 3 | 168.892 | 166.760 | 157.258 | 182.657 | 12.833 | 0.000 | 3.667 | 202.000 | 2943 |
| B | 3 | 159.241 | 166.199 | 144.309 | 167.214 | 12.941 | 0.000 | 3.333 | 189.667 | 2943 |
| C | 3 | 120.219 | 114.679 | 109.847 | 136.130 | 13.990 | 6.333 | 3.000 | 156.667 | 0 |
| D | 3 | 131.341 | 134.019 | 123.863 | 136.142 | 6.563 | 7.667 | 0.000 | 171.000 | 0 |

B→C 的整段 Maven build 均值减少 **39.022 秒（24.5%）**；C restore 均值 **6.333 秒**。仅按 build+restore 算，差额为 **32.689 秒**；完整 job total 均值差为 **33.000 秒**。这些是工作负载总耗时差，不是单独 dependency-resolution 阶段耗时。

A→B build 均值差（B−A）为 `-9.651` 秒。它混合了不同 VM 的构建/网络波动，**不能当作 wrapper 的精确开销，不能把负差解释为 wrapper 加速**。

## Disk analysis

各阶段为该组串行样本剩余磁盘均值，最后一列为该组所有 5 秒采样及阶段快照的最低值：

| Mode | Checkout | Cleanup | Before restore | After restore | After build | After save | After docker save | Sample min |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 85.707 | 85.707 | 85.627 | 85.627 | 82.148 | 82.152 | 81.088 | 80.824 |
| B | 85.707 | 85.707 | 85.627 | 85.627 | 82.150 | 82.152 | 81.088 | 81.086 |
| C | 85.707 | 85.707 | 85.627 | 84.765 | 82.166 | 82.169 | 81.105 | 81.105 |
| D | 85.707 | 85.707 | 85.627 | 84.765 | 82.166 | 82.168 | 81.105 | 80.809 |

- 全部有效 run 最低 `86,768,529,408` bytes（**80.809 GiB**），run 39063137277。采样最低点阶段分布：`{"after_docker_save": 9, "docker_save": 10}`。
- 清理脚本在已核对日志中均判断空间足够并跳过；前后快照的小变化不计为“清理释放”。
- warm restore 前后 filesystem available 差额均值 `925,460,953` bytes（0.862 GiB）；恢复后的 `du -sb` 为 `880,480,662` bytes。两者分别是 VM 快照差和逻辑目录大小，不混用。
- 构建后 workspace 范围 `1,045,902,656–1,046,017,460` bytes。`du -sb` 为逻辑字节，`du -sh` 的已分配块数另见原始日志。
- admin image 范围 `444,878,060–444,878,060` bytes；bootstrap image `686,013,429–686,013,429` bytes。individual inspect Size 包含共享层，不能简单相加当作 Docker 唯一物理占用。A1 的 `docker system df` 在 checkout 为 1.869GB，构建后为 3.597GB，包含 runner 预装和中间镜像。
- admin tar `446,292,480–446,292,480` bytes；bootstrap tar `696,203,264–696,203,264` bytes；两个实际文件同时存在。逐项 docker save 耗时保存在 CSV。
- 采样从 checkout 后探针开始，到 finalize 停止，覆盖 restore、build、cache 压缩和 docker save，不包含 checkout 执行中及最后测量 artifact 上传/post job。5 秒采样的最低值不等于连续时间数学上的绝对最小值；短于采样间隔的峰值没有被完整解析。本轮没有磁盘不足，也未接近 15 GiB 停止线。

## Cache analysis

- warm 精确命中 **13/13**；串行 C restore 均值 `6.333` 秒、D `7.667` 秒。
- Maven raw 实测范围 `880,468,530–880,506,669` bytes。首个 A1 原始目录 `880,480,662` bytes（839.69 MiB）。
- GitHub 实存压缩归档 **776,772,608 bytes（740.79 MiB）**，由 `Sent ... (100.0%)` 日志和 cache API 同时证实。首个种子的 raw/compressed = **1.1335x**。其他行的目录经过本轮构建后略有变化，不能把目录除以旧种子大小声称为一次新压缩实测；CSV 将其单独标为 seed-normalized ratio。
- save step 均值 A `3.667` 秒、B `3.333` 秒、C `3.000` 秒、D 0 秒。A1 是实际压缩+上传；其余同 key 保存先 tar/zstd 压缩，再预留失败，没有 `Sent` 上传记录。不能把全部 save 时间叫作 upload time，也没有独立分离出纯压缩耗时。
- 每个 save 区间的整机计数如下。CPU 为 /proc/stat busy ticks ÷ clock ticks，单位 core-seconds，**不是单线程 wall time**；IO 为整机块设备累计增量，网络为非 loopback 接口累计增量，包含后台活动、虚拟接口计数和缓存/缓冲写回影响。它们不能全部归因于 cache，也不能当成缓存服务账单网络流量。实际上传列仅采用 action 完整发送日志。

| Run | Mode | Sample | Batch | Worker | Result | Step s | Host busy core-s | Host read B | Host write B | Host recv B | Host sent B | Action upload B |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 39061018046 | A | 1 | serial | 1 | saved | 5.0 | 7.87 | 905216 | 75763712 | 4287558 | 1593496598 | 776772608 |
| 39061334821 | B | 1 | serial | 1 | already_exists | 5.0 | 6.56 | 905216 | 32768 | 27228 | 96714 | 0 |
| 39061620432 | C | 1 | serial | 1 | already_exists | 2.0 | 4.83 | 204800 | 28672 | 25170 | 93552 | 0 |
| 39061832433 | D | 1 | serial | 1 | skipped | 0 | 0.83 | 0 | 16384 | 11980 | 1778352 | 0 |
| 39062082358 | B | 2 | serial | 1 | already_exists | 3.0 | 6.21 | 905216 | 24576 | 70534 | 1785428 | 0 |
| 39062365270 | D | 2 | serial | 1 | skipped | 0 | 0.89 | 0 | 20480 | 6358 | 20482 | 0 |
| 39062606495 | A | 2 | serial | 1 | already_exists | 3.0 | 8.57 | 1150976 | 192512 | 39236 | 1334936 | 0 |
| 39062894672 | C | 2 | serial | 1 | already_exists | 5.0 | 10.66 | 212992 | 32768 | 67020 | 3702544 | 0 |
| 39063137277 | D | 3 | serial | 1 | skipped | 0 | 0.72 | 0 | 16384 | 40242 | 3626774 | 0 |
| 39063373638 | C | 3 | serial | 1 | already_exists | 2.0 | 4.70 | 204800 | 54558720 | 81994 | 3712600 | 0 |
| 39063576920 | B | 3 | serial | 1 | already_exists | 2.0 | 4.41 | 905216 | 6332416 | 25334 | 94152 | 0 |
| 39063814084 | A | 3 | serial | 1 | already_exists | 3.0 | 6.04 | 1024000 | 25194496 | 43240 | 119562 | 0 |
| 39064094335 | C | 1 | concurrent-1 | 1 | already_exists | 3.0 | 6.02 | 204800 | 32768 | 31248 | 103100 | 0 |
| 39064308663 | C | 1 | concurrent-2 | 1 | already_exists | 4.0 | 6.51 | 212992 | 28672 | 45142 | 3623768 | 0 |
| 39064314593 | C | 1 | concurrent-2 | 2 | already_exists | 3.0 | 6.17 | 204800 | 28672 | 73368 | 3700428 | 0 |
| 39064565425 | C | 1 | concurrent-4 | 1 | already_exists | 4.0 | 6.41 | 495616 | 32768 | 34085 | 110800 | 0 |
| 39064574790 | C | 1 | concurrent-4 | 2 | already_exists | 2.0 | 4.62 | 364544 | 28672 | 71078 | 3694598 | 0 |
| 39064584890 | C | 1 | concurrent-4 | 3 | already_exists | 4.0 | 6.31 | 204800 | 32768 | 27410 | 95466 | 0 |
| 39064594884 | C | 1 | concurrent-4 | 4 | already_exists | 3.0 | 6.37 | 491520 | 28672 | 37016 | 107182 | 0 |

fork 初始 inventory 为 0；当前 `1` 条、合计 `776,772,608` bytes，Linux-maven 不同 key 数 `1`：

| ID | Key | Ref | Bytes | Created UTC | Last accessed UTC |
| --- | --- | --- | --- | --- | --- |
| 9098703027 | Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a | refs/heads/bench-7767 | 776,772,608 | 2026-10-17T19:10:01.849618Z | 2026-10-17T19:56:45.410444Z |

按用户指定 **10 GiB** 预算推算：`10 × 1024³ / 776772608 = 13.823`，即 **13 份整条同等大小缓存**，忽略其他缓存及大小变化。按十进制 10 GB 则为 `12.874` 份。这个单位区别和预算假设不是对官方仓库配置的测量。没有填满 quota，没有测 eviction，也没有读取官方 cache inventory。

容量按 cache entry 计，不能只数 POM hash：branch/ref/version 作用域也构成缓存身份。不同 PR 的同 hash 归档仍要计入共享配额。本轮只测了一个 hash、一个 ref，没有发生 POM churn。新增一条同等大小归档的预算增量约 740.79 MiB；实际官方 churn 和淘汰频率没有测量。[GitHub cache 作用域与容量规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)、[不同分支缓存占用说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manage-caches)。

## Concurrent experiment

| Batch | Jobs | Peak builds | Peak restore | Peak save | Dispatch span | Wall s | Queue mean | Queue median | Build mean | Build median | Build max | Restore mean | Save mean | Hit | Central downloads | Retries | Failed | Transient mentions |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| concurrent-1 | 1 | 1 | 1 | 1 | 0.0 | 159.0 | 3.00 | 3.00 | 121.865 | 121.865 | 121.865 | 5.00 | 3.00 | 100% | 0 | 0 | 0 | 0 |
| concurrent-2 | 2 | 2 | 2 | 1 | 5.0 | 184.0 | 3.00 | 3.00 | 131.304 | 131.304 | 136.170 | 7.50 | 3.50 | 100% | 0 | 0 | 0 | 0 |
| concurrent-4 | 4 | 4 | 1 | 1 | 23.0 | 207.0 | 3.50 | 3.50 | 127.463 | 133.108 | 137.743 | 6.50 | 3.25 | 100% | 0 | 0 | 0 | 0 |

batch wall clock 从本组最早 workflow created_at 到最后 benchmark job completed_at；queue 从各 workflow created_at 到其 benchmark job started_at。start/finish 原值与实际峰值重叠数见 [concurrent-batches.csv](benchmark-results/concurrent-batches.csv)。

每次写操作都先重新确认 fork，再顺序 dispatch；Dispatch span 是本组最早/最晚 created_at 的跨度，已计入 batch wall。不能把 batch wall 相对单任务的全部差额解释为 GitHub 排队增长。

Peak restore/save 按 API 秒级 step 区间计算，step 内包含 action 启动等开销，不等于纯压缩或纯传输同时发生。多个 Maven build 重叠不代表所有 cache 操作同时重叠；以表中实测峰值为准。cache save 已存在路径没有实际上传，因此这里也没有“并发上传吞吐量”测量。

每个 job 是独立 VM，不能将各 VM 的 ~/.m2 相加说成同一个 runner 的磁盘压力。这里复用已有 cache entry，多个恢复也不等于服务端创建多份归档。每个 run 的 restore/save 行为和 key 均保存在日志与 CSV；本轮没有做空 key 的首次并发抢写，没有模拟不同 PR merge refs。并发结果只能覆盖表中这组短时、warm、同 ref 任务。

## Retry behavior

所有已核对有效 job 的既有 synthetic validation 均为 20 个案例通过，包括正常成功、reset、parent 429、三种 502 文本、彩色 mvnd、耗尽、编译/测试失败、missing、404、其他仓库、混合错误、invalid model、仅本次执行错误及 cancellation。

真实 Maven workload 自然观察到 reset/429/502 日志合计 **0**，实际 retry **0**。没有封锁 Central、改 DNS、制造错误、扩大外部服务负载。synthetic 证明这些固定输入的分类/执行行为；本轮没有得到自然 transient failure 后成功恢复的真实样本，所以**真实网络恢复效果未被本次 benchmark 验证**。

代码审查确认正常成功路径会创建临时日志、启动 tee，执行 Maven 一次后退出；不执行 awk classifier，不执行 10/20 秒 sleep。正常路径成本不是零，但没有独立的 wrapper CPU/RSS/纳秒级耗时测量。

## Measured facts

1. 有效 run 数 `19`，非零 build exit 数 `0`；所有 workload 同一源码 SHA。
2. 串行 A/B 的 Central 下载日志条目均值为 `2943` / `2943`；C/D 均值为 `0` / `0`。这是文件下载日志条目，不是 HTTP 总请求数，也不是下载字节量。
3. 压缩种子实存 `776,772,608` bytes；实际成功保存 `1` 次、已存在路径 `15` 次、跳过 `3` 次。逐 run 行为由原始 action 日志证明。
4. 最低采样剩余空间 `80.809` GiB；未触发队列、缓存增长、网络异常或低磁盘停止条件。

## Inferred conclusions

1. 对这个 workload 和已有可访问完整缓存，restore 减少了重复 Central 文件下载，整体 build+restore 均值净差为 `32.689` 秒。这里没有单独测量 dependency-resolution 时间，不能声称某个解析阶段缩短同一百分比。
2. 10 GiB 的 entry 预算小于本轮 VM 的磁盘余量，从容量管理角度更应关注缓存份数、ref 和 hash 的增长；本轮 fork 的单条缓存不是实际瓶颈。官方实际 inventory、hit rate、churn 未测，不能宣称官方已经/没有发生淘汰。
3. 同 key 已有完整缓存时反复压缩再保存没有发布新数据；保留冷 miss/新 key 的发布职责并跳过 exact-hit 重复保存，是本轮数据支持另行讨论的优化方向。D 不能作为删掉全部 save 的依据。

## Recommendation for #7781

建议：**retry + restore**。依据是 B/C 的上述 workload 实测净差和下载条目变化，而非为了支持现有 PR 预设结论。retry classifier synthetic 通过，真实成功路径完成；自然 Central 故障后的恢复没有真实样本。

这份报告只验证 fork workflow_dispatch 的 build-docker-images 等价 workload。没有修改/运行官方 Actions，没有执行正式 PR 的 pull_request 权限路径及下游完整 E2E，因此不把结果表述为“正式 PR 所有 CI 已验证”。

## Separate CI architecture observations

cache save 属于旧流程。C/D 额外实验支持单独审查 exact-hit 时的重复压缩，而不是把删 save 混入 #7781。本轮 D 依赖 A1 已生成的种子；没有验证全局取消 cache 发布后下游的冷启动行为。

本轮 D−C 的 Maven build 均值差为 `+11.123` 秒，完整 benchmark job 均值差为 `+14.333` 秒。save 在 Maven build **之后**执行，不能解释前段 build 时间差。因此不把 C/D 整个 job 的差值当成 save 的因果收益。可直接测到的是 C 的 save 步骤平均 `3.000` 秒，约为本组 benchmark job 均值的 `1.9%`；D 省略该步骤。数据支持一个小范围优化，不支持新建缓存架构或直接删除全部 save。

同 ref warm 并发没有创建多份相同归档；不同 PR 的作用域不在这项测量内。GitHub 的 cache 默认容量、淘汰或账单预算规则与 VM 本地磁盘是两套约束，本任务没有做容量耗尽或 VM 创建失败实验。[缓存配额与作用域规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)。

## 28 个问题逐项回答

| # | 问题 | 结论与证据边界 |
| --- | --- | --- |
| 1 | retry wrapper 是否引入明显资源成本？ | 正常成功路径新增 shell/tee 和临时日志，没有 sleep/classifier。没有独立资源归因测量；完整 A/B 数据不能证明 wrapper 的精确 CPU 成本。 |
| 2 | 正常路径额外耗时多少？ | 未独立测得。整段 B−A 均值为 -9.651 秒，该值不是 wrapper 开销。 |
| 3 | restore 平均减少多少 build time？ | B−C 为 39.022 秒，24.5%；build+restore 净差 32.689 秒。 |
| 4 | restore 自身耗时？ | 串行 C 6.333 秒，D 7.667 秒；逐 run 和并发见表。 |
| 5 | restore 增加多少本地磁盘占用？ | warm 前后可用空间差均值 925,460,953 bytes；包含整机区间变化。 |
| 6 | Maven raw size？ | 880,468,530–880,506,669 bytes，首个种子 880,480,662 bytes。 |
| 7 | compressed size？ | 776,772,608 bytes，GitHub API + 实际上传日志。 |
| 8 | compression ratio？ | 配对种子 1.1335x。未把后续旧归档归一比冒充新压缩实测。 |
| 9 | 最低 free disk？ | 采样最低 86,768,529,408 bytes / 80.809 GiB。 |
| 10 | runner local disk 是风险吗？ | 本轮没有空间不足，未接近停止线；结论只覆盖本 workload 和 runner。 |
| 11 | 最高磁盘压力在哪？ | 每 run 的最低采样点阶段分布：{"after_docker_save": 9, "docker_save": 10}；5 秒采样不解析所有短峰。 |
| 12 | Docker image 占多少？ | admin 444,878,060–444,878,060；bootstrap 686,013,429–686,013,429 bytes；共享层不能简单求和。 |
| 13 | docker save tar 占多少？ | admin 446,292,480–446,292,480；bootstrap 696,203,264–696,203,264 bytes。 |
| 14 | Maven cache save 花多久？ | A/B/C 均值分别 3.667/3.333/3.000 秒；D 跳过。 |
| 15 | save 的 CPU/IO/network 成本？ | 存在 tar/zstd 压缩与整机区间计数增量，详见表；A1 实际上传完整归档。同 key 其余尝试无上传记录；纯进程归因/纯压缩耗时未分离。 |
| 16 | 10GiB quota 比 VM disk 更值得担心？ | 容量预算更紧；本轮两者均非瓶颈。未读取官方 inventory，不能对其实际瓶颈下结论。 |
| 17 | 完整缓存容纳多少 hash key？ | 10GiB/776772608=13.823，按同尺寸一 entry/hash/ref 可放 13 整份；不同 ref/version 也消耗 entry。 |
| 18 | 当前 key 命中率？ | 受控 warm 组 13/13；A/B 未请求。不是正式 CI 自然 hit rate。 |
| 19 | POM hash churn 风险？ | 本轮一个 hash，未实测 churn；每新增同尺寸 entry 预算约 740.79 MiB，作用域也计容量。 |
| 20 | 同 key 并发 save 行为？ | 已有种子的并发阶段 7 个任务，保存新归档 0 次，已存在路径 7 次，上传日志 0 bytes。本次保存 step 区间没有重叠，不能声称已验证同时 save。未测空 key 首次抢写。 |
| 21 | 2 个并发 job 怎样？ | 实际 peak builds=2；wall 184.0 秒；restore/save step 重叠峰值 2/1；queue 均值 3.00 秒；build 均值 131.304 秒；hit 100%；失败 0；transient 日志 0；上传 0 bytes。 |
| 22 | 4 个并发 job 怎样？ | 实际 peak builds=4；wall 207.0 秒；restore/save step 重叠峰值 1/1；queue 均值 3.50 秒；build 均值 127.463 秒；hit 100%；失败 0；transient 日志 0；上传 0 bytes。 |
| 23 | queue 明显增加吗？ | 1/2/4 并发 queue 均值分别 3.00/3.00/3.50 秒；没有触发 180 秒积压停止线。本轮短样本不测长期排队概率。 |
| 24 | build 明显增加吗？ | 2/4 并发相对本轮单任务的 build 均值差分别为 +9.439/+5.598 秒；这是观测差，不作长期因果推断。 |
| 25 | Central 下载因 restore 显著减少吗？ | 本 workload 文件下载日志均值 B 2943 → C 0；不是 HTTP 总请求计数。 |
| 26 | 并发提高 429/502/reset 风险吗？ | 本轮 transient 日志总计 0，没有测出故障概率；不把无错误样本当作概率不增加的证明。 |
| 27 | #7781 选 retry-only 还是 retry+restore？ | 建议 retry + restore，依据当前受控 workload；自然网络恢复、正式 PR 权限和下游 E2E 未验证。 |
| 28 | D 支持另开 save 优化 PR 吗？ | 支持单独讨论 exact-hit 跳过重复压缩；不支持直接删除全部 save。正式分支和 PR 本任务未修改。 |

## Data, validation and boundaries

- [runs.csv](benchmark-results/runs.csv)、[group-statistics.csv](benchmark-results/group-statistics.csv)、[stage-metrics.csv](benchmark-results/stage-metrics.csv)、[disk-samples.csv](benchmark-results/disk-samples.csv)、[concurrent-batches.csv](benchmark-results/concurrent-batches.csv)、[derived.json](benchmark-results/derived.json)。
- 原始 run 数据位于 `benchmark-results/runs/<run_id>/`，原始 METRIC 行位于各目录的 `metric-lines.txt`。GitHub artifact 只有 14 天保留期，分支保存的原始文件用于长期复核。
- [validation-all.json](benchmark-results/validation-all.json) 保存全部 19 次的数据审计及 Actions 实际 SHA；[branch-integrity.json](benchmark-results/branch-integrity.json) 保存正式分支、草稿 PR 与原工作区的只读核对结果；[files-sha256.csv](benchmark-results/files-sha256.csv) 保存数据文件的字节校验值。
- API 的 step 起止时间为秒级，包含 action 启动等开销；build_seconds 使用 monotonic 计时；两者不是同一种分辨率。
- 连续采样使用 `shutil.disk_usage` 读取 filesystem available，阶段快照另保留实际 `df -B1 /` 输出。save 压缩归档在步骤结束前清理，因此前后快照净差不是它的瞬时峰值；短步骤受 5 秒采样分辨率限制。
- 统计脚本可在工作树运行 `python .github/scripts/7767-benchmark-report.py` 重新生成 CSV/报告；它不访问网络，也不触发 Actions。调度脚本对 fork 名和固定实验源码 SHA 逐次校验，结果提交后 HEAD 改变会阻止误重跑。
- 每次 GitHub 写操作前重新核对唯一目标 `lymerin/shenyu`。没有对 `apache/shenyu` 写操作，没有修改 `7767` 或 #7781，没有删除缓存，没有创建其他 key，没有更改权限/配额，没有删除 benchmark 分支。
