package cn.agentplatform.common.authority;
import java.time.*;
final class MutableClock extends Clock {
    private volatile Instant now=Instant.parse("2026-10-20T00:00:00Z");
    void advance(long seconds){now=now.plusSeconds(seconds);}
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return now;}
}
