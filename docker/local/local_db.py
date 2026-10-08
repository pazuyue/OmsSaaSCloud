"""Database helpers restricted to the local deployment container."""
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ENV = dict(line.split('=', 1) for line in (HERE / '.env').read_text().splitlines() if '=' in line and not line.startswith('#'))

def docker_db(program, *args, sql=None, output=None):
    command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + ENV['MYSQL_ROOT_PASSWORD'], 'oms-local-mysql', program, '-uroot', *args]
    result = subprocess.run(command, input=sql.encode('utf-8') if sql is not None else None, stdout=output if output is not None else subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout.decode('utf-8') if output is None else None

def execute(sql, database='ry-cloud'):
    return docker_db('mysql', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw', '--database=' + database, sql=sql)

def quote(value):
    if value is None:
        return 'NULL'
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"
