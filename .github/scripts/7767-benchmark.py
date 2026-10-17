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

"""Read-only probes around the unchanged #7767 Maven/Docker workload."""

import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request


OUT = Path(os.environ["BENCH_OUTPUT"])
M2 = Path.home() / ".m2/repository"
REPOSITORY = "lymerin/shenyu"
BASELINE = "34781a0aea0df7ddd4df10962f814157bb33b304"
WORKLOAD = ["mvnd", "-B", "clean", "install", "-Prelease,docker",
            "-Dmaven.javadoc.skip=true", "-Drat.skip=true", "-Dmaven.test.skip=true",
            "-Djacoco.skip=true", "-DskipITs", "-DskipTests", "-T1C"]


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def metric(**values):
    record = dict(mode=os.environ["MODE"], run=os.environ["SAMPLE"],
                  run_id=os.environ["GITHUB_RUN_ID"], batch=os.environ["BATCH"],
                  worker=os.environ["WORKER"], timestamp=timestamp(), **values)
    with (OUT / "metrics.jsonl").open("a") as output:
        output.write(json.dumps(record) + "\n")
    print("METRIC|" + "|".join(f"{key}={value}" for key, value in record.items()), flush=True)


def command(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print("$ " + " ".join(args), flush=True)
    print(result.stdout, flush=True)
    return result.stdout.strip()


def size(path):
    if not path.exists():
        return 0
    return int(subprocess.check_output(["du", "-sb", str(path)], text=True).split()[0])


def counters():
    disk = shutil.disk_usage("/")
    cpu = list(map(int, Path("/proc/stat").read_text().splitlines()[0].split()[1:9]))
    read_sectors = write_sectors = 0
    for line in Path("/proc/diskstats").read_text().splitlines():
        parts = line.split()
        if (Path("/sys/block") / parts[2]).exists() and not parts[2].startswith(("loop", "ram", "dm-")):
            read_sectors += int(parts[5])
            write_sectors += int(parts[9])
    received = sent = 0
    for line in Path("/proc/net/dev").read_text().splitlines()[2:]:
        interface, values = line.split(":")
        if interface.strip() != "lo":
            fields = values.split()
            received += int(fields[0])
            sent += int(fields[8])
    return dict(root_total_bytes=disk.total, root_used_bytes=disk.used,
                free_disk_bytes=disk.free, cpu_total_ticks=sum(cpu),
                cpu_busy_ticks=sum(cpu) - cpu[3] - cpu[4],
                io_read_bytes=read_sectors * 512, io_write_bytes=write_sectors * 512,
                net_received_bytes=received, net_sent_bytes=sent)


def inventory(label):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}/actions/caches?per_page=100",
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(request) as response:
            data = json.load(response)
        (OUT / f"cache-inventory-{label}.json").write_text(json.dumps(data, indent=2))
        metric(stage="cache_inventory_" + label, cache_count=data["total_count"],
               cache_inventory_bytes=sum(item["size_in_bytes"] for item in data["actions_caches"]))
    except Exception as error:
        metric(stage="cache_inventory_" + label, cache_inventory_unavailable=type(error).__name__)


def snapshot(stage):
    values = counters()
    values.update(m2_raw_bytes=size(M2), workspace_bytes=size(Path.cwd()))
    (OUT / (stage + ".json")).write_text(json.dumps(values, indent=2))
    metric(stage=stage, **values)
    command(["df", "-h", "/"])
    command(["df", "-B1", "/"])
    command(["du", "-sh", "."])
    command(["du", "-sb", "."])
    if M2.exists():
        command(["du", "-sh", str(M2)])
        command(["du", "-sb", str(M2)])
    command(["docker", "system", "df"])
    if stage == "after_build":
        command(["docker", "image", "ls"])
        for image in ["apache/shenyu-admin:latest", "apache/shenyu-bootstrap:latest"]:
            info = json.loads(subprocess.check_output(["docker", "image", "inspect", image], text=True))[0]
            metric(stage="image", image=image, image_id=info["Id"], image_bytes=info["Size"])


def sample():
    with (OUT / "disk-samples.jsonl").open("a", buffering=1) as output:
        while not (OUT / "stop-sampling").exists():
            record = dict(timestamp=timestamp(), **counters())
            output.write(json.dumps(record) + "\n")
            print(f"DISK_SAMPLE|mode={os.environ['MODE']}|timestamp={record['timestamp']}|available_bytes={record['free_disk_bytes']}", flush=True)
            time.sleep(5)


