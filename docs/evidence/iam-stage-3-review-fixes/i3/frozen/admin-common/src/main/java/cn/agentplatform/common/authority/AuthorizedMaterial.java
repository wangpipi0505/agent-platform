package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record AuthorizedMaterial(Object payload,MaterialScopeV1 scope) {}
