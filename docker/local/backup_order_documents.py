"""Back up the local order database and deployed artifacts before document rollout."""
import shutil
import subprocess
from datetime import datetime
from local_db import HERE, docker_db


def main():
    folder = HERE / 'backups' / ('order-documents-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True)
    with (folder / 'order.sql').open('xb') as output:
        docker_db('mysqldump', '--single-transaction', '--hex-blob', '--default-character-set=utf8mb4', '--databases', 'qm_oms_saas_order', output=output)
    subprocess.run(['docker', 'cp', 'oms-local-order:/app/app.jar', str(folder / 'order.jar')], check=True, stdout=subprocess.DEVNULL)
    shutil.copytree(HERE / 'dist', folder / 'dist')
    shutil.copy2(HERE / 'generated/tracing.compose.json', folder / 'tracing.compose.json')
    assert (folder / 'order.sql').stat().st_size > 0
    print('Order document rollback backup:', folder)


if __name__ == '__main__':
    main()
