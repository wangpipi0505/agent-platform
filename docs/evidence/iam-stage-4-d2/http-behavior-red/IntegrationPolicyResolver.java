package cn.agentplatform.app.application;
import cn.agentplatform.app.domain.*;
import cn.agentplatform.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class IntegrationPolicyResolver {
    private final JdbcTemplate db;
    public IntegrationPolicyResolver(JdbcTemplate db){this.db=db;}
    public PolicyResolveResult resolve(PolicyResolveRequest request){throw new PlatformException(403,"POLICY_UNAVAILABLE","策略尚不可用");}
}
