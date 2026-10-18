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
    chosen = "retry + restore" if net_reduction > 0 else "retry-only"
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

实验于北京时间 2026-10-18 凌晨在 `lymerin/shenyu:bench-7767` 完成，共 {len(serial)} 个串行样本、{len(runs)-len(serial)} 个并发样本。下文时间戳均为 UTC。

有效运行时间：`{min(row['created_at'] for row in runs)}` → `{max(row['completed_at'] for row in runs)}`，累计 `{sum(row['total_seconds'] for row in runs)/60:.2f}` runner-minutes，不含注册任务和失败预检。

## Environment

- 修复基线：`34781a0aea0df7ddd4df10962f814157bb33b304`。
- 实验源码：`2cc3b3c8c48d47a880fae7a297f9170ab3134130`，19 次有效运行均使用此提交。
- runner 标签 `ubuntu-latest`，镜像 `{seed['environment']['image_os']}` / `{seed['environment']['image_version']}`，每 VM `{seed['environment']['cpu_count']}` 个 CPU。
- Temurin JDK 17.0.20.1；mvnd 1.0.6 / Maven 3.9.16；Docker Engine 28.0.4。
- workflow：`.github/workflows/7767-benchmark.yml`；采样：`.github/scripts/7767-benchmark.py`；采集与统计脚本位于同目录。
- checkout@v3、submodules=true、默认 depth=1。setup-java 的 Maven cache 关闭。执行原有 `free-disk-space.sh`，未添加额外清理。
- root 文件系统实测 `{seed['checkout_root_total_bytes']:,}` bytes（{gib(seed['checkout_root_total_bytes'])} GiB）。

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

key 为 `Linux-maven-8a2c41e76411b4f1bee48bb1e3f397f58722a77069a5329af28a1de9a3b8710a`，表达式和正式流程相同：`${{{{ runner.os }}}}-maven-${{{{ hashFiles('**/pom.xml') }}}}`，前缀 fallback 为 `${{{{ runner.os }}}}-maven-`。

并发阶段在同一 ref 上分别启动 1/2/4 个独立 VM，均使用模式 C 和已有缓存，不设置相互取消的 concurrency group。

