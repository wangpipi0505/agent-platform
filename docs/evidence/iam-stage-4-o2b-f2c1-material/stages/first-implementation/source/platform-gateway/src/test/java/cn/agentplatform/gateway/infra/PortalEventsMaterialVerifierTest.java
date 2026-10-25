package cn.agentplatform.gateway.infra;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortalEventsMaterialVerifierTest {
    @Test void frozenAssetIsClasspathOnlyAndHasFullParents() throws Exception {
        var asset = PortalEventsMaterialAuthorityTest.asset();
        assertEquals(69, asset.model().get("scopes").size());
        assertEquals(68, PortalEventsPacketDecoderTest.parentCount(asset.model()));
    }
}
