package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record ResponseBinding(String responseId,String kind,ResourceBinding resource,String requestSha256,Instant hardDeadline) {}
