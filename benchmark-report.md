# ShenYu #7767 CI Benchmark

实验于北京时间 2026-10-18 凌晨在 `lymerin/shenyu:bench-7767` 完成，共 12 个串行样本、7 个并发样本。下文时间戳均为 UTC。

有效运行时间：`2026-10-17T19:06:33Z` → `2026-10-17T19:59:27Z`，累计 `55.25` runner-minutes，不含注册任务和失败预检。

## Environment

- 修复基线：`34781a0aea0df7ddd4df10962f814157bb33b304`。
- 实验源码：`2cc3b3c8c48d47a880fae7a297f9170ab3134130`，19 次有效运行均使用此提交。
- runner 标签 `ubuntu-latest`，镜像 `ubuntu24` / `20261012.334.1`，每 VM `4` 个 CPU。
- Temurin JDK 17.0.20.1；mvnd 1.0.6 / Maven 3.9.16；Docker Engine 28.0.4。
- workflow：`.github/workflows/7767-benchmark.yml`；采样：`.github/scripts/7767-benchmark.py`；采集与统计脚本位于同目录。
- checkout@v3、submodules=true、默认 depth=1。setup-java 的 Maven cache 关闭。执行原有 `free-disk-space.sh`，未添加额外清理。
- root 文件系统实测 `154,894,188,544` bytes（144.256 GiB）。

## Experiment design

| 组 | Retry | Restore | Save | 含义 |
| --- | --- | --- | --- | --- |
| A | 否 | 否 | 是 | 旧方案 |
| B | 是 | 否 | 是 | retry-only |
| C | 是 | 是 | 是 | retry + restore，保留原有 save |
| D | 是 | 是 | 否 | 跳过 save 的对照组 |

四组执行相同的 Maven/Docker 构建；save 沿用原有流程：

```sh
mvnd -B clean install -Prelease,docker -Dmaven.javadoc.skip=true -Drat.skip=true -Dmaven.test.skip=true -Djacoco.skip=true -DskipITs -DskipTests -T1C
```

A 直接调用 mvnd；B/C/D 通过 `maven-with-retry.sh` 执行相同参数。各组构建相同模块和镜像，并导出两个 Docker tar。实验到镜像导出结束，不执行下游 E2E；上传内容为测量数据。

三轮顺序为 A/B/C/D、B/D/A/C、D/C/B/A。A1 生成缓存种子，后续复用相同 key/version/ref。A/B 从空 Maven repository 开始；C/D exact-hit 同一个种子，比较冷构建与 warm 构建。

key 为 `Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`，表达式和正式流程相同：`${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}`，前缀 fallback 为 `${{ runner.os }}-maven-`。

并发阶段在同一 ref 上分别启动 1/2/4 个独立 VM，均使用模式 C 和已有缓存，不设置相互取消的 concurrency group。

