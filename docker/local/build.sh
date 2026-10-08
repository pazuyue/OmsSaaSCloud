#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
mkdir -p docker/local/logs docker/local/artifacts
BUILD_HOME=${OMS_BUILD_HOME:-$HOME/.cache/oms-local}
mkdir -p "$BUILD_HOME/source" "$BUILD_HOME/m2"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
# Build on Linux storage: existing NTFS targets can be locked by Windows tools.
python3 docker/local/prune_backend_cache.py "$ROOT" "$BUILD_HOME/source"
tar --exclude=target --exclude=node_modules --exclude=dist --exclude=.git -cf - pom.xml ruoyi-api ruoyi-auth ruoyi-common ruoyi-gateway ruoyi-modules ruoyi-visual oms-modules | tar -xf - -C "$BUILD_HOME/source"
if [[ -x "$ROOT/docker/local/tools/apache-maven-3.9.9/bin/mvn" ]]; then
    MVN=${MVN:-$ROOT/docker/local/tools/apache-maven-3.9.9/bin/mvn}
else
    MVN=${MVN:-mvn}
fi
"$MVN" -B -f "$BUILD_HOME/source/pom.xml" -Plocal-docker -Dmaven.repo.local="$BUILD_HOME/m2" -Dmaven.test.skip=true clean install "$@" > docker/local/logs/backend-build.log 2>&1
# Replace each artifact atomically; running containers retain their mounted inode
# until recreated, rather than reading a partially overwritten JAR.
python3 - "$BUILD_HOME/source" "$ROOT/docker/local/artifacts" <<'PY'
import shutil
import sys
from pathlib import Path
source, destination = map(Path, sys.argv[1:])
for artifact in source.glob('**/target/*.jar'):
    temporary = destination / (artifact.name + '.next')
    shutil.copy2(artifact, temporary)
    temporary.replace(destination / artifact.name)
PY
echo 'Backend build complete: docker/local/artifacts'
