"""Opt-in local SkyWalking deployment. Invoke through WSL, from any directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
GENERATED = HERE / 'generated'
RUNTIME = HERE / 'tracing/runtime'
OVERLAY = GENERATED / 'tracing.compose.json'
MARKER = GENERATED / 'tracing-enabled'
AGENT_VERSION = '9.5.0'
ARTIFACT = f'apache-skywalking-java-agent-{AGENT_VERSION}.tgz'
AGENT_URL = f'https://archive.apache.org/dist/skywalking/java-agent/{AGENT_VERSION}/{ARTIFACT}'


def run(*command, **kwargs):
    return subprocess.run(command, cwd=HERE, check=True, **kwargs)


def environment():
    values = {}
    if (HERE / '.env').exists():
        values.update(line.split('=', 1) for line in (HERE / '.env').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    values.update(os.environ)
    return values


def download_archive(archive, expected):
    if archive.exists() and hashlib.sha512(archive.read_bytes()).hexdigest() == expected:
        return
    # Apache's archive mirror limits each connection. Fetch bounded, verified ranges.
    from concurrent.futures import ThreadPoolExecutor
    request = urllib.request.Request(AGENT_URL, method='HEAD')
    with urllib.request.urlopen(request, timeout=30) as response:
        size = int(response.headers['Content-Length'])
    parts = RUNTIME / 'download-parts'
    parts.mkdir(exist_ok=True)
    block = 1024 * 1024

    def fetch(start):
        end = min(start + block, size) - 1
        part = parts / str(start)
        if not part.exists() or part.stat().st_size != end - start + 1:
            run('curl', '--silent', '--show-error', '-fL', '--connect-timeout', '15',
                '--max-time', '120', '--retry', '2', '--range', f'{start}-{end}',
                '-o', str(part), AGENT_URL)
        if part.stat().st_size != end - start + 1:
            raise RuntimeError('Incomplete Agent download range: ' + str(start))
        print('Agent download range verified:', start, flush=True)
        return part

    with ThreadPoolExecutor(max_workers=12) as workers:
        downloaded = list(workers.map(fetch, range(0, size, block)))
    with archive.open('wb') as output:
        for part in downloaded:
            with part.open('rb') as source:
                shutil.copyfileobj(source, output)
    if hashlib.sha512(archive.read_bytes()).hexdigest() != expected:
        raise SystemExit('Agent SHA-512 verification failed')


def prepare_agent():
    if (RUNTIME / 'version').exists() and (RUNTIME / 'version').read_text().strip() == AGENT_VERSION:
        for kind in ('default', 'gateway'):
            if not (RUNTIME / kind / 'skywalking-agent.jar').is_file():
                raise SystemExit('Incomplete Agent directory; inspect docker/local/tracing/runtime')
            shutil.copy2(HERE / 'tracing/apm-trace-ignore-plugin.config',RUNTIME / kind / 'config/apm-trace-ignore-plugin.config')
        return
    RUNTIME.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='skywalking-', dir=RUNTIME) as temporary:
        directory = Path(temporary)
        archive = RUNTIME / 'agent-download.tgz'
        print('Downloading Apache SkyWalking Java Agent', AGENT_VERSION, flush=True)
        with urllib.request.urlopen(AGENT_URL + '.sha512', timeout=30) as response:
            checksum = response.read().decode().strip()
        # Apache checksum files may contain only the digest or a filename followed by it.
        import re
        expected = re.search(r'\b[0-9a-fA-F]{128}\b', checksum)
        if not expected:
            raise SystemExit('Invalid Apache SHA-512 checksum file')
        download_archive(archive, expected.group().lower())
        with tarfile.open(archive) as bundle:
            for member in bundle.getmembers():
                target = (directory / member.name).resolve()
                if directory.resolve() not in target.parents or member.issym() or member.islnk():
                    raise SystemExit('Unsafe Agent archive entry')
            bundle.extractall(directory)
        source = next(directory.glob('**/skywalking-agent.jar')).parent
        for kind in ('default', 'gateway'):
            destination = RUNTIME / kind
            if destination.exists():
                raise SystemExit(f'{destination} already exists without a matching version marker')
            shutil.copytree(source, destination)
            plugins = destination / 'plugins'
            patterns = ['apm-trace-ignore-plugin-*.jar']
            if kind == 'gateway':
                patterns += ['apm-spring-cloud-gateway-3.x-plugin-*.jar']
            for pattern in patterns:
                matches = list((destination / 'optional-plugins').glob(pattern))
                if len(matches) != 1:
                    raise SystemExit(f'Expected one optional plugin: {pattern}, got {len(matches)}')
                shutil.copy2(matches[0], plugins)
            shutil.copy2(HERE / 'tracing/apm-trace-ignore-plugin.config',destination / 'config/apm-trace-ignore-plugin.config')
        (RUNTIME / 'version').write_text(AGENT_VERSION + '\n')
        (RUNTIME / 'sha512').write_text(expected.group().lower() + '\n')


def java_services():
    base = json.loads((HERE / 'compose.yaml').read_text())
    return {name: spec for name, spec in base['services'].items()
            if spec.get('entrypoint', [None])[0] == 'java'}


def overlay(pin=False):
    env = environment()
    stack = json.loads((HERE / 'tracing/stack.json').read_text())
    lock = GENERATED / 'tracing-images.json'
    images = json.loads(lock.read_text()) if lock.exists() else {}
    for spec in stack['services'].values():
        tag = spec['image']
        if pin:
            reference = images.get(tag, tag)
            inspected = subprocess.run(['docker','image','inspect',reference,'--format={{json .RepoDigests}}'],cwd=HERE,capture_output=True,text=True)
            if inspected.returncode:
                run('docker', 'pull', reference)
                inspected = run('docker','image','inspect',reference,'--format={{json .RepoDigests}}',capture_output=True,text=True)
            digests = json.loads(inspected.stdout)
            if not digests:
                raise SystemExit(f'Missing image digest: {tag}')
            images[tag] = digests[0]
        spec['image'] = images.get(tag, tag)
    if pin:
        lock.write_text(json.dumps(images, indent=2) + '\n')
    names = {'gateway':'ruoyi-gateway','auth':'ruoyi-auth','system':'ruoyi-system',
             'gen':'ruoyi-gen','job':'ruoyi-job','file':'ruoyi-file','monitor':'ruoyi-monitor',
             'goods':'oms-modules-goods','inventory':'oms-inventory','channel':'oms-channel',
             'order':'oms-order','supplychain':'oms-supplychain'}
    for name in java_services():
        stack['services'][name] = {
            'environment': {
                'JAVA_TOOL_OPTIONS': '-javaagent:/opt/skywalking/agent/skywalking-agent.jar',
                'SW_AGENT_NAME': env.get('OMS_TRACE_ENV', 'local') + '::' + names[name],
                'SW_AGENT_COLLECTOR_BACKEND_SERVICES': 'skywalking-oap:11800',
                'SW_AGENT_SAMPLE': env.get('OMS_TRACE_SAMPLE', '-1'),
                'SW_AGENT_KEEP_TRACING': 'true',
                'SW_LOGGING_OUTPUT': 'CONSOLE',
                'SW_LOGGING_LEVEL': 'WARN',
                'SW_JDBC_TRACE_SQL_PARAMETERS': 'false',
                'SW_PLUGIN_FEIGN_COLLECT_REQUEST_BODY': 'false',
                'SW_PLUGIN_HTTP_INCLUDE_HTTP_HEADERS': '',
                'SW_PLUGIN_TOMCAT_COLLECT_HTTP_PARAMS': 'false',
                'SW_PLUGIN_SPRINGMVC_COLLECT_HTTP_PARAMS': 'false',
                'OMS_TRACING_UI_URL': env.get('OMS_TRACING_UI_URL', 'http://localhost:18090')
            },
            'volumes': [f'./tracing/runtime/{"gateway" if name == "gateway" else "default"}:/opt/skywalking/agent:ro']
        }
    GENERATED.mkdir(exist_ok=True)
    OVERLAY.write_text(json.dumps(stack, indent=2) + '\n')
    return list(java_services())


def compose(*arguments, traced=True):
    command = ['docker', 'compose', '-f', 'compose.yaml']
    if traced:
        command += ['-f', str(OVERLAY)]
    return run(*command, *arguments)


def migrate():
    from configure_messaging import install
    from configure_tracing_menu import install as install_menu
    from local_db import execute
    # These are the two messaging databases in this local deployment.
    for database in ('qm_oms_saas_channel', 'qm_oms_saas_order'):
        if execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+database+"'").strip() == '1':
            install(database)
    install_menu()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare','enable','disable','status','render','migrate'])
    parser.add_argument('--services', nargs='+', choices=sorted(java_services()), help='Recreate only these Java services during an incremental rollout')
    args = parser.parse_args()
    action = args.action
    if args.services and action != 'enable':
        parser.error('--services is only supported with enable')
    GENERATED.mkdir(exist_ok=True)
    if action in ('prepare', 'enable'):
        prepare_agent()
    if action in ('prepare', 'enable', 'render'):
        services = overlay(pin=action in ('prepare','enable'))
        compose('config', '--quiet')
    if action == 'enable':
        migrate()
        compose('up', '-d', '--wait', '--wait-timeout', '300', 'skywalking-es','skywalking-oap','skywalking-ui')
        compose('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '300', *(args.services or services))
        MARKER.write_text('enabled\n')
        print('SkyWalking enabled: http://localhost:18090')
    elif action == 'disable':
        # Roll back applications first; retain telemetry data and its storage volume.
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '300', *java_services(), traced=False)
        MARKER.unlink(missing_ok=True)
        if OVERLAY.exists():
            compose('stop', 'skywalking-ui','skywalking-oap','skywalking-es')
        print('Agent disabled; SkyWalking data retained')
    elif action == 'status':
        print('Tracing opt-in:', MARKER.exists())
        compose('ps', traced=OVERLAY.exists())
    elif action == 'migrate':
        migrate()


if __name__ == '__main__':
    main()