预检 [39060920795](https://github.com/lymerin/shenyu/actions/runs/39060920795) 在浅克隆的历史检查中失败，尚未运行 Maven 或 cache，已排除。修正检查后，全部有效样本使用同一实验提交。注册任务不计入性能数据。

## Raw runs

耗时单位为秒，目录、归档和镜像大小为 bytes，剩余磁盘为 GiB。`not_requested` 表示未请求 restore；compressed size 列记录共享的 A1 种子大小。

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

各组统计使用 3 个串行样本，标准差为样本标准差。Job total 包含探针和测量数据上传。

| Mode | n | Build mean | Median | Min | Max | Stddev | Restore mean | Save mean | Job total mean | Central downloaded mean |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 3 | 168.892 | 166.760 | 157.258 | 182.657 | 12.833 | 0.000 | 3.667 | 202.000 | 2943 |
| B | 3 | 159.241 | 166.199 | 144.309 | 167.214 | 12.941 | 0.000 | 3.333 | 189.667 | 2943 |
| C | 3 | 120.219 | 114.679 | 109.847 | 136.130 | 13.990 | 6.333 | 3.000 | 156.667 | 0 |
| D | 3 | 131.341 | 134.019 | 123.863 | 136.142 | 6.563 | 7.667 | 0.000 | 171.000 | 0 |

B→C 的 Maven build 均值减少 **39.022 秒（24.5%）**；C restore 均值 **6.333 秒**。计入 restore 后净省 **32.689 秒**，benchmark job 均值减少 **33.000 秒**。

A/B 运行在不同 VM，其时间差包含构建和网络波动；wrapper 的独立开销未测。

## Disk analysis

各阶段为该组串行样本剩余磁盘均值，最后一列为该组所有 5 秒采样及阶段快照的最低值：

| Mode | Checkout | Cleanup | Before restore | After restore | After build | After save | After docker save | Sample min |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | 85.707 | 85.707 | 85.627 | 85.627 | 82.148 | 82.152 | 81.088 | 80.824 |
| B | 85.707 | 85.707 | 85.627 | 85.627 | 82.150 | 82.152 | 81.088 | 81.086 |
| C | 85.707 | 85.707 | 85.627 | 84.765 | 82.166 | 82.169 | 81.105 | 81.105 |
| D | 85.707 | 85.707 | 85.627 | 84.765 | 82.166 | 82.168 | 81.105 | 80.809 |

- 全部有效 run 最低 `86,768,529,408` bytes（**80.809 GiB**），run 39063137277。采样最低点阶段分布：`{"after_docker_save": 9, "docker_save": 10}`。
- 所有运行均因空间充足而跳过额外清理。
- warm restore 前后 filesystem available 差额均值 `925,460,953` bytes（0.862 GiB）；恢复后的 `du -sb` 为 `880,480,662` bytes，分别为文件系统可用空间变化和目录逻辑大小。
- 构建后 workspace 范围 `1,045,902,656–1,046,017,460` bytes。`du -sb` 为逻辑字节，`du -sh` 的已分配块数另见原始日志。
- admin image 为 `444,878,060` bytes，bootstrap image 为 `686,013,429` bytes，19 次运行大小一致。inspect Size 含共享层；A1 的 `docker system df` 总占用从 1.869GB 增至 3.597GB，含预装和中间镜像。
- admin tar 为 `446,292,480` bytes，bootstrap tar 为 `696,203,264` bytes，19 次运行大小一致，两个文件同时保留。导出耗时见 CSV。
- 磁盘每 5 秒采样一次，覆盖 restore、build、cache save 和镜像导出。本轮未发生磁盘不足，最低余量远高于 15 GiB 停止线。

## Cache analysis

- warm 精确命中 **13/13**；串行 C restore 均值 `6.333` 秒、D `7.667` 秒。
- Maven raw 实测范围 `880,468,530–880,506,669` bytes。首个 A1 原始目录 `880,480,662` bytes（839.69 MiB）。
- 压缩归档为 **776,772,608 bytes（740.79 MiB）**，上传日志与 cache API 结果一致。种子 raw/compressed 为 **1.1335x**；CSV 中其他运行的 seed-normalized ratio 使用同一旧归档作分母。
- save step 均值 A `3.667` 秒、B `3.333` 秒、C `3.000` 秒，D 跳过。A1 完成压缩和上传；其余同 key 保存先执行 tar/zstd，再因 key 已存在而结束，新增上传为 0。
- 下表记录 save 期间的整机计数，含后台活动：CPU 为 busy core-seconds，IO 为块设备增量，网络为非 loopback 接口增量。Action upload 列取自 action 发送日志。

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

fork 初始 inventory 为 0；实验结束时为 `1` 条、合计 `776,772,608` bytes：

| ID | Key | Ref | Bytes | Created UTC | Last accessed UTC |
| --- | --- | --- | --- | --- | --- |
| 9098703027 | Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a | refs/heads/bench-7767 | 776,772,608 | 2026-10-17T19:10:01.849618Z | 2026-10-17T19:56:45.410444Z |

以 **10 GiB** 作容量示例：`10 × 1024³ / 776772608 = 13.823`，可容纳 **13 份同等大小归档**。十进制 10 GB 对应 `12.874` 份。此处是容量估算，官方实际配额见[后续环境分析](official-environment-analysis.md#cache-storage-pressure)。

缓存按 entry 占用配额，身份包含 key、ref 和 version。不同 PR 的相同 hash 归档分别占空间，每新增一条本轮大小的归档约增加 740.79 MiB。[GitHub cache 作用域规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)。

## Concurrent experiment

| Batch | Jobs | Peak builds | Peak restore | Peak save | Dispatch span | Wall s | Queue mean | Queue median | Build mean | Build median | Build max | Restore mean | Save mean | Hit | Central downloads | Retries | Failed | Transient mentions |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| concurrent-1 | 1 | 1 | 1 | 1 | 0.0 | 159.0 | 3.00 | 3.00 | 121.865 | 121.865 | 121.865 | 5.00 | 3.00 | 100% | 0 | 0 | 0 | 0 |
| concurrent-2 | 2 | 2 | 2 | 1 | 5.0 | 184.0 | 3.00 | 3.00 | 131.304 | 131.304 | 136.170 | 7.50 | 3.50 | 100% | 0 | 0 | 0 | 0 |
| concurrent-4 | 4 | 4 | 1 | 1 | 23.0 | 207.0 | 3.50 | 3.50 | 127.463 | 133.108 | 137.743 | 6.50 | 3.25 | 100% | 0 | 0 | 0 | 0 |

batch wall clock 从本组最早 workflow created_at 到最后 benchmark job completed_at；queue 从各 workflow created_at 到其 benchmark job started_at。start/finish 原值与实际峰值重叠数见 [concurrent-batches.csv](benchmark-results/concurrent-batches.csv)。

Dispatch span 是本组最早与最晚 created_at 的间隔，已包含在 batch wall 中。

Peak restore/save 按 API 秒级 step 区间计算。本轮最多 4 个 build 同时运行，save step 峰值为 1；保存尝试均未新增上传。

每个 job 使用独立 VM 和本地 Maven repository；服务端复用同一 cache entry。

## Retry behavior

实验时的重试脚本通过 20 个行为测试，覆盖成功、临时下载失败、重试耗尽、永久构建错误和取消。

实际构建的 reset/429/502 计数为 **0**，重试次数为 **0**。本轮未观察到自然故障后的恢复，重试恢复率未测。

正常成功路径执行 Maven 一次，通过 tee 保存日志，随后退出；分类器和等待只在失败路径运行。

## Conclusions

- 构建结果：19 次有效运行全部成功，全部使用同一实验提交。
- Restore：B → C 的构建均值减少 39.022 秒，扣除 restore 后净省 32.689 秒，Central 下载日志条目从 2943 降至 0。
- 本地磁盘：最低剩余 80.809 GiB，本轮无磁盘不足。缓存服务配额与 VM 本地磁盘分开计算。
- Cache save：共 1 次上传、15 次已存在、3 次跳过。C 组 save 平均 3.000 秒，占 job 均值 1.9%，没有新增上传。
- 方案取舍：构建时间和下载量支持 **retry + restore**；save 的直接计时支持 exact hit 后跳过保存，miss/fallback 后继续发布当前 key。

## Scope

- 本轮是单一 hash、单一 ref、已有缓存的根构建实验，未覆盖完整下游 E2E、不同 PR refs、POM churn 或缓存淘汰。
- 每组只有 3 个串行样本，未独立测量 wrapper 的 CPU/RSS 开销或依赖解析阶段耗时。
- 磁盘采样间隔为 5 秒；API step 时间为秒级，build 时间使用 monotonic 计时。CPU、IO、网络数据为整机计数。
- 本轮未测同时 save、空 key 首次抢写或自然网络故障恢复。
- 官方缓存和运行历史的后续调查见[环境分析](official-environment-analysis.md)。

## Data and reproduction

- [runs.csv](benchmark-results/runs.csv)、[group-statistics.csv](benchmark-results/group-statistics.csv)、[stage-metrics.csv](benchmark-results/stage-metrics.csv)、[disk-samples.csv](benchmark-results/disk-samples.csv)、[concurrent-batches.csv](benchmark-results/concurrent-batches.csv)、[derived.json](benchmark-results/derived.json)。
- 原始 run 数据位于 `benchmark-results/runs/<run_id>/`，原始 METRIC 行位于各目录的 `metric-lines.txt`。GitHub artifact 只有 14 天保留期，分支保存的原始文件用于长期复核。
- [validation-all.json](benchmark-results/validation-all.json) 保存全部 19 次的数据审计及 Actions 实际 SHA；[branch-integrity.json](benchmark-results/branch-integrity.json) 保存正式分支、草稿 PR 与原工作区的只读核对结果；[files-sha256.csv](benchmark-results/files-sha256.csv) 保存数据文件的字节校验值。


- `python .github/scripts/7767-benchmark-report.py` 从已保存的数据离线生成 CSV 和本报告，不触发 Actions。
- 实验只在 fork 执行；官方仓库、正式修复分支和 PR 在实验期间保持不变。
