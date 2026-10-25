package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record ResponseMaterial(Object payload,MaterialScopeV1 scope,String responseId) {}
