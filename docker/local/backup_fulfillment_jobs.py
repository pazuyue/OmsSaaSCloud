from datetime import datetime
from local_db import HERE, docker_db

target=HERE/'backups'/('fulfillment-jobs-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
target.mkdir(parents=True,exist_ok=False)
with (target/'scheduler.sql').open('wb') as output:
    docker_db('mysqldump','--default-character-set=utf8mb4','--skip-lock-tables','--no-tablespaces','ry-cloud','sys_job','sys_job_log',output=output)
print('Scheduler backup:',target.relative_to(HERE))
