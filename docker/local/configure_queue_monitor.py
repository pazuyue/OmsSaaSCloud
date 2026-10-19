"""Provision a read-only local broker monitoring identity; credentials stay in .env."""
import subprocess
from local_db import ENV

def run(*args,check=True):
 r=subprocess.run(['docker','exec','oms-local-rabbitmq','rabbitmqctl',*args],capture_output=True)
 if check and r.returncode:raise RuntimeError('Local monitor identity provisioning failed')
 return r
if __name__=='__main__':
 user=ENV['RABBITMQ_MONITOR_USER'];password=ENV['RABBITMQ_MONITOR_PASSWORD']
 exists=user in run('list_users','-q').stdout.decode()
 if exists:run('change_password',user,password)
 else:run('add_user',user,password)
 run('set_user_tags',user,'monitoring');run('set_permissions','-p','oms',user,'^$','^$','^$')
 print('Read-only RabbitMQ monitoring identity ready')
