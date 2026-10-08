#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../ruoyi-ui"
mkdir -p ../docker/local/logs
if [[ ! -d node_modules ]]; then
    npm ci --no-audit --no-fund
fi
NODE_OPTIONS=--openssl-legacy-provider npm run build:prod -- --dest ../docker/local/dist-next > ../docker/local/logs/frontend-build.log 2>&1
python3 ../docker/local/publish_frontend.py
