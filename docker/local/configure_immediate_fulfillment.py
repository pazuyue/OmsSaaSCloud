from configure_sales_outbound_documents import install_order
install_order('qm_oms_saas_order')
print('Immediate sync progress columns installed; inventory quantities unchanged')

from configure_order import nacos_get,nacos_set
import yaml
content=nacos_get('oms-order-dev.yml');config=yaml.safe_load(content)
if not config.get('oms',{}).get('tenant',{}).get('routes') and 'oms.tenant.routes.QM' not in config:
    config.setdefault('oms',{}).setdefault('tenant',{})['routes']={'QM':'master'}
    nacos_set('oms-order-dev.yml',yaml.safe_dump(config,allow_unicode=True,sort_keys=False))
print('Unified local order company route verified')
