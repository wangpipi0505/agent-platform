package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Handwritten canonical bytes + JDK digest are independent of the implementation under test. */
class PortalEventsMaterialVerifierTest {
    static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String expected(String canonical) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf(canonical))); }
    static String actual(String input) throws Exception {
        try (var owner=PortalEventsPacketBudget.SHARED.acquire();var fee=owner.reserve(PortalEventsMaterialVerifier.MATERIAL_BYTES)) {
            return PortalEventsMaterialVerifier.canonicalSha(utf(input));
        }
    }
    @Test void frozenAssetIsClasspathOnlyAndHasFullParents() throws Exception {
        var asset=PortalEventsMaterialAuthorityTest.asset();assertEquals(69,asset.model().get("scopes").size());assertEquals(68,PortalEventsPacketDecoderTest.parentCount(asset.model()));
    }
    @Test void preciseNumbersUnicodeNullAndNestedOrderingMatchIndependentBytes() throws Exception {
        String[][] vectors={
            {"{\"b\":1.0,\"a\":-0.00}","{\"a\":0,\"b\":1}"},
            {"[123456789012345678901234567890,1e3,1e-7,0.0000000]","[123456789012345678901234567890,1000,1E-7,0]"},
            {"{\"z\":{\"b\":null,\"a\":true},\"a\":[3,2,1]}","{\"a\":[3,2,1],\"z\":{\"a\":true,\"b\":null}}"},
            {"[1.234567890123456789012345678900,0.00012300,-0e9]","[1.2345678901234567890123456789,0.000123,0]"},
            {"{\"界\":\"é😀\\n\\t\\u0001\\\"\\\\\",\"a\":null}","{\"a\":null,\"界\":\"é😀\\n\\t\\u0001\\\"\\\\\"}"},
            {"null","null"},{"1.0","1"},{"0.0","0"}
        };
        for(var v:vectors)assertEquals(expected(v[1]),actual(v[0]),v[0]);
        assertNotEquals(actual("[1,2]"),actual("[2,1]"));assertNotEquals(actual("\"é\""),actual("\"é\""));
    }
    @Test void oversizedExponentIsRejectedBeforeIntegerExpansion() throws Exception {
        for(String input:new String[]{"1e2147483647","1e2147483648","1e-2147483649","-1e9999999999999999999"}) {
            var e=assertThrows(PlatformException.class,()->actual(input));assertEquals("EVENT_MATERIAL_CAPACITY",e.code());
        }
        assertEquals(expected("1E-2147483647"),actual("1e-2147483647"));
        assertEquals(expected("1".repeat(1000)),actual("1".repeat(1000)));
        assertEquals("EVENT_MATERIAL_CAPACITY",assertThrows(PlatformException.class,()->actual("1".repeat(1001))).code());
        assertEquals(expected("1"+"0".repeat(65533)),actual("1e65533"));
        assertEquals("EVENT_MATERIAL_CAPACITY",assertThrows(PlatformException.class,()->actual("1e65534")).code());
    }
    @Test void reachablePayloadTokenByteAndDepthProfilesFailAtTheirOwnGuard() throws Exception {
        // 2 array tokens + 32766 scalar tokens exactly fit; +1 is this parser's token cap.
        String exact="["+"0,".repeat(32765)+"0]";assertEquals(expected(exact),actual(exact));
        assertEquals("EVENT_MATERIAL_CAPACITY",assertThrows(PlatformException.class,()->actual("["+"0,".repeat(32766)+"0]")).code());
        String byteLimit="\""+"x".repeat(262142)+"\"";assertEquals(expected(byteLimit),actual(byteLimit));
        assertEquals("EVENT_MATERIAL_CAPACITY",assertThrows(PlatformException.class,()->actual(byteLimit+" ")).code());
        String depth="[".repeat(64)+"0"+"]".repeat(64);assertEquals(expected(depth),actual(depth));
        assertEquals("EVENT_MATERIAL_CAPACITY",assertThrows(PlatformException.class,()->actual("["+depth+"]")).code());
    }
    @Test void duplicateTrailingInvalidUtf8AndSurrogatesCannotReachDigest() throws Exception {
        for(String input:new String[]{"{\"a\":0,\"a\":1}","{}[]","\"\\uD800\"","\"\\uDC00\"","\ufeff{}"})assertThrows(Exception.class,()->actual(input));
        assertThrows(Exception.class,()->PortalEventsMaterialVerifier.canonicalSha(new byte[]{(byte)0xc0,(byte)0xaf}));
    }
}