预检 [39060920795](https://github.com/lymerin/shenyu/actions/runs/39060920795) 在浅克隆的历史检查中失败，尚未运行 Maven 或 cache，已排除。修正检查后，全部有效样本使用同一实验提交。注册任务不计入性能数据。

## Raw runs

耗时单位为秒，目录、归档和镜像大小为 bytes，剩余磁盘为 GiB。`not_requested` 表示未请求 restore；compressed size 列记录共享的 A1 种子大小。

{table(['Run / Job','Mode / Batch / Worker','Hit','Build','Restore','Save','Save result','m2 raw','Cache compressed','Min free GiB','Admin image','Bootstrap image','Admin tar','Bootstrap tar','Exit'],raw_rows)}

完整开始/结束时间、queue、total、image ID、日志计数、恢复物理空间增量等见 [runs.csv](benchmark-results/runs.csv)。每个 run 的 `job.log.gz`、Maven 原始日志、`metrics.jsonl`、原始 `METRIC|` / `DISK_SAMPLE|` 行及 GitHub API 元数据均保存在对应 run 目录。

## Group statistics

各组统计使用 3 个串行样本，标准差为样本标准差。Job total 包含探针和测量数据上传。

{table(['Mode','n','Build mean','Median','Min','Max','Stddev','Restore mean','Save mean','Job total mean','Central downloaded mean'],stats_rows)}

B→C 的 Maven build 均值减少 **{build_reduction:.3f} 秒（{build_reduction/mean('B','build_seconds'):.1%}）**；C restore 均值 **{mean('C','restore_seconds'):.3f} 秒**。计入 restore 后净省 **{net_reduction:.3f} 秒**，benchmark job 均值减少 **{total_reduction:.3f} 秒**。

A/B 运行在不同 VM，其时间差包含构建和网络波动；wrapper 的独立开销未测。

## Disk analysis

各阶段为该组串行样本剩余磁盘均值，最后一列为该组所有 5 秒采样及阶段快照的最低值：

{table(['Mode','Checkout','Cleanup','Before restore','After restore','After build','After save','After docker save','Sample min'],disk_rows)}

- 全部有效 run 最低 `{lowest['min_free_disk_bytes']:,}` bytes（**{gib(lowest['min_free_disk_bytes'])} GiB**），run {lowest['run_id']}。采样最低点阶段分布：`{json.dumps(peak_stages,ensure_ascii=False)}`。
- 所有运行均因空间充足而跳过额外清理。
- warm restore 前后 filesystem available 差额均值 `{average('restore_disk_increase_bytes',warm):,.0f}` bytes（{gib(average('restore_disk_increase_bytes',warm))} GiB）；恢复后的 `du -sb` 为 `{seed['m2_raw_bytes']:,}` bytes，分别为文件系统可用空间变化和目录逻辑大小。
- 构建后 workspace 范围 `{span('after_build_workspace_bytes',runs)}` bytes。`du -sb` 为逻辑字节，`du -sh` 的已分配块数另见原始日志。
- admin image 为 `{seed['admin_image_bytes']:,}` bytes，bootstrap image 为 `{seed['bootstrap_image_bytes']:,}` bytes，19 次运行大小一致。inspect Size 含共享层；A1 的 `docker system df` 总占用从 1.869GB 增至 3.597GB，含预装和中间镜像。
- admin tar 为 `{seed['admin_tar_bytes']:,}` bytes，bootstrap tar 为 `{seed['bootstrap_tar_bytes']:,}` bytes，19 次运行大小一致，两个文件同时保留。导出耗时见 CSV。
- 磁盘每 5 秒采样一次，覆盖 restore、build、cache save 和镜像导出。本轮未发生磁盘不足，最低余量远高于 15 GiB 停止线。

## Cache analysis

- warm 精确命中 **{sum(row['cache_hit']=='true' for row in warm)}/{len(warm)}**；串行 C restore 均值 `{mean('C','restore_seconds'):.3f}` 秒、D `{mean('D','restore_seconds'):.3f}` 秒。
- Maven raw 实测范围 `{span('m2_raw_bytes',runs)}` bytes。首个 A1 原始目录 `{seed['m2_raw_bytes']:,}` bytes（{mib(seed['m2_raw_bytes'])} MiB）。
- 压缩归档为 **{archive:,} bytes（{mib(archive)} MiB）**，上传日志与 cache API 结果一致。种子 raw/compressed 为 **{seed['compression_ratio']:.4f}x**；CSV 中其他运行的 seed-normalized ratio 使用同一旧归档作分母。
- save step 均值 A `{mean('A','save_seconds'):.3f}` 秒、B `{mean('B','save_seconds'):.3f}` 秒、C `{mean('C','save_seconds'):.3f}` 秒，D 跳过。A1 完成压缩和上传；其余同 key 保存先执行 tar/zstd，再因 key 已存在而结束，新增上传为 0。
- 下表记录 save 期间的整机计数，含后台活动：CPU 为 busy core-seconds，IO 为块设备增量，网络为非 loopback 接口增量。Action upload 列取自 action 发送日志。

{table(['Run','Mode','Sample','Batch','Worker','Result','Step s','Host busy core-s','Host read B','Host write B','Host recv B','Host sent B','Action upload B'],save_rows)}

fork 初始 inventory 为 0；实验结束时为 `{len(entries)}` 条、合计 `{sum(entry['size_in_bytes'] for entry in entries):,}` bytes：

{table(['ID','Key','Ref','Bytes','Created UTC','Last accessed UTC'],entry_rows)}

以 **10 GiB** 作容量示例：`10 × 1024³ / {archive} = {quota/archive:.3f}`，可容纳 **{quota//archive} 份同等大小归档**。十进制 10 GB 对应 `{10_000_000_000/archive:.3f}` 份。此处是容量估算，官方实际配额见[后续环境分析](official-environment-analysis.md#cache-storage-pressure)。

缓存按 entry 占用配额，身份包含 key、ref 和 version。不同 PR 的相同 hash 归档分别占空间，每新增一条本轮大小的归档约增加 {mib(archive)} MiB。[GitHub cache 作用域规则](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)。

## Concurrent experiment

{table(['Batch','Jobs','Peak builds','Peak restore','Peak save','Dispatch span','Wall s','Queue mean','Queue median','Build mean','Build median','Build max','Restore mean','Save mean','Hit','Central downloads','Retries','Failed','Transient mentions'],batch_rows)}

batch wall clock 从本组最早 workflow created_at 到最后 benchmark job completed_at；queue 从各 workflow created_at 到其 benchmark job started_at。start/finish 原值与实际峰值重叠数见 [concurrent-batches.csv](benchmark-results/concurrent-batches.csv)。

Dispatch span 是本组最早与最晚 created_at 的间隔，已包含在 batch wall 中。

Peak restore/save 按 API 秒级 step 区间计算。本轮最多 4 个 build 同时运行，save step 峰值为 1；保存尝试均未新增上传。

每个 job 使用独立 VM 和本地 Maven repository；服务端复用同一 cache entry。

## Retry behavior

实验时的重试脚本通过 20 个行为测试，覆盖成功、临时下载失败、重试耗尽、永久构建错误和取消。

实际构建的 reset/429/502 计数为 **{errors}**，重试次数为 **{retry}**。本轮未观察到自然故障后的恢复，重试恢复率未测。

正常成功路径执行 Maven 一次，通过 tee 保存日志，随后退出；分类器和等待只在失败路径运行。

## Conclusions

- 构建结果：{len(runs)} 次有效运行全部成功，全部使用同一实验提交。
- Restore：B → C 的构建均值减少 {build_reduction:.3f} 秒，扣除 restore 后净省 {net_reduction:.3f} 秒，Central 下载日志条目从 {mean('B','central_downloaded'):.0f} 降至 {mean('C','central_downloaded'):.0f}。
- 本地磁盘：最低剩余 {gib(lowest['min_free_disk_bytes'])} GiB，本轮无磁盘不足。缓存服务配额与 VM 本地磁盘分开计算。
- Cache save：共 {sum(row['save_result']=='saved' for row in runs)} 次上传、{sum(row['save_result']=='already_exists' for row in runs)} 次已存在、{sum(row['save_result']=='skipped' for row in runs)} 次跳过。C 组 save 平均 {mean('C','save_seconds'):.3f} 秒，占 job 均值 {mean('C','save_seconds')/mean('C','total_seconds'):.1%}，没有新增上传。
- 方案取舍：构建时间和下载量支持 **{chosen}**；save 的直接计时支持 exact hit 后跳过保存，miss/fallback 后继续发布当前 key。

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
