import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'docker/local'))
import test_orders
original_run=test_orders.subprocess.run
def local_run(command,*args,**kwargs):
    if isinstance(command,list) and any(str(value).startswith('-Dtest=') for value in command):
        env=dict(kwargs['env'])
        for key in list(env):
            if key.startswith('OMS_ORDER_PUSH_'):env.pop(key)
        kwargs['env']=env
    return original_run(command,*args,**kwargs)
test_orders.subprocess.run=local_run
sys.argv=[sys.argv[0],'--order-only']
test_orders.main()
