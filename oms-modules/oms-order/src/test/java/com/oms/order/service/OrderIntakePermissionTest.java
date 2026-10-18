package com.oms.order.service;
import com.oms.order.controller.OrderIntakeController;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.system.api.model.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.oms.order.service.ExchangeService.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderIntakePermissionTest {
    @AfterEach void clear(){SecurityContextHolder.remove();}
    @Test void returnRulesCatalogIsIndependentAndUsesAuthenticatedCompany() throws Exception {
        LoginUser user=new LoginUser();user.setCompanyCode("VERIFY");user.setPermissions(Collections.singleton("order:refund:config"));SecurityContextHolder.set(SecurityConstants.LOGIN_USER,user);
        OrderCatalog catalog=mock(OrderCatalog.class);List<Map<String,Object>> shops=Collections.singletonList(map("channel_id",7));when(catalog.channels("VERIFY")).thenReturn(shops);
        com.oms.order.controller.RefundController controller=new com.oms.order.controller.RefundController();ReflectionTestUtils.setField(controller,"catalog",catalog);
        assertSame(shops,controller.parcelConfigShops().get("data"));verify(catalog).channels("VERIFY");verifyNoMoreInteractions(catalog);
        assertArrayEquals(new String[]{"order:refund:config"},controller.getClass().getMethod("parcelConfigShops").getAnnotation(com.ruoyi.common.security.annotation.RequiresPermissions.class).value());
    }
    @Test void refundOnlyRoleCannotSeeOrderOrExchangeConfigurationsOrTasks(){LoginUser user=new LoginUser();user.setCompanyCode("VERIFY");user.setPermissions(new HashSet<>(Arrays.asList("order:refund:config","order:refund:sync")));SecurityContextHolder.set(SecurityConstants.LOGIN_USER,user);OrderIntakeWorkspace workspace=mock(OrderIntakeWorkspace.class);when(workspace.overview(eq("VERIFY"),anySet())).thenReturn(map("rows",Collections.emptyList()));when(workspace.tasks(eq("VERIFY"),anySet(),anyMap())).thenReturn(map("rows",Collections.emptyList()));OrderIntakeController controller=new OrderIntakeController();ReflectionTestUtils.setField(controller,"workspace",workspace);Map<?,?> result=(Map<?,?>)controller.overview().get("data");assertEquals(Collections.singleton("REFUND"),result.get("config_types"));assertEquals(Collections.singleton("REFUND"),result.get("task_types"));controller.tasks(Collections.emptyMap());verify(workspace).overview("VERIFY",Collections.singleton("REFUND"));verify(workspace).tasks("VERIFY",Collections.singleton("REFUND"),Collections.emptyMap());}
}
