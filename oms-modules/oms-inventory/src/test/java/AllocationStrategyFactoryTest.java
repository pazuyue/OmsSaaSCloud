import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.oms.inventory.OmsInventoryApplication;
import com.oms.inventory.model.entity.rule.RuleStockInfo;
import com.oms.inventory.service.impl.rule.RuleStockInfoHandleServicelmpl;
import com.oms.inventory.service.rule.IRuleStockInfoService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.annotation.Autowired; // 新增导入
import org.junit.Before; // 新增导入
import org.junit.runner.RunWith; // 新增导入
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;

import static org.junit.Assert.assertTrue;


@Slf4j
//@RunWith(SpringRunner.class)
//@SpringBootTest(classes = OmsInventoryApplication.class)
//@ContextConfiguration

public class AllocationStrategyFactoryTest {

    // 替换@Resource为@Autowired（更常见的用法）
    @Autowired
    private IRuleStockInfoService ruleStockInfoService;

    @Autowired
    private RuleStockInfoHandleServicelmpl ruleStockInfoHandleServicelmpl;

    @Before
    public void init() {
        log.debug("正在切换到主数据源：master");
        DynamicDataSourceContextHolder.push("master"); // 切换到主数据源
    }

    //@Test
    public void testGetStrategyWithRuleType1() {
        Long id = 1L;
        log.debug("正在查询RuleStockInfo，id={}", id);
        RuleStockInfo one = ruleStockInfoService.selectRuleStockInfoById(id);

        if (one == null) {
            log.error("未找到id={}的RuleStockInfo记录", id);
            return;
        }

        Integer ruleRange = one.getRuleRange();
        if (ruleRange == null) {
            log.warn("规则范围未设置");
            return;
        }

        log.debug("正在获取SKU列表，ruleRange={}", ruleRange);
        List<String> skuList = ruleStockInfoHandleServicelmpl.getSkuList(ruleRange);
        assertTrue("sku列表应非空", !skuList.isEmpty());
        log.info("SKU数量：{}", skuList.size());
    }
}
