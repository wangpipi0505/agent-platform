package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Encoder mechanics only. The test checker is not a production gateway decoder. */
class PortalEventsPacketCodecTest {
    String encode(PortalEventsPacketWriter.Encoding action)throws Exception{var out=new ByteArrayOutputStream();action.write(new PortalEventsPacketWriter.Json(out,80,262144,1048576,1000));return out.toString(StandardCharsets.UTF_8);}
    @Test void fixedWireSizeUsesCheckedLongAndIntersectsEveryBound(){assertThat(PortalEventsPacketWriter.wireBytes(8192,8388608,262144)).isEqualTo(8659012);for(long n:new long[]{-1,0,8193,Long.MAX_VALUE})assertThatThrownBy(()->PortalEventsPacketWriter.wireBytes(n,1,0)).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->PortalEventsPacketWriter.wireBytes(1,8388609,0)).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->PortalEventsPacketWriter.wireBytes(1,1,262145)).isInstanceOf(RunEventReader.CapacityFailure.class);}
    @Test void sortedKeysEscapingSupplementaryUnicodeAndOriginalArraysRoundTrip()throws Exception{String result=encode(j->j.value(Json.read("{\"z\": [3,2,1], \"a\":\"观察😀\\n\\t\\\"\\\\\", \"b\":null}")));assertThat(result).isEqualTo("{\"a\":\"观察😀\\u000a\\u0009\\\"\\\\\",\"b\":null,\"z\":[3,2,1]}");assertThat(Json.read(result)).isEqualTo(Json.read("{\"a\":\"观察😀\\n\\t\\\"\\\\\",\"b\":null,\"z\":[3,2,1]}"));}
    @Test void unpairedSurrogatesAreRejectedAndNeverReplaced()throws Exception{for(String s:new String[]{"\ud800","\udc00","x\ud800y"})assertThatThrownBy(()->encode(j->j.string(s))).isInstanceOf(PlatformException.class);}
    @Test void numericNormalizationPreservesLargeIntegersAndRejectsExponentExpansionBeforeAllocation()throws Exception{assertThat(encode(j->j.number(new BigDecimal("1.2300")))).isEqualTo("1.23");assertThat(encode(j->j.number(new BigDecimal("-0.000")))).isEqualTo("0");assertThat(encode(j->j.number(new BigDecimal("123E+3")))).isEqualTo("123000");assertThat(encode(j->j.number(new BigInteger("123456789012345678901234567890")))).isEqualTo("123456789012345678901234567890");assertThatThrownBy(()->encode(j->j.number(new BigDecimal("1E+1000000000")))).isInstanceOf(RunEventReader.CapacityFailure.class);}
    @Test void headerStringNumberDepthAndTokenLimitsAreCapacityFailures()throws Exception{var sink=OutputStream.nullOutputStream();assertThatThrownBy(()->new PortalEventsPacketWriter.Json(sink,8,2048,4096,19).string("x".repeat(4097))).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->new PortalEventsPacketWriter.Json(sink,8,2048,4096,19).number(new BigInteger("99999999999999999999"))).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->{var j=new PortalEventsPacketWriter.Json(sink,1,20,100,19);j.object();j.object();}).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->{var j=new PortalEventsPacketWriter.Json(sink,8,1,100,19);j.bool(true);j.nil();}).isInstanceOf(RunEventReader.CapacityFailure.class);}
    @Test void ledgerRefusesBeforeAllocationAndReleasesReservation(){var ledger=new RunEventReader.Ledger();try(var held=ledger.reserve(192L*1024*1024)){assertThatThrownBy(()->ledger.reserve(1)).isInstanceOf(RunEventReader.CapacityFailure.class);}assertThat(ledger.current).isZero();}
    @Test void closingTokensCountAgainstFiniteParserBudget(){assertThatThrownBy(()->{var j=new PortalEventsPacketWriter.Json(OutputStream.nullOutputStream(),8,2,100,19);j.array();j.nil();j.endArray();}).isInstanceOf(RunEventReader.CapacityFailure.class);assertThatThrownBy(()->{var j=new PortalEventsPacketWriter.Json(OutputStream.nullOutputStream(),8,3,100,19);j.object();j.field("a");j.nil();j.endObject();}).isInstanceOf(RunEventReader.CapacityFailure.class);}
}
