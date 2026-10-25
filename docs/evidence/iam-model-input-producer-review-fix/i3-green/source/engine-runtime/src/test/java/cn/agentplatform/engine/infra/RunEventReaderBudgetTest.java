package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.PlatformException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RunEventReaderBudgetTest {
    static void rejected(Runnable read) { assertThatThrownBy(read::run).isInstanceOf(PlatformException.class); }
    static String nested(int depth) { return "[".repeat(depth)+"0"+"]".repeat(depth); }
    @Test void metadataStreamingParserChecksEverySingleConstraintBeforeDom() {
        RunEventReader.parse(nested(32),false,()->{});rejected(()->RunEventReader.parse(nested(33),false,()->{}));
        RunEventReader.parse("\""+"x".repeat(65536)+"\"",false,()->{});rejected(()->RunEventReader.parse("\""+"x".repeat(65537)+"\"",false,()->{}));
        RunEventReader.parse("{\""+"x".repeat(65536)+"\":0}",false,()->{});rejected(()->RunEventReader.parse("{\""+"x".repeat(65537)+"\":0}",false,()->{}));
        RunEventReader.parse("1".repeat(1000),false,()->{});rejected(()->RunEventReader.parse("1".repeat(1001),false,()->{}));
        String exact="["+"0,".repeat(65533)+"0]";RunEventReader.parse(exact,false,()->{});rejected(()->RunEventReader.parse("["+"0,".repeat(65534)+"0]",false,()->{}));
        rejected(()->RunEventReader.parse("{\"x\":1,\"x\":2}",false,()->{}));rejected(()->RunEventReader.parse("{}{}",false,()->{}));
    }
    @Test void rootParserHasItsOwnFiniteDepthTokensStringAndNumberLimits() {
        RunEventReader.parse(nested(64),true,()->{});rejected(()->RunEventReader.parse(nested(65),true,()->{}));
        RunEventReader.parse("\""+"x".repeat(1048576)+"\"",true,()->{});rejected(()->RunEventReader.parse("\""+"x".repeat(1048577)+"\"",true,()->{}));
        RunEventReader.parse("["+"0,".repeat(262141)+"0]",true,()->{});rejected(()->RunEventReader.parse("["+"0,".repeat(262142)+"0]",true,()->{}));
        RunEventReader.parse("1".repeat(1000),true,()->{});rejected(()->RunEventReader.parse("1".repeat(1001),true,()->{}));
    }
    @Test void rootFieldNameParserAccepts1048576CharactersAndRejects1048577() {
        // Parser-only mechanical boundary: this does not admit the same document under
        // the independent business raw/model/total representation intersections.
        String exact="x".repeat(1048576);
        assertThat(RunEventReader.parse("{\""+exact+"\":0}",true,()->{}).has(exact)).isTrue();
        rejected(()->RunEventReader.parse("{\""+exact+"x\":0}",true,()->{}));
    }
    @Test void cumulativeByteRecordAndParentBudgetsRejectOverflowAndNeverSilentlyTruncate() {
        var l=new RunEventReader.Limits();l.metadata(8388608);rejected(()->l.metadata(1));
        var r=new RunEventReader.Limits();r.root(8388608);rejected(()->r.root(1));
        var record=new RunEventReader.Limits();for(int i=0;i<4096;i++)record.record(0);rejected(()->record.record(0));
        RunEventReader.Limits.parents(4096);rejected(()->RunEventReader.Limits.parents(4097));
        rejected(()->new RunEventReader.Limits().metadata(Long.MAX_VALUE));rejected(()->new RunEventReader.Limits().metadata(-1));
        rejected(()->new RunEventReader.Limits().record(1048577));
    }
    @Test void normalizationExpansionHasIndependentBudgetBeforeDecimalToIntegerExpansion() {
        // Preserve real precision/scale while intercepting the dangerous expansion itself. RED
        // must fail without allocating the 67MB integer; this is a mechanical digest guard test.
        var decimal=new java.math.BigDecimal("1e67108865"){
            @Override public java.math.BigDecimal stripTrailingZeros(){return this;}
            @Override public java.math.BigInteger toBigIntegerExact(){throw new AssertionError("UNBOUNDED_EXPANSION_REACHED");}
        };
        var value=com.fasterxml.jackson.databind.node.DecimalNode.valueOf(decimal);
        assertThatThrownBy(()->MaterialScopeStore.observedDigest(value)).isInstanceOf(RunEventReader.CapacityFailure.class);
    }
    @Test void scopeArraysRejectDuplicateHashesWrongTypesAndParentCountBeyond4096() {
        var n=cn.agentplatform.common.Json.read("""
                {"schemaVersion":1,"material":{"producer":"engine","kind":"run-event","id":"1","version":"1","payloadSha256":"%s"},"owner":{"tenantId":"tenant-a","principalId":"alice"},"sourceDecisionId":"old","sourceAuthorizationVersion":"old","initialCeilingSha256":"%s","requiredRoles":[],"requiredEntitlements":{},"requiredDataLabels":[],"parentScopeSha256":[],"scopeSha256":"%s"}
                """.formatted("a".repeat(64),"b".repeat(64),"c".repeat(64)));
        var parents=((com.fasterxml.jackson.databind.node.ObjectNode)n).putArray("parentScopeSha256");
        for(int i=0;i<4096;i++)parents.add("%064x".formatted(i));
        assertThat(RunEventReader.scope(RunEventReader.parse(n.toString(),false,()->{})).parentScopeSha256()).hasSize(4096);
        parents.add("d".repeat(64));rejected(()->RunEventReader.scope(RunEventReader.parse(n.toString(),false,()->{})));
        parents.removeAll().add("a".repeat(64)).add("a".repeat(64));rejected(()->RunEventReader.scope(n));
        ((com.fasterxml.jackson.databind.node.ObjectNode)n).put("parentScopeSha256",true);rejected(()->RunEventReader.scope(n));
        ((com.fasterxml.jackson.databind.node.ObjectNode)n).putArray("parentScopeSha256");((com.fasterxml.jackson.databind.node.ObjectNode)n.path("material")).put("id",7);rejected(()->RunEventReader.scope(n));
    }
    @Test void parserCancellationChecksRealCaptureAndFieldNamesConsumeTokenBudget() throws Exception {
        try(var f=new RunEventReaderAuthorityFixture();var r=f.open(new SubjectKey("tenant-a","alice"),"r_"+"c".repeat(32),cn.agentplatform.common.Digest.sha256("c"),0)){
            var c=r.currentCapture();var calls=new java.util.concurrent.atomic.AtomicInteger();
            rejected(()->RunEventReader.parse("["+"0,".repeat(4000)+"0]",false,()->{if(calls.incrementAndGet()==2)r.close();r.requireLive(c);}));
            assertThat(calls).hasValue(2);
        }
        // 2 structural + 2 per field. 32767 pairs gives exactly 65536 including names.
        var fields=new StringBuilder("{");for(int i=0;i<32767;i++){if(i>0)fields.append(',');fields.append('"').append(i).append("\":0");}fields.append('}');
        RunEventReader.parse(fields.toString(),false,()->{});fields.insert(fields.length()-1,",\"extra\":0");rejected(()->RunEventReader.parse(fields.toString(),false,()->{}));
    }
    @Test void getterClosureExpiryAndGenerationFailureAreLocalAndSticky() throws Exception {
        try(var f=new RunEventReaderAuthorityFixture()){
            var owner=new SubjectKey("tenant-a","alice");String run="r_"+"d".repeat(32),sha=cn.agentplatform.common.Digest.sha256("d");
            try(var r=f.open(owner,run,sha,0)){
                var old=r.currentCapture();f.renew(r);int calls=f.iam.requestAttempts();
                rejected(old::initialCursor);rejected(old::remainingNanos);
                var fresh=r.currentCapture();r.close();rejected(fresh::initialCursor);rejected(fresh::remainingNanos);assertThat(f.iam.requestAttempts()).isEqualTo(calls);
            }
            try(var r=f.open(owner,run,sha,0)){
                var c=r.currentCapture();int calls=f.iam.requestAttempts();f.advance(6);rejected(c::initialCursor);rejected(c::remainingNanos);f.advance(-6);rejected(c::remainingNanos);assertThat(f.iam.requestAttempts()).isEqualTo(calls);
            }
        }
    }
    @Test void subMillisecondConservativeBudgetNeverRequestsAConnectionOrDisablesTimeout() throws Exception {
        try(var f=new RunEventReaderAuthorityFixture();var r=f.open(new SubjectKey("tenant-a","alice"),"r_"+"e".repeat(32),cn.agentplatform.common.Digest.sha256("e"),0)){
            var c=r.currentCapture();var ds=new org.springframework.jdbc.datasource.AbstractDataSource(){public java.sql.Connection getConnection(){throw new AssertionError("NO_SQL_BELOW_ONE_MS");}public java.sql.Connection getConnection(String u,String p){return getConnection();}};
            var w=new RunEventReader.Work(new org.springframework.jdbc.core.JdbcTemplate(ds),r,c,System.nanoTime()-c.remainingNanos()+500000);
            rejected(()->w.query("select 1",1));
        }
    }
    @Test void emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences() {
        assertThat(java.util.Arrays.stream(RunEventReader.EventEmission.class.getDeclaredConstructors())).allSatisfy(c->assertThat(java.lang.reflect.Modifier.isPrivate(c.getModifiers())).isTrue());
        for(Class<?> type:List.of(RunEventReader.EventEmission.class,RunEventReader.Borrow.class))for(var method:type.getMethods())if(method.getDeclaringClass()!=Object.class)assertThat(method.getReturnType()).isNotIn(String.class,byte[].class,com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(RunEventReader.class.getAnnotations()).isEmpty();
    }
    @Test void deploymentRepresentationModelCanonicalAndTotalBudgetsAreIndependentHardLimits() {
        assertThat(RunEventReader.MODEL_UNITS).isEqualTo(64L*1024*1024);
        assertThat(MaterialScopeStore.OBSERVED_NORMALIZED_BYTES).isEqualTo(8L*1024*1024);
        assertThat(RunEventReader.REPRESENTATION_UPPER_PER_WORKSPACE).isEqualTo(192L*1024*1024);
        var ledger=new RunEventReader.Ledger();try(var reservation=ledger.reserve(192L*1024*1024)){
            capacity(()->ledger.reserve(1),"REPRESENTATION_TOTAL");assertThat(ledger.current).isEqualTo(192L*1024*1024);
        }assertThat(ledger.current).isZero();capacity(()->ledger.reserve(Long.MAX_VALUE),"REPRESENTATION_TOTAL");
    }
    static void capacity(Runnable r,String dimension){assertThatThrownBy(r::run).isInstanceOfSatisfying(RunEventReader.CapacityFailure.class,e->assertThat(e.dimension()).isEqualTo(dimension));}
    @Test void modelIntersectionIsAdmittedBeforeSecondDomAndTemporaryReservationsUnwind() throws Exception {
        try(var f=new RunEventReaderAuthorityFixture();var r=f.open(new SubjectKey("tenant-a","alice"),"r_"+"f".repeat(32),cn.agentplatform.common.Digest.sha256("f"),0)){
            var w=new RunEventReader.Work(null,r,r.currentCapture(),System.nanoTime());String raw="["+"0,".repeat(65533)+"0]";
            w.json(raw,false);long held=w.ledger.current;
            capacity(()->w.json(raw,false),"REPRESENTATION_MODEL");assertThat(w.ledger.current).isEqualTo(held);assertThat(w.representationUnits).isLessThanOrEqualTo(64L*1024*1024);
            assertThat(w.maxParserBackingChars).isZero(); // Numeric array: no string backing buffer requested.
        }
    }
    @Test void canonicalExactBoundaryAndLongAccumulationOverflowFailBeforeExpansion() {
        var exact=com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("1e8388605"));assertThat(MaterialScopeStore.observedCanonicalCost(exact)).isEqualTo(8388608);
        var extra=com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("1e8388606"));capacity(()->MaterialScopeStore.observedCanonicalCost(extra),"NORMALIZED_BYTES");
        capacity(()->RunEventReader.Limits.add(Long.MAX_VALUE-1,2,Long.MAX_VALUE,"OVERFLOW"),"OVERFLOW");
    }
    @Test void captureInitialCursorAndRemainingAreLiveLocalOperations() throws Exception {
        try(var f=new RunEventReaderAuthorityFixture()) {
            var owner=new SubjectKey("tenant-a","alice");
            try(var response=f.open(owner,"r_"+"b".repeat(32),cn.agentplatform.common.Digest.sha256("b"),7)) {
                var c=response.currentCapture();int calls=f.iam.requestAttempts();
                assertThat(java.util.Arrays.stream(c.getClass().getMethods()).map(java.lang.reflect.Method::getName)).as("live signed cursor getter").contains("initialCursor","remainingNanos");
                assertThat(c.getClass().getMethod("initialCursor").invoke(c)).isEqualTo(7L);
                assertThat((Long)c.getClass().getMethod("remainingNanos").invoke(c)).isPositive();
                assertThat(f.iam.requestAttempts()).isEqualTo(calls);
            }
        }
    }
}
