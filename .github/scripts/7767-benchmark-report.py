#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Derive tables from preserved measurements; no network access or dispatch."""

import csv
import datetime
import gzip
import json
from pathlib import Path
import re
import statistics


ROOT = Path("benchmark-results")


def table(headers, rows):
    return "\n".join(["| " + " | ".join(map(str, headers)) + " |", "| " + " | ".join("---" for _ in headers) + " |"] + ["| " + " | ".join(map(str, row)) + " |" for row in rows])


def report(data):
    runs, groups, batches = data["runs"], data["groups"], data["batches"]
    serial = [row for row in runs if row["batch"] == "serial"]
    if set(row["mode"] for row in serial) != set("ABCD"):
        return
    stat = lambda mode, field: next(row for row in groups if row["mode"] == mode and row["metric"] == field)
    mean = lambda mode, field: stat(mode, field)["mean"]
    average = lambda field, selected: statistics.mean(row[field] for row in selected)
    gib = lambda value: f"{value / 1024**3:.3f}"
    mib = lambda value: f"{value / 1024**2:.2f}"
    span = lambda field, selected: f"{min(row[field] for row in selected):,}–{max(row[field] for row in selected):,}"
    warm = [row for row in runs if row["mode"] in "CD"]
    seed = next(row for row in runs if row["save_result"] == "saved")
    archive = seed["cache_compressed_bytes"]
    quota = 10 * 1024 ** 3
    build_reduction = mean("B", "build_seconds") - mean("C", "build_seconds")
    net_reduction = build_reduction - mean("C", "restore_seconds")
    total_reduction = mean("B", "total_seconds") - mean("C", "total_seconds")
    errors = sum(row[key] for row in runs for key in ["connection_reset_count", "http_429_count", "http_502_count"])
    retry = sum(row["retry_count"] for row in runs)
    lowest = min(runs, key=lambda row: row["min_free_disk_bytes"])
    completed = all(sum(row["mode"] == mode for row in serial) == 3 for mode in "ABCD") and sum(row["jobs"] for row in batches) == 7
    status = "完成：12 个串行样本 + 1/2/4 并发的 7 个样本" if completed else f"进行中/未完成：已收集 {len(serial)} 个串行样本、{len(runs)-len(serial)} 个并发样本"
    chosen = "retry + restore" if net_reduction > 0 else "retry-only"
    concurrent = [row for row in runs if row["batch"] != "serial"]
    batch_map = {row["batch"]: row for row in batches}
    peak_save_overlap = max((row["peak_save_steps"] for row in batches), default=0)
    save_overlap_note = ("本次保存 step 区间没有重叠，不能声称已验证同时 save。" if peak_save_overlap == 1 else
                         f"保存 step 区间实测峰值重叠 {peak_save_overlap}。")
    def concurrent_answer(size):
        item = batch_map.get(f"concurrent-{size}")
        if item is None:
            return "尚未完成，不作结论。"
        selected = [row for row in concurrent if row["batch"] == item["batch"]]
        return (f"实际 peak builds={item['peak_overlapping_builds']}；wall {item['batch_wall_clock_seconds']:.1f} 秒；"
                f"restore/save step 重叠峰值 {item['peak_restore_steps']}/{item['peak_save_steps']}；"
                f"queue 均值 {item['mean_queue_seconds']:.2f} 秒；build 均值 {item['mean_build_seconds']:.3f} 秒；"
                f"hit {item['cache_hit_rate']:.0%}；失败 {item['failed_jobs']}；transient 日志 {item['transient_error_mentions']}；"
                f"上传 {sum(row['cache_uploaded_bytes_log'] for row in selected):,} bytes。")
    if len(batches) == 3:
        queue_answer = "1/2/4 并发 queue 均值分别 " + "/".join(f"{batch_map[f'concurrent-{size}']['mean_queue_seconds']:.2f}" for size in [1,2,4]) + " 秒；没有触发 180 秒积压停止线。本轮短样本不测长期排队概率。"
        reference = batch_map["concurrent-1"]["mean_build_seconds"]
        build_answer = f"2/4 并发相对本轮单任务的 build 均值差分别为 {batch_map['concurrent-2']['mean_build_seconds']-reference:+.3f}/{batch_map['concurrent-4']['mean_build_seconds']-reference:+.3f} 秒；这是观测差，不作长期因果推断。"
    else:
        queue_answer = build_answer = "并发实验尚未完成，不作结论。"
    raw_rows = [[f"[{row['run_id']}]({row['url']}) / {row['job_id']}", f"{row['mode']}{row['run']} / {row['batch']} / w{row['worker']}", row["cache_hit"], f"{row['build_seconds']:.3f}", row["restore_seconds"], row["save_seconds"], row["save_result"], f"{row['m2_raw_bytes']:,}", f"{row['cache_compressed_bytes']:,}", gib(row["min_free_disk_bytes"]), f"{row['admin_image_bytes']:,}", f"{row['bootstrap_image_bytes']:,}", f"{row['admin_tar_bytes']:,}", f"{row['bootstrap_tar_bytes']:,}", row["exit_code"]] for row in runs]
    stats_rows = [[mode, stat(mode,"build_seconds")["n"], *[f"{stat(mode,'build_seconds')[key]:.3f}" for key in ["mean", "median", "min", "max", "stddev"]], f"{mean(mode,'restore_seconds'):.3f}", f"{mean(mode,'save_seconds'):.3f}", f"{mean(mode,'total_seconds'):.3f}", f"{mean(mode,'central_downloaded'):.0f}"] for mode in "ABCD"]
    disk_rows = [[mode, *[gib(average(stage+"_free_disk_bytes", [row for row in serial if row["mode"] == mode])) for stage in ["checkout","after_cleanup","before_restore","after_restore","after_build","after_save","after_docker_save"]], gib(min(row["min_free_disk_bytes"] for row in serial if row["mode"] == mode))] for mode in "ABCD"]
    batch_rows = [[row["batch"], row["jobs"], row["peak_overlapping_builds"], row["peak_restore_steps"], row["peak_save_steps"], f"{row['dispatch_span_seconds']:.1f}", f"{row['batch_wall_clock_seconds']:.1f}", f"{row['mean_queue_seconds']:.2f}", f"{row['median_queue_seconds']:.2f}", f"{row['mean_build_seconds']:.3f}", f"{row['median_build_seconds']:.3f}", f"{row['max_build_seconds']:.3f}", f"{row['mean_restore_seconds']:.2f}", f"{row['mean_save_seconds']:.2f}", f"{row['cache_hit_rate']:.0%}", row["total_central_downloads"], row["retry_count"], row["failed_jobs"], row["transient_error_mentions"]] for row in batches]
    save_rows = [[row["run_id"], row["mode"], row["run"], row["batch"], row["worker"], row["save_result"], row["save_seconds"], f"{row['save_host_busy_core_seconds']:.2f}", row["io_read_bytes_delta"], row["io_write_bytes_delta"], row["net_received_bytes_delta"], row["net_sent_bytes_delta"], row["cache_uploaded_bytes_log"]] for row in runs]
    final_inventory = ROOT / "inventory-finish.json"
    inventory_data = json.loads(final_inventory.read_text()) if final_inventory.exists() else json.loads(sorted(ROOT.glob("inventory-*.json"))[-1].read_text())
    entries = inventory_data["actions_caches"]
    entry_rows = [[entry["id"], entry["key"], entry["ref"], f"{entry['size_in_bytes']:,}", entry["created_at"], entry["last_accessed_at"]] for entry in entries]
    peak_stages = {stage: sum(row["sample_min_stage"] == stage for row in runs) for stage in sorted({row["sample_min_stage"] for row in runs})}
    doc = f"""# ShenYu #7767 CI Benchmark

状态：**{status}**。北京时间 2026-10-18 03:00 启动；时间戳原始数据使用 UTC。仅在 `lymerin/shenyu:bench-7767` 执行。

有效 workload 起止（UTC）：`{min(row['created_at'] for row in runs)}` → `{max(row['completed_at'] for row in runs)}`。有效 benchmark job 时长累计 `{sum(row['total_seconds'] for row in runs)/60:.2f}` runner-minutes，不包含两个轻量注册 job 和已排除预检；这不是账单金额测量。

## Environment

- 修复基线：`34781a0aea0df7ddd4df10962f814157bb33b304`。
- 所有有效 workload 的同一源码 commit：`2cc3b3c8c48d47a880fae7a297f9170ab3134130`；结果提交之后的分支 HEAD 不等于实验源码 SHA。
- runner 标签 `ubuntu-latest`，镜像 `{seed['environment']['image_os']}` / `{seed['environment']['image_version']}`，每 VM `{seed['environment']['cpu_count']}` 个 CPU。
- Temurin JDK 17.0.20.1；mvnd 1.0.6 / Maven 3.9.16；Docker Engine 28.0.4。
- workflow：`.github/workflows/7767-benchmark.yml`；采样：`.github/scripts/7767-benchmark.py`；采集与统计脚本位于同目录。
- checkout@v3、submodules=true、默认 depth=1。setup-java 的 Maven cache 关闭。执行原有 `free-disk-space.sh`，未添加额外清理。
- root 文件系统实测 `{seed['checkout_root_total_bytes']:,}` bytes（{gib(seed['checkout_root_total_bytes'])} GiB）。不要把 repository 的缓存预算当作 VM 磁盘容量。

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

key 为 `Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`，表达式和正式流程相同：`${{{{ runner.os }}}}-maven-${{{{ hashFiles('**/pom.xml') }}}}`，前缀 fallback 为 `${{{{ runner.os }}}}-maven-`。

并发阶段为同一 benchmark ref 上的独立 workflow_dispatch run，分别 1/2/4 个 VM，模式 C。没有使用会相互取消的 concurrency group。它测已有同 key 缓存的并发读取/保存尝试，不测不同 PR merge ref 的隔离、空 key 的首次竞争、POM churn 或 eviction。

预检失败 [39060920795](https://github.com/lymerin/shenyu/actions/runs/39060920795) / job 117478527117 已排除：旧 probe 在默认浅克隆中检查历史祖先失败，尚未运行 Maven/缓存。仅修正 benchmark 检查后，所有有效样本统一使用上述新 SHA。旧 probe commit 为 `01e3e8bef42ace79859ab5ebfe6c413fb3bb4d23`。注册 workflow 的 push run 没有 Maven workload，也不计入性能数据。

## Raw runs

时间单位秒，目录/缓存/镜像/tar 单位 bytes，最低磁盘单位 GiB。`not_requested` 表示未请求恢复，不是缓存 miss。每一行 compressed size 都指同一个 A1 种子归档，不表示本行重新上传了一个归档。

{table(['Run / Job','Mode / Batch / Worker','Hit','Build','Restore','Save','Save result','m2 raw','Cache compressed','Min free GiB','Admin image','Bootstrap image','Admin tar','Bootstrap tar','Exit'],raw_rows)}

完整开始/结束时间、queue、total、image ID、日志计数、恢复物理空间增量等见 [runs.csv](benchmark-results/runs.csv)。每个 run 的 `job.log.gz`、Maven 原始日志、`metrics.jsonl`、原始 `METRIC|` / `DISK_SAMPLE|` 行及 GitHub API 元数据均保存在对应 run 目录。

## Group statistics

仅使用串行样本，各组目标 3 次、实际次数见 n，不把并发 C 混入串行平均。标准差为样本标准差；未作统计显著性或未来故障率推断。Job total 是 benchmark job 的耗时，包含探针与测量 artifact 上传，不等于正式 e2e workflow 总耗时。

{table(['Mode','n','Build mean','Median','Min','Max','Stddev','Restore mean','Save mean','Job total mean','Central downloaded mean'],stats_rows)}

B→C 的整段 Maven build 均值减少 **{build_reduction:.3f} 秒（{build_reduction/mean('B','build_seconds'):.1%}）**；C restore 均值 **{mean('C','restore_seconds'):.3f} 秒**。仅按 build+restore 算，差额为 **{net_reduction:.3f} 秒**；完整 job total 均值差为 **{total_reduction:.3f} 秒**。这些是工作负载总耗时差，不是单独 dependency-resolution 阶段耗时。

A→B build 均值差（B−A）为 `{mean('B','build_seconds')-mean('A','build_seconds'):+.3f}` 秒。它混合了不同 VM 的构建/网络波动，**不能当作 wrapper 的精确开销，不能把负差解释为 wrapper 加速**。

## Disk analysis

各阶段为该组串行样本剩余磁盘均值，最后一列为该组所有 5 秒采样及阶段快照的最低值：

{table(['Mode','Checkout','Cleanup','Before restore','After restore','After build','After save','After docker save','Sample min'],disk_rows)}

- 全部有效 run 最低 `{lowest['min_free_disk_bytes']:,}` bytes（**{gib(lowest['min_free_disk_bytes'])} GiB**），run {lowest['run_id']}。采样最低点阶段分布：`{json.dumps(peak_stages,ensure_ascii=False)}`。
- 清理脚本在已核对日志中均判断空间足够并跳过；前后快照的小变化不计为“清理释放”。
- warm restore 前后 filesystem available 差额均值 `{average('restore_disk_increase_bytes',warm):,.0f}` bytes（{gib(average('restore_disk_increase_bytes',warm))} GiB）；恢复后的 `du -sb` 为 `{seed['m2_raw_bytes']:,}` bytes。两者分别是 VM 快照差和逻辑目录大小，不混用。
- 构建后 workspace 范围 `{span('after_build_workspace_bytes',runs)}` bytes。`du -sb` 为逻辑字节，`du -sh` 的已分配块数另见原始日志。
- admin image 范围 `{span('admin_image_bytes',runs)}` bytes；bootstrap image `{span('bootstrap_image_bytes',runs)}` bytes。individual inspect Size 包含共享层，不能简单相加当作 Docker 唯一物理占用。A1 的 `docker system df` 在 checkout 为 1.869GB，构建后为 3.597GB，包含 runner 预装和中间镜像。
- admin tar `{span('admin_tar_bytes',runs)}` bytes；bootstrap tar `{span('bootstrap_tar_bytes',runs)}` bytes；两个实际文件同时存在。逐项 docker save 耗时保存在 CSV。
- 采样从 checkout 后探针开始，到 finalize 停止，覆盖 restore、build、cache 压缩和 docker save，不包含 checkout 执行中及最后测量 artifact 上传/post job。5 秒采样的最低值不等于连续时间数学上的绝对最小值；短于采样间隔的峰值没有被完整解析。本轮没有磁盘不足，也未接近 15 GiB 停止线。

## Cache analysis

- warm 精确命中 **{sum(row['cache_hit']=='true' for row in warm)}/{len(warm)}**；串行 C restore 均值 `{mean('C','restore_seconds'):.3f}` 秒、D `{mean('D','restore_seconds'):.3f}` 秒。
- Maven raw 实测范围 `{span('m2_raw_bytes',runs)}` bytes。首个 A1 原始目录 `{seed['m2_raw_bytes']:,}` bytes（{mib(seed['m2_raw_bytes'])} MiB）。
- GitHub 实存压缩归档 **{archive:,} bytes（{mib(archive)} MiB）**，由 `Sent ... (100.0%)` 日志和 cache API 同时证实。首个种子的 raw/compressed = **{seed['compression_ratio']:.4f}x**。其他行的目录经过本轮构建后略有变化，不能把目录除以旧种子大小声称为一次新压缩实测；CSV 将其单独标为 seed-normalized ratio。
- save step 均值 A `{mean('A','save_seconds'):.3f}` 秒、B `{mean('B','save_seconds'):.3f}` 秒、C `{mean('C','save_seconds'):.3f}` 秒、D 0 秒。A1 是实际压缩+上传；其余同 key 保存先 tar/zstd 压缩，再预留失败，没有 `Sent` 上传记录。不能把全部 save 时间叫作 upload time，也没有独立分离出纯压缩耗时。
- 每个 save 区间的整机计数如下。CPU 为 /proc/stat busy ticks ÷ clock ticks，单位 core-seconds，**不是单线程 wall time**；IO 为整机块设备累计增量，网络为非 loopback 接口累计增量，包含后台活动、虚拟接口计数和缓存/缓冲写回影响。它们不能全部归因于 cache，也不能当成缓存服务账单网络流量。实际上传列仅采用 action 完整发送日志。

{table(['Run','Mode','Sample','Batch','Worker','Result','Step s','Host busy core-s','Host read B','Host write B','Host recv B','Host sent B','Action upload B'],save_rows)}

fork 初始 inventory 为 0；当前 `{inventory_data['total_count']}` 条、合计 `{sum(entry['size_in_bytes'] for entry in entries):,}` bytes，Linux-maven 不同 key 数 `{len({entry['key'] for entry in entries if entry['key'].startswith('Linux-maven-')})}`：

{table(['ID','Key','Ref','Bytes','Created UTC','Last accessed UTC'],entry_rows)}

按用户指定 **10 GiB** 预算推算：`10 × 1024³ / {archive} = {quota/archive:.3f}`，即 **{quota//archive} 份整条同等大小缓存**，忽略其他缓存及大小变化。按十进制 10 GB 则为 `{10_000_000_000/archive:.3f}` 份。这个单位区别和预算假设不是对官方仓库配置的测量。没有填满 quota，没有测 eviction，也没有读取官方 cache inventory。

容量按 cache entry 计，不能只数 POM hash：branch/ref/version 作用域也构成缓存身份。不同 PR 的同 hash 归档仍要计入共享配额。本轮只测了一个 hash、一个 ref，没有发生 POM churn。新增一条同等大小归档的预算增量约 {mib(archive)} MiB；实际官方 churn 和淘汰频率没有测量。[GitHub cache 作用域与容量规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)、[不同分支缓存占用说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manage-caches)。

## Concurrent experiment

{table(['Batch','Jobs','Peak builds','Peak restore','Peak save','Dispatch span','Wall s','Queue mean','Queue median','Build mean','Build median','Build max','Restore mean','Save mean','Hit','Central downloads','Retries','Failed','Transient mentions'],batch_rows)}

batch wall clock 从本组最早 workflow created_at 到最后 benchmark job completed_at；queue 从各 workflow created_at 到其 benchmark job started_at。start/finish 原值与实际峰值重叠数见 [concurrent-batches.csv](benchmark-results/concurrent-batches.csv)。

每次写操作都先重新确认 fork，再顺序 dispatch；Dispatch span 是本组最早/最晚 created_at 的跨度，已计入 batch wall。不能把 batch wall 相对单任务的全部差额解释为 GitHub 排队增长。

Peak restore/save 按 API 秒级 step 区间计算，step 内包含 action 启动等开销，不等于纯压缩或纯传输同时发生。多个 Maven build 重叠不代表所有 cache 操作同时重叠；以表中实测峰值为准。cache save 已存在路径没有实际上传，因此这里也没有“并发上传吞吐量”测量。

每个 job 是独立 VM，不能将各 VM 的 ~/.m2 相加说成同一个 runner 的磁盘压力。这里复用已有 cache entry，多个恢复也不等于服务端创建多份归档。每个 run 的 restore/save 行为和 key 均保存在日志与 CSV；本轮没有做空 key 的首次并发抢写，没有模拟不同 PR merge refs。并发结果只能覆盖表中这组短时、warm、同 ref 任务。

## Retry behavior

所有已核对有效 job 的既有 synthetic validation 均为 20 个案例通过，包括正常成功、reset、parent 429、三种 502 文本、彩色 mvnd、耗尽、编译/测试失败、missing、404、其他仓库、混合错误、invalid model、仅本次执行错误及 cancellation。

真实 Maven workload 自然观察到 reset/429/502 日志合计 **{errors}**，实际 retry **{retry}**。没有封锁 Central、改 DNS、制造错误、扩大外部服务负载。synthetic 证明这些固定输入的分类/执行行为；本轮没有得到自然 transient failure 后成功恢复的真实样本，所以**真实网络恢复效果未被本次 benchmark 验证**。

代码审查确认正常成功路径会创建临时日志、启动 tee，执行 Maven 一次后退出；不执行 awk classifier，不执行 10/20 秒 sleep。正常路径成本不是零，但没有独立的 wrapper CPU/RSS/纳秒级耗时测量。

## Measured facts

1. 有效 run 数 `{len(runs)}`，非零 build exit 数 `{sum(row['exit_code']!=0 for row in runs)}`；所有 workload 同一源码 SHA。
2. 串行 A/B 的 Central 下载日志条目均值为 `{mean('A','central_downloaded'):.0f}` / `{mean('B','central_downloaded'):.0f}`；C/D 均值为 `{mean('C','central_downloaded'):.0f}` / `{mean('D','central_downloaded'):.0f}`。这是文件下载日志条目，不是 HTTP 总请求数，也不是下载字节量。
3. 压缩种子实存 `{archive:,}` bytes；实际成功保存 `{sum(row['save_result']=='saved' for row in runs)}` 次、已存在路径 `{sum(row['save_result']=='already_exists' for row in runs)}` 次、跳过 `{sum(row['save_result']=='skipped' for row in runs)}` 次。逐 run 行为由原始 action 日志证明。
4. 最低采样剩余空间 `{gib(lowest['min_free_disk_bytes'])}` GiB；未触发队列、缓存增长、网络异常或低磁盘停止条件。

## Inferred conclusions

1. 对这个 workload 和已有可访问完整缓存，restore 减少了重复 Central 文件下载，整体 build+restore 均值净差为 `{net_reduction:.3f}` 秒。这里没有单独测量 dependency-resolution 时间，不能声称某个解析阶段缩短同一百分比。
2. 10 GiB 的 entry 预算小于本轮 VM 的磁盘余量，从容量管理角度更应关注缓存份数、ref 和 hash 的增长；本轮 fork 的单条缓存不是实际瓶颈。官方实际 inventory、hit rate、churn 未测，不能宣称官方已经/没有发生淘汰。
3. 同 key 已有完整缓存时反复压缩再保存没有发布新数据；保留冷 miss/新 key 的发布职责并跳过 exact-hit 重复保存，是本轮数据支持另行讨论的优化方向。D 不能作为删掉全部 save 的依据。

## Recommendation for #7781

建议：**{chosen}**。依据是 B/C 的上述 workload 实测净差和下载条目变化，而非为了支持现有 PR 预设结论。retry classifier synthetic 通过，真实成功路径完成；自然 Central 故障后的恢复没有真实样本。

这份报告只验证 fork workflow_dispatch 的 build-docker-images 等价 workload。没有修改/运行官方 Actions，没有执行正式 PR 的 pull_request 权限路径及下游完整 E2E，因此不把结果表述为“正式 PR 所有 CI 已验证”。

## Separate CI architecture observations

cache save 属于旧流程。C/D 额外实验支持单独审查 exact-hit 时的重复压缩，而不是把删 save 混入 #7781。本轮 D 依赖 A1 已生成的种子；没有验证全局取消 cache 发布后下游的冷启动行为。

本轮 D−C 的 Maven build 均值差为 `{mean('D','build_seconds')-mean('C','build_seconds'):+.3f}` 秒，完整 benchmark job 均值差为 `{mean('D','total_seconds')-mean('C','total_seconds'):+.3f}` 秒。save 在 Maven build **之后**执行，不能解释前段 build 时间差。因此不把 C/D 整个 job 的差值当成 save 的因果收益。可直接测到的是 C 的 save 步骤平均 `{mean('C','save_seconds'):.3f}` 秒，约为本组 benchmark job 均值的 `{mean('C','save_seconds')/mean('C','total_seconds'):.1%}`；D 省略该步骤。数据支持一个小范围优化，不支持新建缓存架构或直接删除全部 save。

同 ref warm 并发没有创建多份相同归档；不同 PR 的作用域不在这项测量内。GitHub 的 cache 默认容量、淘汰或账单预算规则与 VM 本地磁盘是两套约束，本任务没有做容量耗尽或 VM 创建失败实验。[缓存配额与作用域规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)。

## 28 个问题逐项回答

"""
    questions = [
        ("retry wrapper 是否引入明显资源成本？", "正常成功路径新增 shell/tee 和临时日志，没有 sleep/classifier。没有独立资源归因测量；完整 A/B 数据不能证明 wrapper 的精确 CPU 成本。"),
        ("正常路径额外耗时多少？", f"未独立测得。整段 B−A 均值为 {mean('B','build_seconds')-mean('A','build_seconds'):+.3f} 秒，该值不是 wrapper 开销。"),
        ("restore 平均减少多少 build time？", f"B−C 为 {build_reduction:.3f} 秒，{build_reduction/mean('B','build_seconds'):.1%}；build+restore 净差 {net_reduction:.3f} 秒。"),
        ("restore 自身耗时？", f"串行 C {mean('C','restore_seconds'):.3f} 秒，D {mean('D','restore_seconds'):.3f} 秒；逐 run 和并发见表。"),
        ("restore 增加多少本地磁盘占用？", f"warm 前后可用空间差均值 {average('restore_disk_increase_bytes',warm):,.0f} bytes；包含整机区间变化。"),
        ("Maven raw size？", f"{span('m2_raw_bytes',runs)} bytes，首个种子 {seed['m2_raw_bytes']:,} bytes。"),
        ("compressed size？", f"{archive:,} bytes，GitHub API + 实际上传日志。"),
        ("compression ratio？", f"配对种子 {seed['compression_ratio']:.4f}x。未把后续旧归档归一比冒充新压缩实测。"),
        ("最低 free disk？", f"采样最低 {lowest['min_free_disk_bytes']:,} bytes / {gib(lowest['min_free_disk_bytes'])} GiB。"),
        ("runner local disk 是风险吗？", "本轮没有空间不足，未接近停止线；结论只覆盖本 workload 和 runner。"),
        ("最高磁盘压力在哪？", f"每 run 的最低采样点阶段分布：{json.dumps(peak_stages,ensure_ascii=False)}；5 秒采样不解析所有短峰。"),
        ("Docker image 占多少？", f"admin {span('admin_image_bytes',runs)}；bootstrap {span('bootstrap_image_bytes',runs)} bytes；共享层不能简单求和。"),
        ("docker save tar 占多少？", f"admin {span('admin_tar_bytes',runs)}；bootstrap {span('bootstrap_tar_bytes',runs)} bytes。"),
        ("Maven cache save 花多久？", f"A/B/C 均值分别 {mean('A','save_seconds'):.3f}/{mean('B','save_seconds'):.3f}/{mean('C','save_seconds'):.3f} 秒；D 跳过。"),
        ("save 的 CPU/IO/network 成本？", "存在 tar/zstd 压缩与整机区间计数增量，详见表；A1 实际上传完整归档。同 key 其余尝试无上传记录；纯进程归因/纯压缩耗时未分离。"),
        ("10GiB quota 比 VM disk 更值得担心？", "容量预算更紧；本轮两者均非瓶颈。未读取官方 inventory，不能对其实际瓶颈下结论。"),
        ("完整缓存容纳多少 hash key？", f"10GiB/{archive}={quota/archive:.3f}，按同尺寸一 entry/hash/ref 可放 {quota//archive} 整份；不同 ref/version 也消耗 entry。"),
        ("当前 key 命中率？", f"受控 warm 组 {sum(row['cache_hit']=='true' for row in warm)}/{len(warm)}；A/B 未请求。不是正式 CI 自然 hit rate。"),
        ("POM hash churn 风险？", f"本轮一个 hash，未实测 churn；每新增同尺寸 entry 预算约 {mib(archive)} MiB，作用域也计容量。"),
        ("同 key 并发 save 行为？", f"已有种子的并发阶段 {len(concurrent)} 个任务，保存新归档 {sum(row['save_result']=='saved' for row in concurrent)} 次，已存在路径 {sum(row['save_result']=='already_exists' for row in concurrent)} 次，上传日志 {sum(row['cache_uploaded_bytes_log'] for row in concurrent):,} bytes。{save_overlap_note}未测空 key 首次抢写。"),
        ("2 个并发 job 怎样？", concurrent_answer(2)),
        ("4 个并发 job 怎样？", concurrent_answer(4)),
        ("queue 明显增加吗？", queue_answer),
        ("build 明显增加吗？", build_answer),
        ("Central 下载因 restore 显著减少吗？", f"本 workload 文件下载日志均值 B {mean('B','central_downloaded'):.0f} → C {mean('C','central_downloaded'):.0f}；不是 HTTP 总请求计数。"),
        ("并发提高 429/502/reset 风险吗？", f"本轮 transient 日志总计 {errors}，没有测出故障概率；不把无错误样本当作概率不增加的证明。"),
        ("#7781 选 retry-only 还是 retry+restore？", f"建议 {chosen}，依据当前受控 workload；自然网络恢复、正式 PR 权限和下游 E2E 未验证。"),
        ("D 支持另开 save 优化 PR 吗？", "支持单独讨论 exact-hit 跳过重复压缩；不支持直接删除全部 save。正式分支和 PR 本任务未修改。"),
    ]
    doc += table(["#", "问题", "结论与证据边界"], [[i, question, answer] for i,(question,answer) in enumerate(questions,1)])
    doc += """

## Data, validation and boundaries

- [runs.csv](benchmark-results/runs.csv)、[group-statistics.csv](benchmark-results/group-statistics.csv)、[stage-metrics.csv](benchmark-results/stage-metrics.csv)、[disk-samples.csv](benchmark-results/disk-samples.csv)、[concurrent-batches.csv](benchmark-results/concurrent-batches.csv)、[derived.json](benchmark-results/derived.json)。
- 原始 run 数据位于 `benchmark-results/runs/<run_id>/`，原始 METRIC 行位于各目录的 `metric-lines.txt`。GitHub artifact 只有 14 天保留期，分支保存的原始文件用于长期复核。
- [validation-all.json](benchmark-results/validation-all.json) 保存全部 19 次的数据审计及 Actions 实际 SHA；[branch-integrity.json](benchmark-results/branch-integrity.json) 保存正式分支、草稿 PR 与原工作区的只读核对结果；[files-sha256.csv](benchmark-results/files-sha256.csv) 保存数据文件的字节校验值。
- API 的 step 起止时间为秒级，包含 action 启动等开销；build_seconds 使用 monotonic 计时；两者不是同一种分辨率。
- 连续采样使用 `shutil.disk_usage` 读取 filesystem available，阶段快照另保留实际 `df -B1 /` 输出。save 压缩归档在步骤结束前清理，因此前后快照净差不是它的瞬时峰值；短步骤受 5 秒采样分辨率限制。
- 统计脚本可在工作树运行 `python .github/scripts/7767-benchmark-report.py` 重新生成 CSV/报告；它不访问网络，也不触发 Actions。调度脚本对 fork 名和固定实验源码 SHA 逐次校验，结果提交后 HEAD 改变会阻止误重跑。
- 每次 GitHub 写操作前重新核对唯一目标 `lymerin/shenyu`。没有对 `apache/shenyu` 写操作，没有修改 `7767` 或 #7781，没有删除缓存，没有创建其他 key，没有更改权限/配额，没有删除 benchmark 分支。
"""
    Path("benchmark-report.md").write_text(doc, encoding="utf-8")


