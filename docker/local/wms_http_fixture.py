"""Temporary WMS stub for local acceptance; binds only the Docker bridge address."""
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path
import subprocess,json,hashlib,urllib.parse

folder=Path(__file__).parent/'logs'
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body=self.rfile.read(int(self.headers['Content-Length'])).decode()
        params=dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query))
        raw='ui-test-secret'+''.join(k+v for k,v in sorted(params.items()) if k!='sign' and v)+body+'ui-test-secret'
        valid=hashlib.md5(raw.encode()).hexdigest().upper()==params.get('sign')
        (folder/'wms-http-last-request.json').write_text(json.dumps({'valid':valid,'body':body,'method':params.get('method')},ensure_ascii=False),encoding='utf-8')
        if params.get('format')=='json':reply=json.dumps({'flag':'success' if valid else 'failure','entryOrderId':'UI-EXT-ORDER','status':'ACCEPT'})
        else:reply='<response><flag>'+('success' if valid else 'failure')+'</flag><entryOrderId>UI-EXT-ORDER</entryOrderId><status>ACCEPT</status></response>'
        payload=reply.encode();self.send_response(200);self.end_headers();self.wfile.write(payload)
    def log_message(self,*args):pass

if __name__=='__main__':
    gateway=subprocess.check_output(['docker','inspect','oms-local-supplychain','--format','{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}'],text=True).strip()
    server=HTTPServer((gateway,0),Handler)
    folder.mkdir(exist_ok=True)
    (folder/'wms-http-endpoint.txt').write_text(f'http://{gateway}:{server.server_port}/router',encoding='utf-8')
    print('WMS HTTP fixture ready; stop with Ctrl+C after acceptance',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
