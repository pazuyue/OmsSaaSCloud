"""Generate isolated local Compose and Nacos seed data from repository sources."""
import hashlib
import json
import re
import secrets
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = HERE / 'generated'
OUT.mkdir(exist_ok=True)
env_file = HERE / '.env'
if not env_file.exists():
    env_file.write_text('MYSQL_ROOT_PASSWORD=' + secrets.token_hex(16) + '\n', encoding='utf-8')
local_env = dict(line.split('=', 1) for line in env_file.read_text().splitlines() if '=' in line and not line.startswith('#'))
password = local_env['MYSQL_ROOT_PASSWORD']

sql = (ROOT / 'sql/ry_config_20231204.sql').read_text(encoding='utf-8')
rows = re.findall(r"\(\d+,'([^']+)','DEFAULT_GROUP','((?:\\.|[^'\\])*)'", sql)
def unescape(value):
    mapping = {'n': '\n', 'r': '\r', 't': '\t', '0': '\0'}
    return re.sub(r'\\(.)', lambda m: mapping.get(m[1], m[1]), value)
configs = {name: unescape(content) for name, content in rows}
assert len(configs) == 9, 'Unexpected Nacos SQL seed format'
for name, content in configs.items():
    content = content.replace('host: localhost', 'host: redis')
    content = content.replace('jdbc:mysql://localhost:', 'jdbc:mysql://mysql:').replace('useSSL=true', 'useSSL=false')
    content = content.replace('password: password', 'password: ' + password)
    configs[name] = content.replace('\nmybatis:', '\nmybatis-plus:')
configs['application-dev.yml'] += '\nspring.cloud.sentinel.enabled: false\nspring.cloud.sentinel.eager: false\nmanagement.endpoints.web.exposure.include: health,info\n'
configs['ruoyi-file-dev.yml'] = '''file:
  domain: http://localhost:8088/prod-api/file
  path: /home/ruoyi/uploadPath
  prefix: /statics
fdfs:
  domain: http://localhost
  tracker-list: localhost:22122
minio:
  url: http://localhost:9000
  accessKey: local-unused
  secretKey: local-unused
  bucketName: local-unused
spring:
  redis:
    host: redis
    port: 6379
'''
for prefix, name in [('goods', 'oms-modules-goods'), ('supplychain', 'oms-supplychain'), ('inventory', 'oms-inventory'), ('channel', 'oms-channel')]:
    route = f'''        - id: {name}
          uri: lb://{name}
          predicates:
            - Path=/{prefix}/**
          filters:
            - StripPrefix=1
'''
    configs['ruoyi-gateway-dev.yml'] = configs['ruoyi-gateway-dev.yml'].replace('      routes:\n', '      routes:\n' + route)
    package = {'goods': 'com.oms.goods', 'supplychain': 'com.oms.supplychain', 'inventory': 'com.oms.inventory', 'channel': 'com.oms.channel'}[prefix]
    configs[name + '-dev.yml'] = configs['ruoyi-system-dev.yml'].replace('com.ruoyi.system', package) + '\nmybatis-plus.mapper-locations: classpath*:mapper/**/*.xml\n'
    if local_env.get('OMS_BUSINESS_DATABASES') == '1':
        database = {'goods': 'qm_oms_saas_commodity', 'supplychain': 'qm_oms_saas_commodity', 'inventory': 'qm_oms_saas_inventory', 'channel': 'qm_oms_saas_channel'}[prefix]
        configs[name + '-dev.yml'] = configs[name + '-dev.yml'].replace('/ry-cloud?', '/' + database + '?')