def write_csv(name, rows):
    fields = list(dict.fromkeys(key for row in rows for key in row))
    with (ROOT / name).open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def parse(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def elapsed(start, end):
    return (parse(end) - parse(start)).total_seconds()


def stats(values):
    return dict(n=len(values), mean=statistics.mean(values), median=statistics.median(values),
                min=min(values), max=max(values), stddev=statistics.stdev(values) if len(values) > 1 else 0)


def main():
    summaries = []
    stages = []
    samples = []
    for path in sorted((ROOT / "runs").glob("*/summary.json")):
        row = json.loads(path.read_text(encoding="utf-8"))
        if row.get("excluded"):
            summaries.append(row)
            continue
        records = [json.loads(line) for line in next((path.parent / "artifact").rglob("metrics.jsonl")).read_text().splitlines()]
        by_stage = {record["stage"]: record for record in records}
        row["build_started_at"] = by_stage["build_start"]["timestamp"]
        row["build_completed_at"] = by_stage["build_finish"]["timestamp"]
        for record in records:
            if "free_disk_bytes" in record:
                stages.append(record)
        for stage in ["checkout", "after_cleanup", "before_restore", "after_restore", "before_build", "after_build", "before_save", "after_save", "after_docker_save"]:
            for key in ["free_disk_bytes", "root_total_bytes", "root_used_bytes", "m2_raw_bytes", "workspace_bytes"]:
                if key in by_stage.get(stage, {}):
                    row[stage + "_" + key] = by_stage[stage][key]
        for record in records:
            if record["stage"] == "docker_save":
                row[record["image"] + "_docker_save_seconds"] = record["docker_save_seconds"]
        row["cleanup_freed_bytes"] = by_stage["after_cleanup"]["free_disk_bytes"] - by_stage["checkout"]["free_disk_bytes"]
        row["restore_disk_increase_bytes"] = by_stage["before_restore"]["free_disk_bytes"] - by_stage["after_restore"]["free_disk_bytes"]
        data = [json.loads(line) for line in next((path.parent / "artifact").rglob("disk-samples.jsonl")).read_text().splitlines()]
        for record in data:
            stage = "setup"
            for name, mark in [("restore", "restore_start"), ("build", "build_start"),
                               ("after_build", "build_finish"), ("cache_save", "before_save"),
                               ("docker_save", "after_save"), ("after_docker_save", "after_docker_save")]:
                if parse(record["timestamp"]) >= parse(by_stage[mark]["timestamp"]):
                    stage = name
            record.update(run_id=row["run_id"], mode=row["mode"], batch=row["batch"], stage=stage)
            samples.append(record)
        minimum = min(data, key=lambda item: item["free_disk_bytes"])
        row["sample_min_stage"] = minimum["stage"]
        row["sample_min_timestamp"] = minimum["timestamp"]
        log_text = gzip.decompress((path.parent / "job.log.gz").read_bytes()).decode("utf-8", errors="replace")
        sends = re.findall(r"Sent (\d+) of (\d+) \(100\.0%\)", log_text)
        row["cache_uploaded_bytes_log"] = max((int(sent) for sent, total in sends), default=0)
        received = re.findall(r"Received (\d+) of (\d+) \(100\.0%\)", log_text)
        row["cache_restored_bytes_log"] = max((int(count) for count, total in received), default=0)
        row["cache_size_source"] = "initial A1 seed entry; immutable shared archive"
        row["maven_total_times"] = "; ".join(json.loads(next((path.parent / "artifact").rglob("maven-total-times.json")).read_text()))
        row["save_host_busy_core_seconds"] = row["cpu_busy_ticks_delta"] / row["environment"]["clock_ticks"]
        summaries.append(row)
    write_csv("runs.csv", [{key: value for key, value in row.items() if key != "environment"} for row in summaries])
    write_csv("stage-metrics.csv", stages)
    write_csv("disk-samples.csv", samples)
    valid = [row for row in summaries if not row.get("excluded")]
    serial = [row for row in valid if row["batch"] == "serial"]
    groups = []
    for mode in "ABCD":
        selected = [row for row in serial if row["mode"] == mode]
        if not selected:
            continue
        for field in ["build_seconds", "restore_seconds", "save_seconds", "total_seconds", "queue_seconds", "central_downloaded", "m2_raw_bytes", "min_free_disk_bytes", "save_host_busy_core_seconds"]:
            groups.append(dict(mode=mode, metric=field, **stats([row[field] for row in selected])))
    write_csv("group-statistics.csv", groups)
    batches = []
    for batch in sorted({row["batch"] for row in valid if row["batch"] != "serial"}):
        selected = [row for row in valid if row["batch"] == batch]
        started = min(row["created_at"] for row in selected)
        finished = max(row["completed_at"] for row in selected)
        events = sorted([(parse(row["started_at"]), 1) for row in selected] + [(parse(row["completed_at"]), -1) for row in selected])
        active = peak = 0
        for when, delta in events:
            active += delta
            peak = max(peak, active)
        build_events = sorted([(parse(row["build_started_at"]), 1) for row in selected] + [(parse(row["build_completed_at"]), -1) for row in selected])
        active_builds = peak_builds = 0
        for when, delta in build_events:
            active_builds += delta
            peak_builds = max(peak_builds, active_builds)
        step_peaks = {}
        for kind in ["restore", "save"]:
            step_events = sorted([(parse(row[kind+"_seconds_started_at"]), 1) for row in selected] + [(parse(row[kind+"_seconds_completed_at"]), -1) for row in selected])
            active_steps = peak_steps = 0
            for when, delta in step_events:
                active_steps += delta
                peak_steps = max(peak_steps, active_steps)
            step_peaks[kind] = peak_steps
        batches.append(dict(batch=batch, jobs=len(selected), peak_overlapping_jobs=peak,
                            peak_overlapping_builds=peak_builds,
                            peak_restore_steps=step_peaks["restore"], peak_save_steps=step_peaks["save"],
                            batch_start=started, batch_finish=finished,
                            dispatch_span_seconds=elapsed(started, max(row["created_at"] for row in selected)),
                            batch_wall_clock_seconds=elapsed(started, finished),
                            mean_queue_seconds=statistics.mean(row["queue_seconds"] for row in selected),
                            median_queue_seconds=statistics.median(row["queue_seconds"] for row in selected),
                            mean_build_seconds=statistics.mean(row["build_seconds"] for row in selected),
                            median_build_seconds=statistics.median(row["build_seconds"] for row in selected),
                            max_build_seconds=max(row["build_seconds"] for row in selected),
                            mean_restore_seconds=statistics.mean(row["restore_seconds"] for row in selected),
                            mean_save_seconds=statistics.mean(row["save_seconds"] for row in selected),
                            cache_hit_rate=sum(row["cache_hit"] == "true" for row in selected) / len(selected),
                            total_central_downloads=sum(row["central_downloaded"] for row in selected),
                            total_cache_restore_bytes_log=sum(row["cache_restored_bytes_log"] for row in selected),
                            total_cache_upload_bytes_log=sum(row["cache_uploaded_bytes_log"] for row in selected),
                            retry_count=sum(row["retry_count"] for row in selected),
                            failed_jobs=sum(row["exit_code"] != 0 for row in selected),
                            transient_error_mentions=sum(row[key] for row in selected for key in ["connection_reset_count", "http_429_count", "http_502_count"])))
    write_csv("concurrent-batches.csv", batches)
    data = dict(runs=valid, groups=groups, batches=batches)
    (ROOT / "derived.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    report(data)
    print(f"Derived {len(valid)} runs, {len(groups)} group statistics, {len(batches)} concurrency batches")


if __name__ == "__main__":
    main()
