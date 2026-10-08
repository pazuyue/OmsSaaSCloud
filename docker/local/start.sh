#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
python3 prepare.py
python3 - <<'PY'
import json
from pathlib import Path
for service in json.loads(Path('compose.yaml').read_text())['services'].values():
    for mount in service.get('volumes', []):
        if mount.startswith('./artifacts/'):
            artifact = Path(mount.split(':', 1)[0])
            if not artifact.is_file():
                raise SystemExit(f'Missing {artifact}; run bash docker/local/build.sh first.')
if not Path('dist/index.html').is_file():
    raise SystemExit('Missing frontend; run bash docker/local/build-frontend.sh first.')
PY
docker compose -f compose.yaml config --quiet
docker compose -f compose.yaml up -d --wait --wait-timeout 180 mysql redis nacos
python3 configure.py
docker compose -f compose.yaml up -d --wait --wait-timeout 300
