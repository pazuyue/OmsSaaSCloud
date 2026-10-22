"""Back up local business schemas and running artifacts before lucky-bag rollout."""
import json
import shutil
import subprocess
from datetime import datetime
from local_db import HERE, docker_db


def main():
    folder = HERE / 'backups' / ('lucky-bags-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True)
    with (folder / 'business.sql').open('xb') as out:
        docker_db('mysqldump', '--single-transaction', '--hex-blob', '--default-character-set=utf8mb4', '--databases', 'qm_oms_saas_commodity', 'qm_oms_saas_inventory', 'qm_oms_saas_order', 'qm_oms_saas_channel', output=out)
    for app in ['goods', 'inventory', 'order', 'channel']:
        subprocess.run(['docker', 'cp', 'oms-local-' + app + ':/app/app.jar', str(folder / (app + '.jar'))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.copytree(HERE / 'dist', folder / 'dist')
    override = HERE / 'generated/tracing.compose.json'
    if override.exists():
        shutil.copy2(override, folder / override.name)
    assert (folder / 'business.sql').stat().st_size > 0
    (folder / 'manifest.json').write_text(json.dumps({'schemas_only_install': True, 'historical_backfill': False, 'jobs_changed': False, 'artifacts': ['goods.jar', 'inventory.jar', 'order.jar', 'channel.jar', 'dist'], 'tracing_override_preserved': override.exists()}, indent=2), encoding='utf-8')
    print('Lucky-bag rollback backup:', folder)


if __name__ == '__main__':
    main()
