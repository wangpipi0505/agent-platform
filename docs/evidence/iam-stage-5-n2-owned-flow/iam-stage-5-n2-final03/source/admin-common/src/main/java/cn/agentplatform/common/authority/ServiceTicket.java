package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record ServiceTicket(String value,Instant expiresAt,OperationBinding operation) { @Override public String toString(){return "ServiceTicket[redacted]";} }
