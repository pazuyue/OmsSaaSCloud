"""Exercise the real local login flow without disabling captcha."""
import json
import subprocess
import urllib.request

BASE = 'http://localhost:8088'
def request(path, payload=None, token=None):
    headers = {}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)

with urllib.request.urlopen(BASE, timeout=15) as response:
    assert response.status == 200 and b'<html' in response.read(), 'Frontend missing'
print('PASS frontend HTML')
captcha = request('/prod-api/code')
assert captcha['code'] == 200 and captcha.get('img'), captcha
print('PASS captcha image')
code = subprocess.check_output(['docker', 'exec', 'oms-local-redis', 'redis-cli', '--raw', 'GET', 'captcha_codes:' + captcha['uuid']], text=True).strip().strip('"')
login = request('/prod-api/auth/login', {'username': 'admin', 'password': 'admin123', 'uuid': captcha['uuid'], 'code': code})
assert login['code'] == 200, login
token = login['data']['access_token']
print('PASS admin login')
for path in ['/system/user/getInfo', '/system/menu/getRouters', '/system/user/list', '/system/role/list', '/system/dict/type/list', '/code/gen/list', '/schedule/job/list']:
    result = request('/prod-api' + path, token=token)
    assert result['code'] == 200, (path, result)
    print('PASS', path)
request('/prod-api/auth/logout', {}, token)
print('PASS logout')
