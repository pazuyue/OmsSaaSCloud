"""Regression suites in disposable schemas, with no real platform writes."""
import os,sys,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
from local_db import ROOT,HERE,ENV,execute,test_jdbc_url
from configure_platform_products import install,install_order

def main():
 db='channel_platform_test'
 execute('CREATE DATABASE IF NOT EXISTS '+db+' CHARACTER SET utf8mb4');execute('CREATE DATABASE IF NOT EXISTS message_delivery_test CHARACTER SET utf8mb4')
 execute(f'CREATE TABLE IF NOT EXISTS {db}.t_channel LIKE qm_oms_saas_channel.t_channel')
 from configure_platform_log_center import install as logs
 from configure_exchanges import install_channel
 logs(db);install_channel(db);install(db);install_order("order_workspace_test")
 from configure_refund_goods import install_order as refund_goods
 refund_goods('order_workspace_test')
 from configure_messaging import order,install as messages,management
 order('order_workspace_test');messages('message_delivery_test')
 execute('CREATE DATABASE IF NOT EXISTS queue_control_test CHARACTER SET utf8mb4');management('queue_control_test')
 cache=Path.home()/'.cache/oms-local'
 for module in ['oms-common-core','oms-messaging','oms-channel','oms-order','oms-inventory']+(['oms-supplychain','oms-goods-administration'] if '--gift' in sys.argv else []):
  shutil.copytree(ROOT/'oms-modules'/module/'src',cache/'source/oms-modules'/module/'src',dirs_exist_ok=True);shutil.copy2(ROOT/'oms-modules'/module/'pom.xml',cache/'source/oms-modules'/module/'pom.xml')
 shutil.copytree(ROOT/'ruoyi-common/ruoyi-common-core/src',cache/'source/ruoyi-common/ruoyi-common-core/src',dirs_exist_ok=True)
 shutil.copytree(ROOT/'ruoyi-modules/ruoyi-system/src',cache/'source/ruoyi-modules/ruoyi-system/src',dirs_exist_ok=True)
 shutil.copy2(ROOT/'ruoyi-modules/ruoyi-system/pom.xml',cache/'source/ruoyi-modules/ruoyi-system/pom.xml')
 shutil.copy2(ROOT/'oms-modules/pom.xml',cache/'source/oms-modules/pom.xml')
 suites=['OrderIntakeMessageTest','ProductPullMessageTest','MessageExceptionTest','DeadLetterCollectorTest','QueueDeadLettersTest','QueueMetricsDiscoveryTest','QueueSupervisorTest','QueueControlStoreTest','MessageDeliveryTest','OrderSalesDocumentServiceTest','OrderImmediateWorkTest','PlatformProductTest','OrderProcessorTest','StockSyncTest','ChannelPlatformTest','TmallClientTest','ExchangePlatformTest','PlatformLogTest','OrderStockTest','BundleWarehousePlannerTest','OrderRepositoryTest','TenantRoutingTest']
 if '--gift' in sys.argv:
  from configure_gifts import install_order as gift_order,install_inventory as gift_inventory
  gift_order('order_workspace_test');gift_inventory('order_inventory_test')
  from test_goods_workspace import prepare as prepare_goods
  prepare_goods()
  suites=['TenantRoutingTest','GoodsWorkspaceTest','GoodsGiftProtectionTest','GiftCatalogTest','OrderImmediateWorkTest','WmsOutboundServiceTest','RefundReturnTest','RefundParcelsTest','GiftRulesTest','GiftServiceTest','GiftQuotaTest','GiftConfigurationTest','GiftStrategyDesignerTest','OrderRepositoryTest','OrderProcessorTest','OrderStockTest','RefundServiceTest','RefundBusinessListTest','RefundGoodsServiceTest','RefundReturnAutoCreateTest','RefundReturnDispatchAutomationTest','RefundSharedScopeTest','RefundParcelAutomationTest','RefundParcelPolicyTest','OrderShipmentServiceTest','OrderShipmentDispatchServiceTest','OrderPlatformShippingServiceTest','OrderReviewPolicyTest']
 if '--messaging-only' in sys.argv:suites=['ProductPullMessageTest','MessageExceptionTest','DeadLetterCollectorTest','QueueDeadLettersTest','MessageDeliveryTest','QueueSupervisorTest','QueueControlStoreTest','QueueMetricsDiscoveryTest']
 if '--exchange-messaging' in sys.argv:suites=['PlatformRateIdentityTest','ExchangeIntakeMessageTest','ExchangeSyncTest','PlatformExchangeServiceTest','ExchangeServiceTest','ExchangePlatformTest','ProductPullMessageTest','RefundIntakeMessageTest','OrderIntakeMessageTest','OrderIntakeWorkspaceTest','MessageDeliveryTest','MessageExceptionTest','QueueControlStoreTest','QueueSupervisorTest','TenantRoutingTest']
 if '--exchange-intake-only' in sys.argv:suites=['ExchangeIntakeMessageTest']
 if '--refund-messaging' in sys.argv:suites=['RefundIntakeMessageTest','RefundServiceTest','RefundBusinessListTest','RefundPushReaderTest','RefundGoodsServiceTest','RefundReturnAutoCreateTest','RefundReturnDispatchAutomationTest','RefundParcelAutomationTest','RefundParcelPolicyTest','RefundSharedScopeTest','OrderIntakeMessageTest','OrderIntakeWorkspaceTest','MessageDeliveryTest','QueueControlStoreTest','QueueSupervisorTest','TenantRoutingTest']
 if '--refund-intake-only' in sys.argv:suites=['RefundIntakeMessageTest']
 if '--intake-only' in sys.argv:suites=['OrderIntakeMessageTest']
 if '--order-messaging' in sys.argv:suites=['OrderIntakeMessageTest','OrderBackfillServiceTest','OrderRepositoryTest','OrderIntakeWorkspaceTest','OrderProcessorTest','OrderIntakePermissionTest','TmallOrderParserTest','OrderUpdateServiceTest','OrderProductMatchServiceTest','MessageDeliveryTest','QueueControlStoreTest']
 env=dict(os.environ,GOODS_TEST_URL=test_jdbc_url('goods_workspace_test'),PURCHASE_TEST_URL=test_jdbc_url('purchase_workspace_test'),QUEUE_CONTROL_TEST_URL=test_jdbc_url('queue_control_test'),MESSAGE_TEST_URL=test_jdbc_url('message_delivery_test'),CHANNEL_TEST_URL=test_jdbc_url(db),ORDER_STOCK_TEST_URL=test_jdbc_url('order_inventory_test'),ORDER_TEST_URL=test_jdbc_url('order_workspace_test'),INVENTORY_TEST_PASSWORD=ENV['MYSQL_ROOT_PASSWORD'],JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64',TZ='Asia/Shanghai')
 cmd=[str(HERE/'tools/apache-maven-3.9.9/bin/mvn'),'-B','-f',str(cache/'source/pom.xml'),'-Plocal-docker','-Dmaven.repo.local='+str(cache/'m2'),'-pl','oms-modules/oms-channel,oms-modules/oms-order,oms-modules/oms-inventory,ruoyi-modules/ruoyi-system'+(',oms-modules/oms-supplychain,oms-modules/oms-goods-administration' if '--gift' in sys.argv else ''),'-am','-Dtest='+','.join(suites),'-Dsurefire.failIfNoSpecifiedTests=false','clean','test']
 with (HERE/'logs/stock-sync-tests.log').open('w') as log:r=subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT)
 if r.returncode:raise SystemExit('Stock sync regressions failed: docker/local/logs/stock-sync-tests.log')
 total=0;found=set()
 for module in ['oms-messaging','oms-channel','oms-order','oms-inventory','ruoyi-system']+(['oms-supplychain','oms-goods-administration'] if '--gift' in sys.argv else []):
  for report in (cache/'source'/('ruoyi-modules' if module=='ruoyi-system' else 'oms-modules')/module/'target/surefire-reports').glob('TEST-*.xml'):
   a=ET.parse(report).getroot().attrib;name=a['name'].split('.')[-1]
   if name in suites:
    assert all(int(a.get(k,0))==0 for k in ['errors','failures','skipped']),a['name'];total+=int(a['tests']);found.add(name)
 assert found==set(suites),(found,set(suites)-found)
 print('PASS platform stock synchronization and affected workflows:',total)
if __name__=='__main__':main()