def quote(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"
updates = []
for name, content in configs.items():
    updates.append('INSERT INTO config_info (data_id, group_id, content, md5, gmt_create, gmt_modified, tenant_id, type, encrypted_data_key) VALUES (' + ','.join([quote(name), quote('DEFAULT_GROUP'), quote(content), quote(hashlib.md5(content.encode()).hexdigest()), 'NOW()', 'NOW()', "''", quote('yaml' if name.endswith('.yml') else 'json'), "''"]) + ') ON DUPLICATE KEY UPDATE content=VALUES(content), md5=VALUES(md5), gmt_modified=NOW();')
seed = 'CREATE DATABASE IF NOT EXISTS `ry-cloud` CHARACTER SET utf8mb4;\nUSE `ry-cloud`;\n'
seed += (ROOT / 'sql/ry_20240529.sql').read_text(encoding='utf-8')
seed += '\nALTER TABLE sys_user ADD COLUMN login_company_code VARCHAR(64) DEFAULT NULL;\n'
seed += (ROOT / 'sql/quartz.sql').read_text(encoding='utf-8')
seed += '\n' + sql + '\nUSE `ry-config`;\n' + '\n'.join(updates)
(OUT / 'init.sql').write_text(seed, encoding='utf-8')
(OUT / 'nacos.json').write_text(json.dumps(configs, ensure_ascii=False), encoding='utf-8')

health = lambda cmd: {'test': cmd, 'interval': '10s', 'timeout': '5s', 'retries': 30, 'start_period': '30s'}
services = {
    'mysql': {'image': 'mysql:5.7', 'environment': {'MYSQL_ROOT_PASSWORD': password, 'TZ': 'Asia/Shanghai'}, 'ports': ['127.0.0.1:13306:3306'], 'volumes': ['mysql-data:/var/lib/mysql', './generated:/docker-entrypoint-initdb.d:ro'], 'command': ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci', '--lower-case-table-names=1'], 'healthcheck': health(['CMD-SHELL', 'MYSQL_PWD="$$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot --database=ry-config -N -e "SELECT COUNT(*) FROM config_info" >/dev/null'])},
    'redis': {'image': 'redis:latest', 'working_dir': '/data', 'command': ['redis-server', '--dir', '/data', '--appendonly', 'yes'], 'ports': ['127.0.0.1:16379:6379'], 'volumes': ['redis-data:/data'], 'healthcheck': health(['CMD', 'redis-cli', 'ping'])},
    'nacos': {'image': 'nacos/nacos-server:v2.2.3', 'environment': {'MODE': 'standalone', 'PREFER_HOST_MODE': 'hostname', 'SPRING_DATASOURCE_PLATFORM': 'mysql', 'MYSQL_SERVICE_HOST': 'mysql', 'MYSQL_SERVICE_DB_NAME': 'ry-config', 'MYSQL_SERVICE_USER': 'root', 'MYSQL_SERVICE_PASSWORD': password, 'MYSQL_SERVICE_DB_PARAM': 'characterEncoding=utf8&connectTimeout=10000&socketTimeout=30000&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai', 'JVM_XMS': '256m', 'JVM_XMX': '512m', 'JVM_XMN': '128m', 'NACOS_AUTH_ENABLE': 'false'}, 'ports': ['127.0.0.1:8848:8848', '127.0.0.1:9848:9848'], 'depends_on': {'mysql': {'condition': 'service_healthy'}}, 'healthcheck': health(['CMD-SHELL', 'curl -fsS http://localhost:8848/nacos/v1/console/health/readiness'])},
}
apps = [
    ('gateway', 'ruoyi-gateway/target/ruoyi-gateway.jar', 8080),
    ('auth', 'ruoyi-auth/target/ruoyi-auth.jar', 9200),
    ('system', 'ruoyi-modules/ruoyi-system/target/ruoyi-modules-system.jar', 9201),
    ('gen', 'ruoyi-modules/ruoyi-gen/target/ruoyi-modules-gen.jar', 9202),
    ('job', 'ruoyi-modules/ruoyi-job/target/ruoyi-modules-job.jar', 9203),
    ('file', 'ruoyi-modules/ruoyi-file/target/ruoyi-modules-file.jar', 9300),
    ('monitor', 'ruoyi-visual/ruoyi-monitor/target/ruoyi-visual-monitor.jar', 9100),
    ('goods', 'oms-modules/oms-goods-administration/target/oms-goods-administration.jar', 9301),
    ('supplychain', 'oms-modules/oms-supplychain/target/oms-supplychain.jar', 9302),
    ('inventory', 'oms-modules/oms-inventory/target/oms-inventory.jar', 9303),
    ('channel', 'oms-modules/oms-channel/target/oms-channel.jar', 9304),
]
for name, jar, port in apps:
    services[name] = {
        'image': '${JAVA_RUNTIME_IMAGE:-docker_ruoyi-auth:latest}',
        'entrypoint': ['java', '-Dfile.encoding=UTF-8', '-Xms64m', '-Xmx384m', '-XX:ActiveProcessorCount=2', '-jar', '/app/app.jar'],
        'working_dir': '/home/ruoyi',
        'environment': {'TZ': 'Asia/Shanghai', 'SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR': 'nacos:8848', 'SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR': 'nacos:8848', 'SPRING_CLOUD_SENTINEL_ENABLED': 'false', 'SPRING_CLOUD_SENTINEL_EAGER': 'false', 'SPRING_REDIS_HOST': 'redis'},
        'volumes': [f'./artifacts/{Path(jar).name}:/app/app.jar:ro'],
        'depends_on': {x: {'condition': 'service_healthy'} for x in ['mysql', 'redis', 'nacos']},
        'healthcheck': health(['CMD-SHELL', f'curl -fsS http://localhost:{port}/actuator/health | grep -q UP']),
    }
    if name == 'gateway':
        services[name]['ports'] = ['127.0.0.1:8080:8080']
    if name == 'monitor':
        services[name]['ports'] = ['127.0.0.1:9100:9100']
        services[name]['healthcheck'] = health(['CMD-SHELL', 'curl -fsS -u ruoyi:123456 http://localhost:9100/actuator/health | grep -q UP'])
    if name == 'file':
        services[name]['volumes'].append('uploads:/home/ruoyi/uploadPath')
services['nginx'] = {'image': 'nginx:latest', 'ports': ['127.0.0.1:8088:80'], 'volumes': ['./dist:/usr/share/nginx/html:ro', './nginx.conf:/etc/nginx/nginx.conf:ro'], 'depends_on': {'gateway': {'condition': 'service_started'}}, 'healthcheck': health(['CMD-SHELL', 'curl -fsS http://localhost/ >/dev/null'])}
for name, service in services.items():
    service['container_name'] = 'oms-local-' + name
    service['restart'] = 'unless-stopped'
    service['logging'] = {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}}
(HERE / 'compose.yaml').write_text(json.dumps({'name': 'oms-local', 'services': services, 'volumes': {x: {} for x in ['mysql-data', 'redis-data', 'uploads']}}, indent=2).replace(password, '${MYSQL_ROOT_PASSWORD}'), encoding='utf-8')
print('Prepared local Compose and database seed. Existing data volumes are not modified.')
