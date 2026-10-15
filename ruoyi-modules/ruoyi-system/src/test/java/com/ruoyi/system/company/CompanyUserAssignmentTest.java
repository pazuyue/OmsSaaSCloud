package com.ruoyi.system.company;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import com.ruoyi.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CompanyUserAssignmentTest {
    @Test void companyCannotBeChangedThroughPasswordStatusOrPersonalProfileCommands(){
        SysUserServiceImpl service=new SysUserServiceImpl();SysUserMapper mapper=mock(SysUserMapper.class);ReflectionTestUtils.setField(service,"userMapper",mapper);
        SysUser input=new SysUser(2L);input.setLoginCompanyCode("OTHER");input.setPassword("encoded");input.setStatus("1");input.setNickName("name");
        service.resetPwd(input);service.updateUserStatus(input);service.updateUserProfile(input);
        ArgumentCaptor<SysUser> changes=ArgumentCaptor.forClass(SysUser.class);verify(mapper,times(3)).updateUser(changes.capture());
        for(SysUser change:changes.getAllValues())assertNull(change.getLoginCompanyCode());assertEquals("OTHER",input.getLoginCompanyCode());
    }
}