def build():
    snapshot("before_build")
    args = WORKLOAD if os.environ["MODE"] == "A" else ["bash", ".github/scripts/maven-with-retry.sh", *WORKLOAD]
    started = time.monotonic()
    metric(stage="build_start", command=json.dumps(args))
    with (OUT / "maven.log").open("w") as log:
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        for line in process.stdout:
            log.write(line)
            print(line, end="", flush=True)
        status = process.wait()
    text = (OUT / "maven.log").read_text()
    errors = {
        "central_downloading": r"Downloading from central:",
        "central_downloaded": r"Downloaded from central:",
        "connection_reset_count": r"Connection reset",
        "http_429_count": r"(?:[Ss]tatus(?: code)?:|Return code is:)\s*429\b",
        "http_502_count": r"(?:[Ss]tatus(?: code)?:|Return code is:)\s*502\b",
        "http_404_count": r"(?:[Ss]tatus(?: code)?:|Return code is:)\s*404\b",
        "artifact_not_found_count": r"Could not find artifact",
        "artifact_transfer_failure_count": r"Could not transfer artifact",
        "retry_count": r"Maven Central transfer failed; retrying in",
        "build_success_count": r"BUILD SUCCESS",
        "build_failure_count": r"BUILD FAILURE",
    }
    metric(stage="build_finish", build_seconds=round(time.monotonic() - started, 3),
           exit_code=status, **{key: len(re.findall(pattern, text)) for key, pattern in errors.items()})
    (OUT / "maven-total-times.json").write_text(json.dumps(re.findall(r"Total time:\s*([^\n\r]+)", text)))
    sys.exit(status)


def main():
    if os.environ["GITHUB_REPOSITORY"] != REPOSITORY:
        raise SystemExit("STOP: benchmark repository is not lymerin/shenyu")
    action = sys.argv[1]
    if action == "sample":
        sample()
    elif action == "init":
        OUT.mkdir(parents=True)
        source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        (OUT / "environment.json").write_text(json.dumps(dict(
            source_commit=source, baseline_commit=BASELINE,
            runner=os.environ.get("RUNNER_NAME"), runner_os=os.environ.get("RUNNER_OS"),
            image_os=os.environ.get("ImageOS"), image_version=os.environ.get("ImageVersion"),
            cpu_count=os.cpu_count(), clock_ticks=os.sysconf("SC_CLK_TCK"),
            mode=os.environ["MODE"], sample=os.environ["SAMPLE"],
            batch=os.environ["BATCH"], worker=os.environ["WORKER"]), indent=2))
        with (OUT / "disk-samples.log").open("w") as log:
            process = subprocess.Popen([sys.executable, __file__, "sample"], stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        (OUT / "sampler.pid").write_text(str(process.pid))
        snapshot("checkout")
        inventory("before")
    elif action == "environment":
        environment = json.loads((OUT / "environment.json").read_text())
        environment.update(java=command(["java", "-version"]), mvnd=command(["mvnd", "--version"]),
                           docker=command(["docker", "version"]), workload=WORKLOAD)
        (OUT / "environment.json").write_text(json.dumps(environment, indent=2))
    elif action == "mark":
        (OUT / (sys.argv[2] + ".time")).write_text(str(time.time()))
        metric(stage=sys.argv[2])
    elif action == "snapshot":
        snapshot(sys.argv[2])
    elif action == "restored":
        snapshot("after_restore")
        metric(stage="restore_finish", cache_hit=os.environ["CACHE_HIT"] or "not_requested",
               cache_matched_key=os.environ["CACHE_MATCHED_KEY"], cache_key=os.environ["CACHE_PRIMARY_KEY"],
               restore_envelope_seconds=round(time.time() - float((OUT / "restore_start.time").read_text()), 3))
    elif action == "build":
        build()
    elif action == "docker-save":
        started = time.monotonic()
        for name in ["bootstrap", "admin"]:
            target = Path(f"/tmp/apache-shenyu-{name}.tar")
            before = time.monotonic()
            with target.open("wb") as output:
                subprocess.run(["docker", "save", f"apache/shenyu-{name}:latest"], stdout=output, check=True)
            metric(stage="docker_save", image=name, tar_bytes=target.stat().st_size,
                   docker_save_seconds=round(time.monotonic() - before, 3))
            command(["ls", "-lh", str(target)])
            command(["du", "-b", str(target)])
        metric(stage="docker_save_finish", docker_save_total_seconds=round(time.monotonic() - started, 3))
        snapshot("after_docker_save")
    elif action == "finalize":
        (OUT / "stop-sampling").touch()
        snapshot("final")
        samples = OUT / "disk-samples.jsonl"
        records = [json.loads(line) for line in samples.read_text().splitlines()] if samples.exists() else []
        stages = [json.loads(path.read_text()) for path in OUT.glob("*.json")
                  if path.name in {name + ".json" for name in ["checkout", "after_cleanup", "before_restore", "after_restore", "before_build", "after_build", "before_save", "after_save", "after_docker_save", "final"]}]
        metric(stage="overall", min_free_disk_bytes=min(record["free_disk_bytes"] for record in records + stages),
               restore_outcome=os.environ["RESTORE_OUTCOME"], save_outcome=os.environ["SAVE_OUTCOME"],
               cache_key=os.environ["CACHE_PRIMARY_KEY"])
        if (OUT / "before_save.json").exists() and (OUT / "after_save.json").exists():
            before = json.loads((OUT / "before_save.json").read_text())
            after = json.loads((OUT / "after_save.json").read_text())
            metric(stage="save_host_counters", **{key + "_delta": after[key] - before[key]
                   for key in ["cpu_busy_ticks", "io_read_bytes", "io_write_bytes", "net_received_bytes", "net_sent_bytes"]})
        inventory("after")
        if (OUT / "disk-samples.log").exists():
            print((OUT / "disk-samples.log").read_text(), flush=True)


if __name__ == "__main__":
    main()
