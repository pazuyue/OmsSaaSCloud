# setRule函数设计文档

## 策略工厂（StrategyFactory）

- 使用Spring的依赖注入自动装配所有实现了`AllocationStrategyService`接口的策略类
- 通过`@StrategyType`注解标识不同的策略类型
- 使用`ConcurrentHashMap`存储策略实例，支持并发访问
- 提供`getStrategy`方法根据策略类型获取对应的策略实现



## 超额分配策略（OverAllocateStrategyServiceImpl）

- 使用`@StrategyType("OVER_ALLOCATE")`标识
- 主要处理逻辑：
  - 支持全部商品或指定商品范围的分配
  - 分页处理大量SKU，每页1000条
  - 按照设定的百分比或固定数量进行分配
  - 支持小数位的不同处理方式（向上/向下取整、四舍五入）

## 优先级分配策略（PriorityStrategyServiceImpl）

- 使用`@StrategyType("PRIORITY")`标识
- 核心特点：
  - 按照渠道优先级顺序分配库存
  - 优先级高的渠道先分配，直到库存耗尽
  - 同样支持全部商品或指定商品范围
  - 实时检查剩余可用库存（allTotalAvailable）

## 通用处理流程

1. 获取规则关联的仓库代码列表
2. 根据规则范围（全部/指定）获取SKU列表
3. 调用WMS接口获取实际库存数据
4. 执行库存分配计算
5. 更新渠道库存信息
6. 异常处理和日志记录


