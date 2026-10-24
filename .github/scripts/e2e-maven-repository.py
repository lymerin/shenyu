#!/usr/bin/env python3
#
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

"""Prepare and restore the Maven inputs for the selected E2E matrix."""

import json
import os
from pathlib import Path
import sys
import tarfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


def plan(directory, source):
    source = source.resolve(strict=True)
    examples = set()
    tests = set()
    for item in json.loads(os.environ.get("E2E_MATRIX", '{"include":[]}'))["include"]:
        examples.update(item["example_projects"].split(","))
        tests.add("shenyu-e2e-case/" + item["case"])
    for item in json.loads(os.environ.get("STORAGE_MATRIX", '{"include":[]}'))["include"]:
        tests.add("shenyu-e2e-case/" + item["case"])
    if not tests:
        raise ValueError("The selected E2E matrix is empty")
    (directory / "example-projects").write_text(",".join(sorted(examples)), encoding="utf-8")
    (directory / "test-projects").write_text(",".join(sorted(tests)), encoding="utf-8")
    # Both producer and consumer use the same repository IDs, preserving Maven's
    # repository-origin tracking. Central is restricted to releases; built
    # snapshots are read from this workflow's successful prerequisite build.
    repository = f"""<repository><id>e2e-built-inputs</id><url>{escape(source.as_uri())}</url>
      <releases><enabled>true</enabled><checksumPolicy>ignore</checksumPolicy></releases>
      <snapshots><enabled>true</enabled><checksumPolicy>ignore</checksumPolicy></snapshots></repository>"""
    plugin_repository = repository.replace("repository>", "pluginRepository>")
    (directory / "settings.xml").write_text(f"""<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles><profile><id>e2e-inputs</id>
    <repositories>{repository}
      <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url>
        <snapshots><enabled>false</enabled></snapshots></repository>
    </repositories>
    <pluginRepositories>{plugin_repository}</pluginRepositories>
  </profile></profiles>
  <activeProfiles><activeProfile>e2e-inputs</activeProfile></activeProfiles>
</settings>
""", encoding="utf-8")


def build_tools(directory, pom):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    artifacts = set()
    for plugin in ET.parse(pom).findall(".//m:build/m:plugins/m:plugin", ns):
        if plugin.findtext("m:artifactId", namespaces=ns) == "protobuf-maven-plugin":
            for name in ("protocArtifact", "pluginArtifact"):
                artifacts.update(item.text for item in plugin.findall("m:configuration/m:" + name, ns))
            for compiler in plugin.findall("m:configuration/m:protocPlugins/m:protocPlugin", ns):
                artifacts.add(":".join(compiler.findtext("m:" + name, namespaces=ns)
                                       for name in ("groupId", "artifactId", "version")) + ":jar")
    (directory / "build-tools").write_text("".join(artifact + "\n" for artifact in sorted(artifacts)), encoding="utf-8")


def pack(directory, maven_user_home):
    repository = directory / "repository"
    version = (directory / "maven-version.txt").read_text(encoding="utf-8")
    maven_home = Path(next(
        line.removeprefix("Maven home:").strip()
        for line in version.splitlines() if line.startswith("Maven home:")))
    distribution = maven_home.parent
    relative = distribution.relative_to(maven_user_home.resolve())
    if relative.parts[:2] != ("wrapper", "dists") or not (maven_home / "bin/mvn").is_file():
        raise ValueError("Unexpected Maven wrapper distribution: " + str(maven_home))
    payload = [
        (repository, Path("repository")),
        (distribution, Path("maven-user-home") / relative),
        (Path(".mvn/wrapper/maven-wrapper.jar"), Path("wrapper/maven-wrapper.jar")),
    ]
    archive = directory / "repository.tar.gz"
    with tarfile.open(archive, "w:gz") as output:
        for source, target in payload:
            output.add(source, arcname=str(target))
    print("Prepared the selected E2E Maven repository and wrapper")


def restore(directory, destination):
    archive = directory / "repository.tar.gz"
    if destination.exists():
        raise ValueError("E2E Maven repository destination must not exist: " + str(destination))
    destination.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as source:
        source.extractall(destination, filter="data")
    repository = destination / "repository"
    namespace = "http://maven.apache.org/SETTINGS/1.0.0"
    ET.register_namespace("", namespace)
    settings = ET.parse(directory / "settings.xml")
    ns = {"s": namespace}
    repositories = settings.findall(".//s:repository", ns) + settings.findall(".//s:pluginRepository", ns)
    for item in repositories:
        if item.find("s:id", ns).text == "e2e-built-inputs":
            item.find("s:url", ns).text = repository.as_uri()
    ET.SubElement(settings.getroot(), f"{{{namespace}}}offline").text = "true"
    ET.SubElement(settings.getroot(), f"{{{namespace}}}localRepository").text = str(repository)
    settings.write(destination / "settings.xml", encoding="utf-8", xml_declaration=True)
    print("Restored E2E Maven inputs with offline settings")


if __name__ == "__main__":
    command, artifact = sys.argv[1:3]
    directory = Path(artifact).resolve()
    if command == "plan":
        plan(directory, Path(sys.argv[3]))
    elif command == "build-tools":
        build_tools(directory, Path(sys.argv[3]))
    elif command == "pack":
        pack(directory, Path(sys.argv[3]))
    elif command == "restore":
        restore(directory, Path(sys.argv[3]).resolve())
    else:
        raise ValueError("Unknown E2E Maven repository command: " + command)
