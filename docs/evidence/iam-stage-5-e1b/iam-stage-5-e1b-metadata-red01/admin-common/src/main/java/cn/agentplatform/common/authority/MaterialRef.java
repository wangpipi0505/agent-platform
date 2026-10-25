package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record MaterialRef(String producer,String kind,String id,String version,String payloadSha256) {}
