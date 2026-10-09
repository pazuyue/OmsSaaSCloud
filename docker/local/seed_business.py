"""Complete local OMS schema and UI configuration from the current source code.

Insert missing settings only; preserve existing menu IDs and business rows.
The original business dictionaries were not supplied. The local values follow
SQL comments and Java enums; unspecified goods type remains explicitly unknown.
"""
import json
import re
from local_db import HERE, ROOT, execute, quote

COMMODITY = 'qm_oms_saas_commodity'
INVENTORY = 'qm_oms_saas_inventory'

def ensure_menu(name, parent, path, component=None, kind='C', permission='', order=1):
    where = 'parent_id=' + str(parent) + ' AND menu_type=' + quote(kind)
    where += (' AND perms=' + quote(permission)) if kind == 'F' else (' AND path=' + quote(path))
    found = execute('SELECT menu_id FROM sys_menu WHERE ' + where + ' LIMIT 1').strip()
    if found:
        return int(found)
    columns = 'menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark'
    values = [quote(name), str(parent), str(order), quote(path), quote(component), '1', '1', quote(kind), "'0'", "'0'", quote(permission), quote('example' if kind == 'M' else 'list'), "'local-deploy'", 'NOW()', "'OMS 本地初始化，按当前源码补齐'"]
    return int(execute('INSERT INTO sys_menu (' + columns + ') VALUES (' + ','.join(values) + '); SELECT LAST_INSERT_ID();').strip())

