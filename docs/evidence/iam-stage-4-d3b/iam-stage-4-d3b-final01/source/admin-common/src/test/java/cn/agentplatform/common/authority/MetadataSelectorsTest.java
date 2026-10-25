package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetadataSelectorsTest {
    @Test void canonicalTupleAndDescriptorAreDeterministicAndTenantBound(){
        var r=MetadataSelectors.published("tenant:a","App_1");
        assertThat(r.kind()).isEqualTo("metadata-selector-v1");
        assertThat(r.id()).isEqualTo("[\"app-published-selector.v1\",\"tenant:a\",\"App_1\",\"PUBLISHED\",\"app-published-metadata.v1\"]");
        assertThat(r.versionDigest()).isEqualTo(Digest.sha256("{\"app_code\":\"App_1\",\"projection\":\"app-published-metadata.v1\",\"schema\":\"app-published-selector.v1\",\"selector\":\"PUBLISHED\",\"tenant_id\":\"tenant:a\"}"));
        assertThat(r).isEqualTo(MetadataSelectors.published("tenant:a","App_1")).isNotEqualTo(MetadataSelectors.published("tenant","App_1"));
        assertThat(MetadataSelectors.published("t\"x\\y","App_1").id()).contains("t\\\"x\\\\y");
    }
    @Test void codesAreBoundedBeforeAnyUrlCanBeConstructed(){
        for(String code:List.of("","1app","../app","app?x=1","app/other","a".repeat(65)," app","应用"))assertThatThrownBy(()->MetadataSelectors.published("tenant",code)).isInstanceOf(PlatformException.class);
        for(String tenant:List.of(""," ","t".repeat(129)))assertThatThrownBy(()->MetadataSelectors.published(tenant,"App")).isInstanceOf(PlatformException.class);
    }
    @Test void queryPurposeIsBidirectionalAndPortalOnly(){
        var query=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published("tenant","App"));
        MetadataSelectors.requirePurpose(query,"gateway","delegated","PORTAL","PORTAL");
        for(var call:List.of(new ServiceCall("engine",query.action(),query.resource()),new ServiceCall("app","app.payload",query.resource()),new ServiceCall("app",query.action(),new ResourceBinding("app-version","App@1",Digest.sha256("candidate")))))
            assertThatThrownBy(()->MetadataSelectors.requirePurpose(call,"gateway","delegated","PORTAL","PORTAL")).isInstanceOf(PlatformException.class);
        for(String source:List.of("DELEGATED","INTEGRATION",""))assertThatThrownBy(()->MetadataSelectors.requirePurpose(query,"gateway","delegated",source,"PORTAL")).isInstanceOf(PlatformException.class);
        assertThatThrownBy(()->MetadataSelectors.requirePurpose(query,"engine","delegated","PORTAL","PORTAL")).isInstanceOf(PlatformException.class);
        assertThatThrownBy(()->MetadataSelectors.requirePurpose(query,"gateway","workload","PORTAL","PORTAL")).isInstanceOf(PlatformException.class);
        assertThatThrownBy(()->MetadataSelectors.requirePurpose(query,"gateway","delegated","PORTAL","INTEGRATION")).isInstanceOf(PlatformException.class);
    }
    @Test void catalogGrantsOnlyExactGatewayQueryAndKeepsIntegrationClosed(){
        var rows=new ServiceRoutePolicy().routes().stream().filter(r->r.action().equals(MetadataSelectors.ACTION)).toList();
        assertThat(rows).hasSize(1);var r=rows.getFirst();assertThat(r.target()).isEqualTo("app");assertThat(r.caller()).isEqualTo("gateway");assertThat(r.method()).isEqualTo("GET");assertThat(r.template()).isEqualTo("/internal/apps/{code}/published-metadata");assertThat(r.mode()).isEqualTo("delegated");assertThat(r.integrationAllowed()).isFalse();
    }

    @Test void currentPermissionNarrowingAfterQueryBlocksSecondExchangeAndMainCurrentCheck()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var app=f.client("app")){
            String source=f.portal();var query=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published("tenant-a","App"));
            var queryTicket=gateway.exchange(source,query);app.authorize(queryTicket.value(),query);
            var main=new ServiceCall("app",new ServiceRoutePolicy().require("gateway","app","GET","/admin/apps/{code}").action(),new ResourceBinding("app-version","App@1",Digest.sha256("stored")));
            f.currentRoles=Set.of();assertThatThrownBy(()->gateway.exchange(source,main)).isInstanceOf(PlatformException.class);assertThat(f.grantCount()).isEqualTo(1);
            f.currentRoles=Set.of("EDITOR");var mainTicket=gateway.exchange(source,main);f.currentRoles=Set.of();
            assertThatThrownBy(()->app.authorize(mainTicket.value(),main)).isInstanceOf(PlatformException.class);
            assertThat(mainTicket.operation().grantRef()).isNotEqualTo(queryTicket.operation().grantRef());
        }
    }
}
