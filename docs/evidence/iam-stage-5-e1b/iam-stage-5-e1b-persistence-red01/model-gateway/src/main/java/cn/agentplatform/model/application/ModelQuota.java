package cn.agentplatform.model.application;

import cn.agentplatform.common.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Redis Lua原子预留供应商双桶、独立的应用/用户日额及并发；不可用即拒绝。 */
@Service
public class ModelQuota {
    private final StringRedisTemplate redis;

    public ModelQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static final DefaultRedisScript<List> ACQUIRE =
            new DefaultRedisScript<>(
                    """
local clock=redis.call('TIME');local now=clock[1]*1000+math.floor(clock[2]/1000)
if tonumber(ARGV[11])>0 and now>=tonumber(ARGV[11]) then return {0,1000} end
local tpm=tonumber(ARGV[1]);local rpm=tonumber(ARGV[2]);local cost=tonumber(ARGV[3])
local state=redis.call('HMGET',KEYS[1],'tokens','requests','updated','generation')
local elapsed=math.max(0,now-tonumber(state[3] or now))
local tokens=math.min(tpm,tonumber(state[1] or tpm)+elapsed*tpm/60000)
local requests=math.min(rpm,tonumber(state[2] or rpm)+elapsed*rpm/60000)
local appSpent=tonumber(redis.call('GET',KEYS[2]) or '0')
local userSpent=tonumber(redis.call('GET',KEYS[3]) or '0')
redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now);redis.call('ZREMRANGEBYSCORE',KEYS[5],'-inf',now)
local wait=0
if tokens<cost then wait=math.max(wait,math.ceil((cost-tokens)*60000/tpm)) end
if requests<1 then wait=math.max(wait,math.ceil((1-requests)*60000/rpm)) end
if now>=tonumber(ARGV[9]) or now<tonumber(ARGV[9])-86400000 or appSpent+cost>tonumber(ARGV[4]) or userSpent+cost>tonumber(ARGV[5]) then wait=math.max(wait,86400000) end
if redis.call('ZCARD',KEYS[4])>=tonumber(ARGV[6]) or redis.call('ZCARD',KEYS[5])>=tonumber(ARGV[7]) then wait=math.max(wait,1000) end
if wait>0 then return {0,wait} end
local generation=state[4] or ARGV[8]
redis.call('HSET',KEYS[1],'tokens',tokens-cost,'requests',requests-1,'updated',now,'generation',generation);redis.call('PEXPIRE',KEYS[1],120000)
for i=2,3 do redis.call('INCRBY',KEYS[i],cost);redis.call('EXPIRE',KEYS[i],172800) end
for i=4,5 do redis.call('ZADD',KEYS[i],now+660000,ARGV[8]);redis.call('PEXPIRE',KEYS[i],720000) end
local deadline=tonumber(ARGV[10]);if deadline==0 then deadline=now+86400000 end
redis.call('HSET',KEYS[6],'generation',generation,'providerUntil',now+60000)
redis.call('PEXPIREAT',KEYS[6],deadline+86400000)
return {1,0,now,generation}
""",
                    List.class);
    private static final DefaultRedisScript<Long> CLOCK =
            new DefaultRedisScript<>(
                    "local t=redis.call('TIME');return t[1]*1000+math.floor(t[2]/1000)",
                    Long.class);
    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    "for i=1,#KEYS do redis.call('ZREM',KEYS[i],ARGV[1]) end return 1", Long.class);
    private static final DefaultRedisScript<Long> RECONCILE =
            new DefaultRedisScript<>(
                    """
local clock=redis.call('TIME');local now=clock[1]*1000+math.floor(clock[2]/1000)
if now>=tonumber(ARGV[4]) then return -1 end
local previous=redis.call('GET',KEYS[4]);if previous then return tonumber(previous) end
local generation=ARGV[7];local providerUntil=tonumber(ARGV[5])
if ARGV[8]=='1' then
 local evidence=redis.call('HMGET',KEYS[5],'generation','providerUntil')
 if not evidence[1] then
  if now<tonumber(ARGV[11]) then return 3 end
  return -1
 end
 generation=evidence[1];providerUntil=tonumber(evidence[2])
end
local difference=tonumber(ARGV[1])-tonumber(ARGV[2])
local applied=0
if now<providerUntil and redis.call('HGET',KEYS[1],'generation')==generation and redis.call('PTTL',KEYS[1])>0 then
 local tokens=tonumber(redis.call('HGET',KEYS[1],'tokens'))
 redis.call('HSET',KEYS[1],'tokens',math.min(tonumber(ARGV[3]),tokens+difference));applied=applied+1
 if ARGV[9]=='1' then
  local requests=tonumber(redis.call('HGET',KEYS[1],'requests'))
  redis.call('HSET',KEYS[1],'requests',math.min(tonumber(ARGV[10]),requests+1))
 end
end
for i=2,3 do
 if now<tonumber(ARGV[6]) and redis.call('PTTL',KEYS[i])>0 then
  local daily=tonumber(redis.call('GET',KEYS[i]));redis.call('SET',KEYS[i],math.max(0,daily-difference),'KEEPTTL');applied=applied+1
 end
end
local outcome=-1;if applied==3 then outcome=1 elseif applied>0 then outcome=2 end
redis.call('SET',KEYS[4],outcome,'PXAT',tonumber(ARGV[4])+86400000)
return outcome
""",
                    Long.class);
    // 批量队列也是Redis有界租约集合；进程退出不会留下永久占位，禁止每副本各自无界排队。
    private static final DefaultRedisScript<Long> ENQUEUE =
            new DefaultRedisScript<>(
                    """
local clock=redis.call('TIME');local now=clock[1]*1000+math.floor(clock[2]/1000)
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-30000)
if redis.call('ZCARD',KEYS[1])>=32 then return -1 end
redis.call('ZADD',KEYS[1],now,ARGV[1]);redis.call('PEXPIRE',KEYS[1],60000);return redis.call('ZRANK',KEYS[1],ARGV[1])
""",
                    Long.class);

    public Permit acquire(
            Actor actor,
            long provider,
            String app,
            int tokens,
            int tpm,
            int rpm,
            int daily,
            int userConcurrency,
            int appConcurrency,
            boolean batch,
            int maxWaitMs) {
        return acquire(
                actor,
                provider,
                app,
                tokens,
                tpm,
                rpm,
                daily,
                daily,
                userConcurrency,
                appConcurrency,
                batch,
                maxWaitMs);
    }

    public Permit acquire(
            Actor actor,
            long provider,
            String app,
            int tokens,
            int tpm,
            int rpm,
            int daily,
            int userDaily,
            int userConcurrency,
            int appConcurrency,
            boolean batch,
            int maxWaitMs) {
        return acquire(
                actor,
                provider,
                app,
                tokens,
                tpm,
                rpm,
                daily,
                userDaily,
                userConcurrency,
                appConcurrency,
                batch,
                maxWaitMs,
                null);
    }

    public Permit acquire(
            Actor actor,
            long provider,
            String app,
            int tokens,
            int tpm,
            int rpm,
            int daily,
            int userDaily,
            int userConcurrency,
            int appConcurrency,
            boolean batch,
            int maxWaitMs,
            Consumer<Map<String, Object>> beforeAcquire) {
        if (tokens < 1
                || tpm < 1
                || rpm < 1
                || daily < 1
                || userDaily < 1
                || userConcurrency < 1
                || appConcurrency < 1) throw new IllegalArgumentException("模型配额必须为正数");
        String prefix = "agent:model:{" + Digest.sha256(actor.tenantId()) + "}:",
                id = UUID.randomUUID().toString();
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        long dailyUntil = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<String> keys =
                List.of(
                        prefix + "provider:" + provider,
                        prefix + "daily:" + day + ":" + Digest.sha256(app),
                        prefix + "user-daily:" + day + ":" + Digest.sha256(actor.principalId()),
                        prefix + "user:" + Digest.sha256(actor.principalId()),
                        prefix + "app:" + Digest.sha256(app),
                        prefix + "provider:" + provider + ":reserved:" + id);
        String queue = prefix + "queue:" + provider;
        boolean queued = false;
        long started = System.nanoTime(),
                deadline = started + Math.min(10000, Math.max(0, maxWaitMs)) * 1_000_000L;
        try {
            long recoveryDeadline = 0;
            long admissionDeadline = 0;
            if (beforeAcquire != null) {
                Long clock = redis.execute(CLOCK, List.of());
                if (clock == null) throw new IllegalStateException("Redis未确认预留时钟");
                recoveryDeadline = clock + 86400000L;
                admissionDeadline = clock + 900000L;
                // 先落PG意图，再由准入Lua原子写预留证据；即使准入返回后进程退出也可证明是否预留。
                var prepared =
                        new LinkedHashMap<String, Object>(
                                Map.of(
                                        "keys",
                                        keys.subList(0, 3),
                                        "reserved",
                                        tokens,
                                        "tpm",
                                        tpm,
                                        "rpm",
                                        rpm,
                                        "id",
                                        id,
                                        "reconcileUntil",
                                        recoveryDeadline,
                                        "providerUntil",
                                        0,
                                        "dailyUntil",
                                        dailyUntil,
                                        "generation",
                                        "",
                                        "prepared",
                                        true));
                prepared.put("admissionUntil", admissionDeadline);
                beforeAcquire.accept(Map.copyOf(prepared));
            }
            for (; ; ) {
                boolean head =
                        !queued || Long.valueOf(0).equals(redis.opsForZSet().rank(queue, id));
                List<?> result =
                        head
                                ? redis.execute(
                                        ACQUIRE,
                                        keys,
                                        "" + tpm,
                                        "" + rpm,
                                        "" + tokens,
                                        "" + daily,
                                        "" + userDaily,
                                        "" + userConcurrency,
                                        "" + appConcurrency,
                                        id,
                                        "" + dailyUntil,
                                        "" + recoveryDeadline,
                                        "" + admissionDeadline)
                                : List.of(0L, 100L);
                if (result != null && ((Number) result.getFirst()).longValue() == 1)
                    return new Permit(
                            keys,
                            id,
                            tokens,
                            tpm,
                            rpm,
                            (System.nanoTime() - started) / 1_000_000,
                            ((Number) result.get(2)).longValue(),
                            dailyUntil,
                            result.get(3).toString());
                long wait = result == null ? 1000 : ((Number) result.get(1)).longValue();
                if (!batch || System.nanoTime() >= deadline) throw rejected(wait);
                if (!queued) {
                    Long position = redis.execute(ENQUEUE, List.of(queue), id);
                    if (position == null || position < 0) throw rejected(wait);
                    queued = true;
                }
                Thread.sleep(Math.min(100, Math.max(10, wait)));
            }
        } catch (PlatformException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rejected(1000);
        } catch (Exception e) {
            throw new PlatformException(503, "SYS-MODEL-503", "分布式模型配额暂不可用，请稍后重试");
        } finally {
            if (queued)
                try {
                    redis.execute(RELEASE, List.of(queue), id);
                } catch (Exception ignored) {
                }
        }
    }

    private static PlatformException rejected(long wait) {
        return new PlatformException(
                429,
                "SYS-MODEL-429",
                "模型配额不足，retryAfter=" + Math.max(1, (wait + 999) / 1000) + "秒");
    }

    public String reconcile(Map<String, Object> receipt, int actual) {
        // 旧receipt没有可证明的去重期限，禁止盲目重放。PG保留预留与EXPIRED供人工审计。
        if (actual < 0 || !receipt.containsKey("reconcileUntil")) return "EXPIRED";
        var keys = new ArrayList<String>((List<String>) receipt.get("keys"));
        keys.add(keys.getFirst() + ":reconciled:" + receipt.get("id"));
        keys.add(keys.getFirst() + ":reserved:" + receipt.get("id"));
        Long outcome =
                redis.execute(
                        RECONCILE,
                        keys,
                        receipt.get("reserved").toString(),
                        "" + actual,
                        receipt.get("tpm").toString(),
                        receipt.get("reconcileUntil").toString(),
                        receipt.get("providerUntil").toString(),
                        receipt.get("dailyUntil").toString(),
                        receipt.get("generation").toString(),
                        Boolean.TRUE.equals(receipt.get("prepared")) ? "1" : "0",
                        Boolean.TRUE.equals(receipt.get("refundRequests")) ? "1" : "0",
                        receipt.getOrDefault("rpm", 0).toString(),
                        receipt.getOrDefault("admissionUntil", 0).toString());
        if (outcome == null) throw new IllegalStateException("Redis未确认用量对账");
        if (outcome == 3) throw new IllegalStateException("准入窗口尚未关闭，等待预留证据或期限届满");
        return outcome == 1 ? "RECONCILED" : outcome == 2 ? "PARTIAL" : "EXPIRED";
    }

    public String refundNoSend(Map<String, Object> receipt) {
        var refund = new LinkedHashMap<>(receipt);
        refund.put("refundRequests", true);
        return reconcile(refund, 0);
    }

    public final class Permit implements AutoCloseable {
        private final List<String> keys;
        private final String id;
        private final int reserved, tpm, rpm;
        private boolean reconciled;
        private final long queuedMs;
        private final long acquiredAt, dailyUntil;
        private final String generation;
        private String outcome;

        Permit(
                List<String> keys,
                String id,
                int reserved,
                int tpm,
                int rpm,
                long queuedMs,
                long acquiredAt,
                long dailyUntil,
                String generation) {
            this.keys = keys;
            this.id = id;
            this.reserved = reserved;
            this.tpm = tpm;
            this.rpm = rpm;
            this.queuedMs = queuedMs;
            this.acquiredAt = acquiredAt;
            this.dailyUntil = dailyUntil;
            this.generation = generation;
        }

        public long queuedMs() {
            return queuedMs;
        }

        public Map<String, Object> receipt() {
            // PG持久保存这些不可变窗口；marker晚于恢复截止24h到期，之后Lua永远只返回EXPIRED。
            return Map.of(
                    "keys",
                    keys.subList(0, 3),
                    "reserved",
                    reserved,
                    "tpm",
                    tpm,
                    "rpm",
                    rpm,
                    "id",
                    id,
                    "reconcileUntil",
                    acquiredAt + 86400000L,
                    "providerUntil",
                    acquiredAt + 60000L,
                    "dailyUntil",
                    dailyUntil,
                    "generation",
                    generation);
        }

        public String reconcile(int actual) {
            if (reconciled) return outcome;
            outcome = ModelQuota.this.reconcile(receipt(), actual);
            reconciled = true;
            return outcome;
        }

        @Override
        public void close() {
            try {
                redis.execute(RELEASE, keys.subList(3, 5), id);
            } catch (Exception ignored) {
                /* 租约到期恢复；绝不因释放失败重新发送模型请求。 */
            }
        }
    }
}
