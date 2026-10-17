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

"""Fork-only dispatch and lossless collection; never contacts the upstream API."""

import datetime
import gzip
import json
from pathlib import Path
import subprocess
import sys
import time


REPO = "lymerin/shenyu"
BRANCH = "bench-7767"
SOURCE = "2cc3b3c8c48d47a880fae7a297f9170ab3134130"
WORKFLOW = 387899033
ROOT = Path("benchmark-results")


def gh(*args, binary=False):
    result = subprocess.run(["gh", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    return result.stdout if binary else result.stdout.decode("utf-8-sig", errors="replace")


def api(path):
    assert path == f"repos/{REPO}" or path.startswith(f"repos/{REPO}/"), "STOP: non-fork API target"
    return json.loads(gh("api", path))


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def seconds(start, end):
    parse = lambda value: datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return (parse(end) - parse(start)).total_seconds()


def inventory(label):
    data = api(f"repos/{REPO}/actions/caches?per_page=100")
    save(ROOT / f"inventory-{label}.json", data)
    entries = data["actions_caches"]
    current = [item for item in entries if item["ref"] == "refs/heads/" + BRANCH]
    total = sum(item["size_in_bytes"] for item in entries)
    print(f"Inventory {label}: {len(entries)} entries, {total} bytes", flush=True)
    if total > 4 * 1024 ** 3 or len(current) > 1:
        raise RuntimeError("STOP: cache storage or benchmark cache-key growth exceeded the controlled envelope")
    return data


def dispatch(mode, sample, batch="serial", worker=1):
    # Reconfirm the exact repository before EACH remote write.
    metadata = api(f"repos/{REPO}")
    if metadata["full_name"] != REPO:
        raise RuntimeError("STOP: remote write target is not lymerin/shenyu")
    ref = api(f"repos/{REPO}/git/ref/heads/{BRANCH}")
    if ref["object"]["sha"] != SOURCE:
        raise RuntimeError("STOP: benchmark source commit changed")
    gh("api", "--method", "POST", f"repos/{REPO}/actions/workflows/{WORKFLOW}/dispatches",
       "-f", "ref=" + BRANCH, "-f", "inputs[mode]=" + mode,
       "-f", f"inputs[sample]={sample}", "-f", "inputs[batch]=" + batch,
       "-f", f"inputs[worker]={worker}")
    title = f"7767 {mode} sample={sample} batch={batch} worker={worker}"
    for _ in range(15):
        runs = api(f"repos/{REPO}/actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&per_page=100")
        matches = [run for run in runs["workflow_runs"] if run["display_title"] == title and run["head_sha"] == SOURCE]
        if matches:
            run_id = max(run["id"] for run in matches)
            print(f"Dispatched {title}: {run_id}", flush=True)
            return run_id
        time.sleep(2)
    raise RuntimeError("STOP: dispatched run could not be identified")


def wait(run_ids):
    seen = {}
    while True:
        completed = 0
        for run_id in run_ids:
            run = api(f"repos/{REPO}/actions/runs/{run_id}")
            state = (run["status"], run["conclusion"])
            if seen.get(run_id) != state:
                print(f"Run {run_id}: {state}", flush=True)
                seen[run_id] = state
            if run["status"] == "completed":
                completed += 1
            elif run["status"] == "queued":
                now = datetime.datetime.now(datetime.timezone.utc).isoformat()
                if seconds(run["created_at"], now) > 180:
                    raise RuntimeError("STOP: runner queue exceeded three minutes")
        if completed == len(run_ids):
            return
        time.sleep(30)


def collect(run_id, excluded=False):
    directory = ROOT / "runs" / str(run_id)
    directory.mkdir(parents=True, exist_ok=True)
    run = api(f"repos/{REPO}/actions/runs/{run_id}")
    jobs = api(f"repos/{REPO}/actions/runs/{run_id}/jobs?per_page=100")
    save(directory / "run.json", run)
    save(directory / "jobs.json", jobs)
    job = next(item for item in jobs["jobs"] if item["name"] == "benchmark")
    logs = gh("api", f"repos/{REPO}/actions/jobs/{job['id']}/logs", "--allow-escape-sequences", binary=True)
    with gzip.open(directory / "job.log.gz", "wb") as output:
        output.write(logs)
    if not (directory / "artifact").exists():
        gh("run", "download", str(run_id), "--repo", REPO, "--dir", str(directory / "artifact"))
    metrics_file = next((directory / "artifact").rglob("metrics.jsonl"))
    records = [json.loads(line) for line in metrics_file.read_text(encoding="utf-8").splitlines()]
    environment_file = next((directory / "artifact").rglob("environment.json"))
    environment = json.loads(environment_file.read_text(encoding="utf-8"))
    summary = dict(run_id=run_id, job_id=job["id"], url=run["html_url"],
                   source_commit=run["head_sha"], conclusion=run["conclusion"], excluded=excluded,
                   created_at=run["created_at"], started_at=job["started_at"], completed_at=job["completed_at"],
                   queue_seconds=seconds(run["created_at"], job["started_at"]),
                   total_seconds=seconds(job["started_at"], job["completed_at"]),
                   environment=environment)
    for record in records:
        for key in ["mode", "run", "batch", "worker"]:
            summary[key] = record[key]
        if record["stage"] in ["build_finish", "restore_finish", "overall", "save_host_counters"]:
            summary.update({key: value for key, value in record.items() if key not in ["timestamp", "stage", "run_id"]})
        elif record["stage"] == "image":
            name = "admin" if "shenyu-admin" in record["image"] else "bootstrap"
            summary[name + "_image_bytes"] = record["image_bytes"]
            summary[name + "_image_id"] = record["image_id"]
        elif record["stage"] == "docker_save":
            summary[record["image"] + "_tar_bytes"] = record["tar_bytes"]
        elif record["stage"] == "after_build":
            summary["m2_raw_bytes"] = record["m2_raw_bytes"]
        elif record["stage"] == "before_restore":
            summary["initial_m2_raw_bytes"] = record["m2_raw_bytes"]
    for field, step_name in [("restore_seconds", "Restore ShenYu Maven Repos"),
                             ("save_seconds", "Save ShenYu Maven Repos")]:
        step = next(item for item in job["steps"] if item["name"] == step_name)
        summary[field] = seconds(step["started_at"], step["completed_at"]) if step["conclusion"] != "skipped" else 0
        summary[field + "_started_at"] = step["started_at"]
        summary[field + "_completed_at"] = step["completed_at"]
    save_lines = [line for line in logs.decode("utf-8", errors="replace").splitlines()
                  if "Cache saved" in line or "Unable to reserve cache" in line or "Failed to save:" in line or "Cache Size:" in line or "Sent " in line]
    (directory / "cache-log-lines.txt").write_text("\n".join(save_lines), encoding="utf-8")
    log_text = logs.decode("utf-8", errors="replace")
    summary["save_result"] = ("skipped" if summary.get("save_outcome") == "skipped" else
                              "saved" if "Cache saved with key:" in log_text else
                              "already_exists" if "another job may be creating this cache" in log_text or "already exists" in log_text else "not_saved_or_unclassified")
    inventories = list((directory / "artifact").rglob("cache-inventory-after.json"))
    if inventories:
        entries = json.loads(inventories[0].read_text())["actions_caches"]
        entry = next((item for item in entries if item["key"] == summary.get("cache_key") and item["ref"] == "refs/heads/" + BRANCH), None)
        if entry:
            summary["cache_compressed_bytes"] = entry["size_in_bytes"]
            summary["cache_entry_id"] = entry["id"]
            summary["seed_normalized_raw_ratio"] = summary.get("m2_raw_bytes", 0) / entry["size_in_bytes"]
            summary["compression_ratio"] = summary["seed_normalized_raw_ratio"] if summary["save_result"] == "saved" else None
    save(directory / "summary.json", summary)
    with (directory / "metric-lines.txt").open("w", encoding="utf-8") as output:
        output.write("\n".join(line for line in log_text.splitlines() if "METRIC|" in line or "DISK_SAMPLE|" in line))
        output.write("\nMETRIC|" + "|".join(f"{key}={summary.get(key, 'unavailable')}" for key in
                     ["run_id", "job_id", "mode", "queue_seconds", "restore_seconds", "save_seconds", "save_result", "cache_compressed_bytes"]) + "\n")
    print("Collected " + json.dumps({key: summary.get(key) for key in
          ["run_id", "mode", "build_seconds", "restore_seconds", "save_seconds", "save_result", "cache_hit", "m2_raw_bytes", "cache_compressed_bytes", "min_free_disk_bytes", "exit_code"]}), flush=True)
    return summary


def verify(summary):
    if summary["source_commit"] != SOURCE or summary["conclusion"] != "success" or summary.get("exit_code") != 0:
        raise RuntimeError("STOP: failed/incomplete first-round workload; inspect preserved run")
    if summary["mode"] in ["A", "B"] and summary["initial_m2_raw_bytes"] != 0:
        raise RuntimeError("STOP: cold mode unexpectedly started with a populated Maven repository")
    if summary["mode"] in ["C", "D"] and summary.get("cache_hit") != "true":
        raise RuntimeError("STOP: warm mode did not restore the controlled seed")
    if summary.get("http_429_count", 0) >= 3 or summary.get("retry_count", 0) >= 2:
        raise RuntimeError("STOP: repeated network failures observed; no further load")
    if summary["min_free_disk_bytes"] < 15 * 1024 ** 3:
        raise RuntimeError("STOP: free disk below the benchmark safety envelope")
    previous = [json.loads(path.read_text(encoding="utf-8")) for path in (ROOT / "runs").glob("*/summary.json")]
    for other in previous:
        if other.get("excluded") or other["run_id"] == summary["run_id"]:
            continue
        for field in ["java", "mvnd", "image_version", "cpu_count"]:
            if other["environment"].get(field) != summary["environment"].get(field):
                raise RuntimeError(f"STOP: environment changed: {field}")


def experiment(first_run):
    inventory("start")
    first = collect(first_run)
    verify(first)
    for sample, modes in [(1, "BCD"), (2, "BDAC"), (3, "DCBA")]:
        for mode in modes:
            inventory(f"before-{mode}-{sample}")
            run_id = dispatch(mode, sample)
            wait([run_id])
            verify(collect(run_id))
    for concurrency in [1, 2, 4]:
        inventory(f"before-concurrent-{concurrency}")
        run_ids = [dispatch("C", 1, f"concurrent-{concurrency}", worker)
                   for worker in range(1, concurrency + 1)]
        wait(run_ids)
        for run_id in run_ids:
            verify(collect(run_id))
    inventory("finish")


if __name__ == "__main__":
    ROOT.mkdir(exist_ok=True)
    try:
        if sys.argv[1] == "collect":
            collect(int(sys.argv[2]), excluded="--excluded" in sys.argv)
        elif sys.argv[1] == "experiment":
            experiment(int(sys.argv[2]))
        elif sys.argv[1] == "inventory":
            inventory(sys.argv[2])
    except Exception as error:
        save(ROOT / "blocker.json", dict(timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                         error=str(error)))
        raise
