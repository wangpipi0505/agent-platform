package cn.agentplatform.skill.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;
import java.util.*;
import org.junit.jupiter.api.Test;
/** 当前主体候选/实时回读/失败分项逻辑；替身不证明OR真实授权或数据库。 */
class SemanticCatalogTest {
  final Actor actor=new Actor("tenant","alice",Set.of("EDITOR"),Map.of());
  final UUID release=UUID.fromString("11111111-1111-1111-1111-111111111111");
  final OntologyConsumeIdentity identities=mock(OntologyConsumeIdentity.class);
  final OntologyManifestClient sources=mock(OntologyManifestClient.class);
  final SemanticDefinitionClient definitions=mock(SemanticDefinitionClient.class);
  final SemanticCatalog service=new SemanticCatalog(identities,sources,definitions);
  final Map<String,Object> ref=Map.of("spaceCode","orders","releaseId",release.toString(),"version","v1","contentDigest","a".repeat(64));
  @Test void onlyCurrentActorCandidatesAreRereadAndNoSecretIsReturned(){
    when(identities.releaseIds(actor)).thenReturn(Set.of(release));
    var formal=new OntologyManifestClient.FormalSource("orders",release,"v1","a".repeat(64),"b".repeat(64),List.of(),List.of(),Map.of());
    when(sources.resolve(release,null)).thenReturn(formal);
    when(definitions.query(eq(release),anyMap())).thenReturn(new SemanticDefinitionClient.Projection(ref,Map.of(),Map.of(),true,false,null,"c".repeat(64)));
    var result=service.list(actor);
    assertThat((List<?>)result.get("releases")).hasSize(1);
    assertThat(result).containsEntry("complete",true);
    assertThat(Json.write(result)).doesNotContain("secretRef","ontopa_","endpoint","authRef");
    verify(identities).releaseIds(actor);verify(sources).resolve(release,null);
  }
  @Test void revokedOrUnavailableReleaseCannotRemainAChoice(){
    when(identities.releaseIds(actor)).thenReturn(Set.of(release));
    when(sources.resolve(release,null)).thenThrow(new PlatformException(403,"FORBIDDEN","当前主体已撤权"));
    var result=service.list(actor);
    assertThat((List<?>)result.get("releases")).isEmpty();assertThat(result).containsEntry("complete",false);
    assertThat((List<?>)result.get("errors")).hasSize(1);verifyNoInteractions(definitions);
  }
  @Test void mismatchedDefinitionCannotBeSelected(){
    when(identities.releaseIds(actor)).thenReturn(Set.of(release));
    when(sources.resolve(release,null)).thenReturn(new OntologyManifestClient.FormalSource("other",release,"v1","a".repeat(64),"b".repeat(64),List.of(),List.of(),Map.of()));
    when(definitions.query(eq(release),anyMap())).thenReturn(new SemanticDefinitionClient.Projection(ref,Map.of(),Map.of(),true,false,null,"c".repeat(64)));
    assertThat((List<?>)service.list(actor).get("releases")).isEmpty();
  }
}