def main():
    execute('''CREATE TABLE IF NOT EXISTS sys_company_model_association_config (
      id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      company_code VARCHAR(64) NOT NULL,
      order_transfer_model BIGINT NOT NULL DEFAULT 1,
      goods_handle_model BIGINT NOT NULL DEFAULT 1,
      create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
      modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY uk_company_code (company_code)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司 OMS 插件配置（依据当前实体与 Mapper 补齐）';''')
    execute('''CREATE TABLE IF NOT EXISTS company_model_association_info (
      id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
      company_code VARCHAR(64) NOT NULL,
      order_transfer_model INT NOT NULL DEFAULT 1,
      UNIQUE KEY uk_company_code (company_code)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧版公司插件关联（依据当前实体补齐）';''', COMMODITY)
    present = execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='qm_oms_saas_inventory' AND table_name='rule_stock_info' AND column_name='type'").strip()
    if present == '0':
        execute("ALTER TABLE rule_stock_info ADD COLUMN `type` INT DEFAULT 0 COMMENT '商品类型；0未指定（原备份缺少类型定义）' AFTER rule_range", INVENTORY)
    companies = execute('SELECT DISTINCT LOWER(company_code) FROM goods_sku_sn_info WHERE company_code IS NOT NULL', COMMODITY).splitlines()
    for company in companies:
        execute('INSERT INTO sys_company_model_association_config (company_code,order_transfer_model,goods_handle_model) SELECT ' + quote(company) + ',1,1 WHERE NOT EXISTS (SELECT 1 FROM sys_company_model_association_config WHERE company_code=' + quote(company) + ')')
        execute('INSERT INTO company_model_association_info (company_code,order_transfer_model) SELECT ' + quote(company) + ',1 WHERE NOT EXISTS (SELECT 1 FROM company_model_association_info WHERE company_code=' + quote(company) + ')', COMMODITY)
    if len(companies) != 1:
        raise RuntimeError('Expected exactly one imported company; choose the admin company explicitly.')
    execute('UPDATE sys_user SET login_company_code=' + quote(companies[0]) + " WHERE user_id=1 AND (login_company_code IS NULL OR login_company_code='')")

    dictionaries = json.loads((HERE / 'business-dictionaries.json').read_text(encoding='utf-8'))
    for key, definition in dictionaries.items():
        remark = definition.get('remark', '本地补齐：来自 SQL 字段注释或当前代码枚举')
        execute('INSERT INTO sys_dict_type (dict_name,dict_type,status,create_by,create_time,remark) SELECT ' + ','.join([quote(definition['name']), quote(key), "'0'", "'local-deploy'", 'NOW()', quote(remark)]) + ' WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type=' + quote(key) + ')')
        for index, (value, label) in enumerate(definition['values']):
            execute('INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time,remark) SELECT ' + ','.join([str(index), quote(label), quote(value), quote(key), "'default'", "'N'", "'0'", "'local-deploy'", 'NOW()', quote(remark)]) + ' WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type=' + quote(key) + ' AND dict_value=' + quote(value) + ')')

    # Only standalone pages are menus; the other OMS components require dialog props.
    groups = {'goods': ('商品管理', 'oms-goods'), 'supplychain': ('供应链管理', 'oms-supplychain'), 'inventory': ('库存管理', 'oms-inventory'), 'channel': ('渠道管理', 'oms-channel')}
    parents = {key: ensure_menu(title, 0, path, kind='M', order=10 + index) for index, (key, (title, path)) in enumerate(groups.items())}
    pages = [
        ('goods', '商品资料', 'info', 'oms/goods/info/index', ['GoodsSkuSnInfoController', 'GoodsController']),
        ('goods', '商品分类', 'category', 'oms/goods/category/index', ['GoodsCategoryController']),
        ('goods', '商品颜色', 'color', 'oms/goods/color/index', ['GoodsColorController']),
        ('goods', '商品尺码', 'size', 'oms/goods/size/index', ['GoodsSizeController']),
        ('supplychain', '采购单', 'poInfo', 'oms/poInfo/index', ['PoInfoController', 'NoTicketsController', 'NoTicketsTmpController', 'NoTicketsGoodsController']),
        ('supplychain', '供应商', 'supplier', 'oms/supplier/index', ['SupplierInfoController']),
        ('supplychain', '货主', 'owner', 'oms/owner/index', ['OwnerInfoController']),
        ('supplychain', '实体仓库', 'wmsRealStore', 'oms/wmsRealStore/index', ['WmsRealStoreInfoController']),
        ('supplychain', '虚拟仓库', 'simulationStore', 'oms/simulationStore/index', ['WmsSimulationStoreInfoController']),
        ('supplychain', '出入库单', 'wmsTickets', 'oms/wmsTickets/index', ['WmsTicketsController']),
        ('inventory', '商品库存', 'productInventory', 'oms/productInventory/index', ['ProductInventoryController']),
        ('inventory', '仓库库存', 'wmsInventory', 'oms/wmsInventory/index', ['WmsInventoryController']),
        ('inventory', '分货管理', 'ruleStock', 'oms/ruleStock/index', ['RuleStockInfoController', 'RuleStockInfoHandleController']),
        ('channel', '渠道资料', 'channel', 'oms/channel/index', ['TChannelController']),
        ('system', '企业插件配置', 'companyModelAssociationConfig', 'system/companyModelAssociationConfig/index', ['SysCompanyModelAssociationConfigController']),
    ]
    parents['system'] = int(execute("SELECT menu_id FROM sys_menu WHERE path='system' AND parent_id=0 LIMIT 1").strip())
    controller_sources = {}
    for base in [ROOT / 'oms-modules', ROOT / 'ruoyi-modules/ruoyi-system/src/main/java']:
        for source in base.rglob('*Controller.java'):
            if 'target' not in source.parts:
                controller_sources[source.stem] = source.read_text(encoding='utf-8')
    created_ids = list(parents.values())
    for index, (group, title, path, component, controllers) in enumerate(pages):
        view = ROOT / 'ruoyi-ui/src/views' / (component + '.vue')
        assert view.is_file(), component
        source = '\n'.join(controller_sources[c] for c in controllers)
        permissions = set(re.findall(r'@RequiresPermissions\("([^"]+)"\)', source))
        frontend = '\n'.join(p.read_text(encoding='utf-8') for p in view.parent.glob('*.vue'))
        if path == 'poInfo':
            frontend += (ROOT / 'ruoyi-ui/src/views/oms/noTicketsTmp/index.vue').read_text(encoding='utf-8')
        for block in re.findall(r'v-hasPermi="([^"]+)"', frontend):
            permissions.update(re.findall(r"'([^']+)'", block))
        main_permission = next((p for p in sorted(permissions) if p.endswith(':list')), '')
        menu = ensure_menu(title, parents[group], path, component, permission=main_permission, order=index + 1)
        created_ids.append(menu)
        for permission in sorted(permissions):
            action = permission.rsplit(':', 1)[-1]
            label = {'list': '列表', 'query': '查看', 'add': '新增', 'edit': '修改', 'remove': '删除', 'export': '导出'}.get(action, action)
            created_ids.append(ensure_menu(title + '-' + label, menu, '', kind='F', permission=permission))
    for menu_id in set(created_ids):
        execute(f'INSERT INTO sys_role_menu (role_id,menu_id) SELECT 1,{menu_id} WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id={menu_id})')
    env_path = HERE / '.env'
    text = env_path.read_text()
    if re.search(r'^OMS_BUSINESS_DATABASES=', text, re.M):
        text = re.sub(r'^OMS_BUSINESS_DATABASES=.*$', 'OMS_BUSINESS_DATABASES=1', text, flags=re.M)
    else:
        text = text.rstrip() + '\nOMS_BUSINESS_DATABASES=1\n'
    env_path.write_text(text, encoding='utf-8')
    print('Completed 2 plugin tables, rule type, company binding,', len(dictionaries), 'dictionaries,', len(pages), 'pages and admin permissions.')

if __name__ == '__main__':
    main()
