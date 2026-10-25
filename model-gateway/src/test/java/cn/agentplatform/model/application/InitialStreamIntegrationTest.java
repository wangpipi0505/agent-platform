package cn.agentplatform.model.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.model.infra.FrozenRequestConnector;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real transport prerequisite; the authenticated component cases follow this scheduling check. */
@Timeout(20)
class InitialStreamIntegrationTest {
    @Test void finalRevocationAfterSuccessfulPersistenceSuppressesOnlyClientFinal() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("controlled-usage");
            var invoked=new java.util.concurrent.atomic.AtomicInteger();
            f.dataSource.onFinished=()->{
                assertThat(f.db.queryForObject("select status from model.call_log where invocation_ref=?",String.class,f.invocation())).isEqualTo("SUCCEEDED");
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                try { f.authorityControl("revoke"); invoked.incrementAndGet(); }
                catch(Exception e){throw new IllegalStateException(e);}
            };
            var response=f.post(f.selected(),f.request);
            assertThat(invoked.get()).isEqualTo(1);
            assertThat(response.body()).contains("event:error").doesNotContain("event:final");
            assertThat(f.providerCalls()).isEqualTo(1);assertThat(f.logs()).isEqualTo(1);
            assertThat(f.db.queryForMap("select status,total_tokens from model.call_log where invocation_ref=?",f.invocation()))
                    .containsEntry("status","SUCCEEDED").containsEntry("total_tokens",66);
            assertThat(f.db.queryForObject("select status from model.quota_reservation where invocation_ref=?",String.class,f.invocation())).isEqualTo("SENT");
            Files.writeString(f.dir.resolve("post-finished-revoke.json"),Json.write(Map.of("actualSuccessfulUpdate",true,"controlCalls",invoked.get(),"providerDispatches",f.providerCalls(),"clientFinal",false,"durableTokens",66)));
        }
    }

    @Test void credentialPreparationRevocationPreventsTokenizerEgress() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            var original=System.getProperties();var reads=new java.util.concurrent.atomic.AtomicInteger();
            var owner=new AtomicReference<Thread>();var revoked=new java.util.concurrent.atomic.AtomicBoolean();
            var properties=new java.util.Properties(){
                {putAll(original);}
                @Override public String getProperty(String key) {
                    String value=original.getProperty(key);
                    if(key.equals("agent.master.key") && Thread.currentThread().getName().equals(f.receiverThread.get())) {
                        int count=reads.incrementAndGet();
                        if(count==1)owner.set(Thread.currentThread());
                        assertThat(Thread.currentThread()).isSameAs(owner.get());
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        if(count==2)try {f.authorityControl("revoke");revoked.set(true);}catch(Exception e){throw new IllegalStateException(e);}
                    }
                    return value;
                }
            };
            System.setProperties(properties);
            try {
                var response=f.post(f.selected(),f.request);
                assertThat(reads.get()).isEqualTo(2);assertThat(revoked.get()).isTrue();
                assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
                assertThat(response.body()).doesNotContain("event:final");
                assertThat(f.tokenizerCalls()).isZero();assertThat(f.providerCalls()).isZero();assertThat(f.logs()).isZero();
                assertThat(f.db.queryForObject("select count(*) from model.quota_reservation where invocation_ref=?",Long.class,f.invocation())).isZero();
            } finally {System.setProperties(original);assertThat(System.getProperties()).isSameAs(original);}
            Files.writeString(f.dir.resolve("credential-boundary.json"),Json.write(Map.of("credentialFileReads",reads.get(),"revokedDuringDecryptPreparation",true,"owner",owner.get().getName(),"propertiesRestored",System.getProperties()==original,"tokenizerCalls",f.tokenizerCalls(),"providerCalls",f.providerCalls())));
        }
    }

    @Test void failedReceiverSetupClosesAlreadyStartedDependencies() throws Exception {
        var captured=new AtomicReference<InitialStreamIntegrationFixture>();
        var primary=new IllegalStateException("CONTROLLED_RECEIVER_SETUP_FAILURE");
        var secondary=new IllegalStateException("CONTROLLED_HTTP_CLOSE_FAILURE");
        try {
            assertThatThrownBy(()->new InitialStreamIntegrationFixture(f->{
                captured.set(f);
                f.afterHttpClose=()->{throw secondary;};
                assertThat(f.db.queryForObject("select 1",Integer.class)).isEqualTo(1);
                try(var redis=f.redisFactory.getConnection()){assertThat(redis.ping()).isEqualTo("PONG");}
                assertThat(f.http.isTerminated()).isFalse();
                throw primary;
            })).isSameAs(primary);
            var f=captured.get();
            assertThat(primary.getSuppressed()).containsExactly(secondary);
            assertThat(f.pool.isClosed()).isTrue();assertThat(f.http.isTerminated()).isTrue();
            assertThat(f.redisFactory.isRunning()).isFalse();assertThat(InitialStreamIntegrationFixture.current).isNull();
            Files.writeString(f.dir.resolve("constructor-closure.json"),Json.write(Map.of("poolClosed",f.pool.isClosed(),"httpTerminated",f.http.isTerminated(),"redisStopped",!f.redisFactory.isRunning(),"currentCleared",InitialStreamIntegrationFixture.current==null)));
        } finally {
            // Test emergency cleanup occurs only AFTER assertions, so it cannot satisfy lifecycle evidence.
            var f=captured.get();if(f!=null){f.http.close();f.verifier.close();f.authority.close();f.redisFactory.destroy();f.pool.close();InitialStreamIntegrationFixture.current=null;}
        }
    }

    @Test void teardownFailureStillClosesLaterOwnedDependencies() throws Exception {
        var f=new InitialStreamIntegrationFixture();var primary=new IllegalStateException("CONTROLLED_CONTEXT_CLOSE_FAILURE");
        var secondary=new IllegalStateException("CONTROLLED_HTTP_CLOSE_FAILURE");
        f.afterContextClose=()->{throw primary;};
        f.afterHttpClose=()->{throw secondary;};
        try {
            assertThat(f.db.queryForObject("select 1",Integer.class)).isEqualTo(1);
            try(var redis=f.redisFactory.getConnection()){assertThat(redis.ping()).isEqualTo("PONG");}
            assertThatThrownBy(f::close).isSameAs(primary);
            assertThat(primary.getSuppressed()).containsExactly(secondary);
            assertThat(f.pool.isClosed()).isTrue();assertThat(f.http.isTerminated()).isTrue();
            assertThat(f.redisFactory.isRunning()).isFalse();assertThat(InitialStreamIntegrationFixture.current).isNull();
            Files.writeString(f.dir.resolve("throwing-teardown-closure.json"),Json.write(Map.of("poolClosed",f.pool.isClosed(),"httpTerminated",f.http.isTerminated(),"redisStopped",!f.redisFactory.isRunning(),"receiverInactive",!f.context.isActive(),"currentCleared",InitialStreamIntegrationFixture.current==null)));
        } finally {f.context.close();f.http.close();f.verifier.close();f.authority.close();f.redisFactory.destroy();f.pool.close();InitialStreamIntegrationFixture.current=null;}
    }
    @Test void truncatedLengthCannotBecomeFinalSuccess() throws Exception { rejectedFinish("length"); }
    @Test void filteredCompletionCannotBecomeFinalSuccess() throws Exception { rejectedFinish("content_filter"); }
    @Test void toolCompletionCannotBecomeFinalSuccess() throws Exception { rejectedFinish("tool_calls"); }
    @Test void unknownCompletionCannotBecomeFinalSuccess() throws Exception { rejectedFinish("unknown"); }
    private void rejectedFinish(String finish) throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("finish-"+finish);var response=f.post(f.selected(),f.request);
            assertThat(response.body()).doesNotContain("event:final");
            assertThat(response.statusCode()>=400 || response.body().contains("event:error")).isTrue();
            assertThat(f.providerCalls()).isEqualTo(1);
            assertThat(f.db.queryForObject("select status from model.call_log where invocation_ref=?",String.class,f.invocation())).isEqualTo("FAILED");
            assertThat(f.db.queryForObject("select status from model.quota_reservation where invocation_ref=?",String.class,f.invocation())).isEqualTo("SENT");
        }
    }

    @Test void controlledProviderUsagePropagatesWithoutClaimingRealBilling() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("controlled-usage");var response=f.post(f.selected(),f.request);
            assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("event:final").doesNotContain("event:error");
            assertThat(f.db.queryForObject("select total_tokens from model.call_log where invocation_ref=?",Integer.class,f.invocation())).isEqualTo(66);
            assertThat(f.providerCalls()).isEqualTo(1);
        }
    }

    @Test void clientResetCancelsProviderStreamAndReturnsOwnerThread() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            Files.writeString(f.dir.resolve("hold-final"),"hold");
            try(var socket=new java.net.Socket("127.0.0.1",f.url("").getPort())) {
                socket.setSoTimeout(12000);socket.setSoLinger(true,0);
                var out=socket.getOutputStream();
                out.write(("POST /internal/model/chat/stream"+f.selected()+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+f.ticket+"\r\nContent-Type: application/json\r\nContent-Length: "+f.request.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(f.request);out.flush();
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                String line;do {line=reader.readLine();assertThat(line).isNotNull();}while(!line.equals("event:chunk"));
                assertThat(reader.readLine()).contains("受控响应");assertThat(reader.readLine()).isEmpty();
                assertThat(Files.exists(f.dir.resolve("provider-final-released"))).isFalse();
            }
            long started=System.nanoTime();Files.writeString(f.dir.resolve("allow-final"),"allow");
            long until=started+Duration.ofSeconds(5).toNanos();
            while(!Files.exists(f.dir.resolve("receiver-completed.json"))&&System.nanoTime()<until)Thread.sleep(20);
            assertThat(Files.exists(f.dir.resolve("receiver-completed.json"))).isTrue();
            assertThat(f.db.queryForObject("select status from model.call_log where invocation_ref=?",String.class,f.invocation())).isEqualTo("FAILED");
            assertThat(f.providerCalls()).isEqualTo(1);
            Files.writeString(f.dir.resolve("disconnect-observations.json"),Json.write(Map.of("receiverReturnedAfterResetMs",Duration.ofNanos(System.nanoTime()-started).toMillis(),"providerCalls",1,"noFinalBeforeDisconnect",true)));
        }
    }

    @Test void providerEofWithoutFinishCannotBecomeFinalSuccess() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("provider-eof");var response=f.post(f.selected(),f.request);
            assertThat(response.body()).doesNotContain("event:final");
            assertThat(response.statusCode()>=400 || response.body().contains("event:error")).isTrue();
            assertThat(f.providerCalls()).isEqualTo(1);
            assertThat(f.db.queryForMap("select status,total_tokens,usage_reconciliation from model.call_log where invocation_ref=?",f.invocation()))
                    .containsEntry("status","FAILED").containsEntry("total_tokens",null).containsEntry("usage_reconciliation","UNAVAILABLE");
        }
    }

    @Test void providerHttpFailureClosesAndNeverRetriesOrRefunds() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("provider-error");var response=f.post(f.selected(),f.request);
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);assertThat(response.body()).doesNotContain("event:final");
            assertThat(f.providerCalls()).isEqualTo(1);assertThat(Files.exists(f.dir.resolve("provider-request-closed"))).isTrue();
            assertThat(f.db.queryForObject("select status from model.call_log where invocation_ref=?",String.class,f.invocation())).isEqualTo("FAILED");
            assertThat(f.db.queryForObject("select status from model.quota_reservation where invocation_ref=?",String.class,f.invocation())).isEqualTo("SENT");
        }
    }

    @Test @Timeout(27) void tokenizerWholeBodyDripIsCancelledAtBoundBeforeReservation() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.control("drip");long started=System.nanoTime();
            var response=f.http.send(java.net.http.HttpRequest.newBuilder(f.url(f.selected())).timeout(Duration.ofSeconds(19))
                    .header("Authorization","Bearer "+f.ticket).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(f.request)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(504);
            long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();assertThat(elapsed).isLessThan(18000);
            long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(!Files.exists(f.dir.resolve("tokenizer-peer-cancelled"))&&System.nanoTime()<until)Thread.sleep(20);
            assertThat(Files.exists(f.dir.resolve("tokenizer-peer-cancelled"))).isTrue();
            assertThat(f.providerCalls()).isZero();assertThat(f.logs()).isZero();
            assertThat(f.db.queryForObject("select count(*) from model.quota_reservation where invocation_ref=?",Long.class,f.invocation())).isZero();
            Files.writeString(f.dir.resolve("tokenizer-drip-observations.json"),Json.write(Map.of("requestElapsedMonotonicMs",elapsed,"bodyDeadlineMs",10000,"peerObservedCancellation",true)));
        }
    }

    @Test void authenticatedInitialHttpHasExactDurableWireAndUnavailableUsage() throws Exception {
        try (var f = new InitialStreamIntegrationFixture()) {
            var response = f.post(f.selected(), f.request);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("event:chunk", "受控响应", "event:final").doesNotContain("event:error");
            assertThat(f.providerCalls()).isEqualTo(1);
            assertThat(f.logs()).isEqualTo(1);
            var fact = f.fact().path("initialRequest");
            assertThat(fact.path("wireUtf8").textValue()).as("the integrated path durably preserves actual encoded bytes")
                    .isEqualTo(Files.readString(f.dir.resolve("provider-wire.bin")));
            assertThat(fact.path("acceptedCanonicalSha256").asText()).isEqualTo(f.ready.path("requestSha256").asText());
            assertThat(f.dataSource.sendingThread.get()).isEqualTo(f.receiverThread.get()).startsWith("http-nio-");
            assertThat(f.db.queryForMap("select total_tokens,usage_reconciliation from model.call_log where invocation_ref=?",
                    Json.read(new String(f.request,StandardCharsets.UTF_8)).path("invocationRef").asText()))
                    .containsEntry("total_tokens", null).containsEntry("usage_reconciliation", "UNAVAILABLE");
            int gets=f.authorityControl("counts").path("gets").asInt(),tokenizer=f.tokenizerCalls(),resolved=f.resolutions.get();
            long reservations=f.db.queryForObject("select count(*) from model.quota_reservation",Long.class);
            for(String body:java.util.List.of("{\"modelEntryId\":7,\"messages\":[{\"role\":\"user\",\"content\":\"unrelated legacy body\"}]}",
                    "{\"invocationRef\":\"\",\"materialRef\":\"\",\"messages\":[]}","{\"invocationRef\":null,\"messages\":[]}")) {
                assertThat(f.post("",body.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(403);
            }
            assertThatThrownBy(()->f.calls.call(Json.read("{\"messages\":[]}"),null,s->{}))
                    .isInstanceOfSatisfying(cn.agentplatform.common.PlatformException.class,e->assertThat(e.code()).isEqualTo("MODEL_INITIAL_PROFILE_REQUIRED"));
            assertThat(f.authorityControl("counts").path("gets").asInt()).isEqualTo(gets);
            assertThat(f.tokenizerCalls()).isEqualTo(tokenizer);assertThat(f.providerCalls()).isEqualTo(1);
            assertThat(f.resolutions.get()).isEqualTo(resolved);
            assertThat(f.db.queryForObject("select count(*) from model.quota_reservation",Long.class)).isEqualTo(reservations);
            String before=f.fact().toString();
            assertThat(f.post(f.selected(),f.request).statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(f.providerCalls()).isEqualTo(1);assertThat(f.logs()).isEqualTo(1);assertThat(f.fact().toString()).isEqualTo(before);
            Files.writeString(f.dir.resolve("positive-observations.json"),Json.write(Map.of("servletThread",f.receiverThread.get(),
                    "actualSendCallbackThread",f.dataSource.sendingThread.get(),"sameTicketDowngradeMaterialGets",0,"providerDispatches",1,"unavailableUsage",true)));
        }
    }

    @Test void tokenizerRejectsBadFullRequestCountsAndAcknowledgementsBeforeReservation() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            long before=f.db.queryForObject("select count(*) from model.quota_reservation",Long.class);
            for(String mode:java.util.List.of("missing-ack","wrong-ack","fractional","zero","overflow","oversized","redirect","context-overflow")) {
                f.control(mode);var response=f.post(f.selected(),f.request);
                assertThat(response.statusCode()).as(mode+response.body()).isGreaterThanOrEqualTo(400);
                assertThat(response.body()).doesNotContain("event:final");
            }
            assertThat(f.tokenizerCalls()).isEqualTo(8);assertThat(f.providerCalls()).isZero();assertThat(f.logs()).isZero();
            assertThat(f.db.queryForObject("select count(*) from model.quota_reservation",Long.class)).isEqualTo(before);
        }
    }

    @Test void actualCurrentLossDuringTokenizerAndReservationDeniesCompletion() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            for(String mode:java.util.List.of("revoke","narrow","outage")) {
                f.control(mode);var response=f.post(f.selected(),f.request);
                assertThat(response.statusCode()).as(mode+response.body()).isGreaterThanOrEqualTo(400);
                f.authorityControl("restore");
            }
            f.control("normal");f.duringReservation("revoke");
            var response=f.post(f.selected(),f.request);
            assertThat(response.statusCode()).as(response.body()).isGreaterThanOrEqualTo(400);
            f.authorityControl("restore");
            assertThat(f.tokenizerCalls()).isEqualTo(4);assertThat(f.providerCalls()).isZero();assertThat(f.logs()).isZero();
            assertThat(f.db.queryForObject("select status from model.quota_reservation where invocation_ref=?",String.class,f.invocation())).isEqualTo("NO_SEND");
        }
    }

    @Test void firstChunkFlushesBeforeProviderFinalAndResponseRevocationStopsFinal() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            Files.writeString(f.dir.resolve("hold-final"),"hold");
            Files.writeString(f.dir.resolve("client-send-started"),java.time.Instant.now().toString());
            var response=f.http.send(java.net.http.HttpRequest.newBuilder(f.url(f.selected())).timeout(Duration.ofSeconds(12))
                    .header("Authorization","Bearer "+f.ticket).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(f.request)).build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            Files.writeString(f.dir.resolve("client-headers-received"),java.time.Instant.now().toString());
            try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).isEqualTo("event:chunk");
                assertThat(reader.readLine()).contains("受控响应");
                assertThat(reader.readLine()).isEmpty();
                Files.writeString(f.dir.resolve("client-first-received"),java.time.Instant.now().toString());
                assertThat(Files.exists(f.dir.resolve("allow-final"))).isFalse();
                assertThat(Files.exists(f.dir.resolve("provider-final-released"))).isFalse();
                f.authorityControl("revoke");Files.writeString(f.dir.resolve("allow-final"),"allow");
                String rest=reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                assertThat(rest).contains("event:error").doesNotContain("event:final");
                f.authorityControl("restore");
                Files.writeString(f.dir.resolve("flush-revocation.json"),Json.write(Map.of("firstChunkBeforeFinalRelease",true,"finalSuppressedAfterActualRevocation",true)));
            }
            assertThat(f.providerCalls()).isEqualTo(1);
        }
    }

    @Test void badSelectorsAndSlowBodiesStopBeforeMaterialOrModelWork() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            int gets=f.authorityControl("counts").path("gets").asInt();
            for(String query:java.util.List.of("?profile=unknown&materialRef=x",f.selected()+"&profile=initial-chat-main-v1",f.selected()+"&materialRef=x"))
                assertThat(f.post(query,f.request).statusCode()).isEqualTo(400);
            try(var socket=new java.net.Socket("127.0.0.1",f.url("").getPort())) {
                socket.setSoTimeout(4000);long started=System.nanoTime();
                socket.getOutputStream().write(("POST /internal/model/chat/stream"+f.selected()+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+f.ticket+"\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine();
                assertThat(status).doesNotContain(" 200 ");assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
            }
            try(var socket=new java.net.Socket("127.0.0.1",f.url("").getPort())) {
                socket.setSoTimeout(4500);long started=System.nanoTime();
                socket.getOutputStream().write(("POST /internal/model/chat/stream"+f.selected()+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+f.ticket+"\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                var writer=Thread.ofVirtual().start(()->{
                    try { for(int i=0;i<30;i++){socket.getOutputStream().write(' ');socket.getOutputStream().flush();Thread.sleep(150);} }
                    catch(java.io.IOException closed) { } catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                });
                try {
                    String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine();
                    long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();
                    assertThat(status).contains(" 408 ");assertThat(elapsed).isBetween(2000L,4000L);
                    Files.writeString(f.dir.resolve("active-drip.json"),Json.write(Map.of("elapsedMonotonicMs",elapsed,"byteIntervalMs",150,"containerReadTimeoutMs",1000,"bodyDeadlineMs",2000,"status",status)));
                } finally {writer.interrupt();writer.join(1000);assertThat(writer.isAlive()).isFalse();}
            }
            assertThat(f.post(f.selected(),new byte[2097153]).statusCode()).isEqualTo(413);
            assertThat(f.authorityControl("counts").path("gets").asInt()).isEqualTo(gets);
            assertThat(f.providerCalls()).isZero();assertThat(f.tokenizerCalls()).isZero();assertThat(f.logs()).isZero();
        }
    }

    @Test void nativeProductionConnectorRunsOnOwnerBeforeActualProviderSend() throws Exception {
        Thread owner = Thread.currentThread();
        var callback = new AtomicReference<Thread>();
        var encoded = new AtomicReference<byte[]>();
        var received = new AtomicReference<byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(exchange.getRequestBody().readNBytes(2097153));
            byte[] response = ("data: {\"id\":\"controlled\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"controlled response\"},\"finish_reason\":null}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
            exchange.close();
        });
        server.start();
        try {
            var connector = new FrozenRequestConnector((uri, bytes) -> {
                callback.set(Thread.currentThread());
                encoded.set(bytes.clone());
                assertThat(Thread.currentThread()).as("encoder callback owns the current authority frame").isSameAs(owner);
            });
            var options = OpenAiChatOptions.builder().model("fixture-model").internalToolExecutionEnabled(false).build();
            var api = OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("controlled-no-secret").webClientBuilder(WebClient.builder().clientConnector(connector)).build();
            var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            StringBuilder text = new StringBuilder();
            try (var stream = model.stream(new Prompt("controlled transport prerequisite", options))
                    .timeout(Duration.ofSeconds(5)).toStream(1)) {
                stream.forEach(chunk -> {
                    assertThat(Thread.currentThread()).isSameAs(owner);
                    text.append(chunk.getResult().getOutput().getText());
                });
            }
            assertThat(callback.get()).isSameAs(owner);
            assertThat(received.get()).isEqualTo(encoded.get());
            assertThat(text).hasToString("controlled response");
            Files.writeString(Path.of("G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-initial-stream/connector-thread.json"), Json.write(Map.of(
                    "ownerThread", owner.getName(), "callbackThread", callback.get().getName(),
                    "actualProviderBytes", received.get().length, "sameOwner", true, "realTransport", true)));
        } finally { server.stop(0); }
    }
}
