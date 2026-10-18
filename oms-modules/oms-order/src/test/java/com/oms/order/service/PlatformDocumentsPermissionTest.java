package com.oms.order.service;
import com.oms.order.controller.PlatformDocumentsController;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.system.api.model.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;

class PlatformDocumentsPermissionTest {
    @AfterEach void clear(){SecurityContextHolder.remove();}
    @Test void refundOnlyRoleUsesTrustedCompanyAndCannotExpandTypesFromRequest(){LoginUser user=new LoginUser();user.setCompanyCode("VERIFY");user.setPermissions(Collections.singleton("order:refund:list"));SecurityContextHolder.set(SecurityConstants.LOGIN_USER,user);PlatformDocumentsWorkspace workspace=mock(PlatformDocumentsWorkspace.class);PlatformDocumentsController controller=new PlatformDocumentsController();ReflectionTestUtils.setField(controller,"workspace",workspace);Map<String,String> input=new HashMap<>();input.put("companyCode","OTHER");input.put("types","ORDER,EXCHANGE");controller.options();controller.related(input);controller.list("ORDER",input);verify(workspace).options("VERIFY",Collections.singleton("REFUND"));verify(workspace).related("VERIFY",Collections.singleton("REFUND"),input);verify(workspace).list("VERIFY",Collections.singleton("REFUND"),"ORDER",input);}
}
