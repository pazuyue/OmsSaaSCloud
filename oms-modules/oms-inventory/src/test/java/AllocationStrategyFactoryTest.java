import com.oms.inventory.factory.AllocationStrategyFactory;
import com.oms.inventory.model.enums.AllocationStrategy;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AllocationStrategyFactoryTest {

    @Test
    public void testGetStrategyWithRuleType1() {
        // Arrange
        int ruleType = 1;
        // Act
        AllocationStrategy strategy = AllocationStrategyFactory.getStrategy(ruleType);
        System.out.println(strategy);
    }

    @Test
    public void testGetStrategyWithRuleType2() {
        // Arrange
        int ruleType = 2;

        // Act
        AllocationStrategy strategy = AllocationStrategyFactory.getStrategy(ruleType);
    }

    @Test
    public void testGetStrategyWithRuleType3() {
        // Arrange
        int ruleType = 3;

        // Act
        AllocationStrategy strategy = AllocationStrategyFactory.getStrategy(ruleType);

        // Assert
        //assertTrue(strategy instanceof OverAllocateAllocationStrategy); // 假设 OVER_ALLOCATE 是 OverAllocateAllocationStrategy 的实例
    }

    @Test
    public void testGetStrategyWithInvalidRuleType() {
        // Arrange
        int invalidRuleType = 4;

        // Act & Assert
      /*  assertThrows(IllegalArgumentException.class, () -> {
            AllocationStrategyFactory.getStrategy(invalidRuleType);
        }, "应抛出 '未知分配规则' 异常");*/
    }
}
