package cn.agentplatform.app.application;

import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingPlanTest {
    static Map<String,Object> field(String name,String kind,boolean required) {
        return Map.of("param",name,"label",name,"kind",kind,"required",required);
    }
    static Map<String,Object> returns() {
        return Map.of("summaryTemplate","查询完成","cardTemplateEnabled",false,"cardFields",List.of(),"fileRefPaths",List.of());
    }
    static Map<String,Object> plan(List<Map<String,Object>> fields,Map<String,Object> values,Map<String,Object> mappings) {
        return new LinkedHashMap<>(Map.of("fieldsBundle",new LinkedHashMap<>(Map.of("fields",fields,"mappings",mappings,"returns",returns())),
                "values",values,"selectedOperations",List.of("read")));
    }
    @Test void mappingPreservesFalsyAndNull() {
        var values=new LinkedHashMap<String,Object>();values.put("enabled",false);values.put("count",0);values.put("note",null);values.put("items",List.of("a","b"));
        var mappings=new LinkedHashMap<String,Object>();
        for(String key:values.keySet())mappings.put(key,Map.of("kind","direct","from",key));
        var source=plan(List.of(field("enabled","boolean",true),field("count","number",true),field("note","text",true),field("items","text",false)),values,mappings);
        var actual=OnboardingPlan.arguments(source);
        assertEquals(false,actual.get("enabled"));assertEquals(0,actual.get("count"));assertTrue(actual.containsKey("note"));assertNull(actual.get("note"));
        assertEquals(List.of("a","b"),actual.get("items"));assertNotSame(values,actual);
    }

    @Test void draftAllowsIncompleteValuesButExecutionRequiresMappedRequiredKeys() {
        var missing=plan(List.of(field("count","number",true)),Map.of(),Map.of("count",Map.of("kind","direct","from","count")));
        assertDoesNotThrow(()->OnboardingPlan.normalize(missing));
        assertThrows(PlatformException.class,()->OnboardingPlan.arguments(missing));
        var unmapped=plan(List.of(field("count","number",true)),Map.of("count",0),Map.of());
        assertThrows(PlatformException.class,()->OnboardingPlan.arguments(unmapped));
        var optional=plan(List.of(field("note","text",false)),Map.of(),Map.of("note",Map.of("kind","direct","from","note")));
        assertTrue(OnboardingPlan.arguments(optional).isEmpty());
    }

    @Test void directReadsOriginalValuesAndConstantsKeepJsonTypes() {
        var constant=new LinkedHashMap<String,Object>();constant.put("kind","constant");constant.put("value",null);
        var input=plan(List.of(field("first","text",false),field("second","text",false),field("nil","text",true),field("enabled","boolean",false)),
                Map.of("first","A","second","B"),Map.of("first",Map.of("kind","direct","from","second"),"second",Map.of("kind","direct","from","first"),
                        "nil",constant,"enabled",Map.of("kind","constant","value",false)));
        var actual=OnboardingPlan.arguments(input);
        assertEquals("B",actual.get("first"));assertEquals("A",actual.get("second"));assertEquals(false,actual.get("enabled"));
        assertTrue(actual.containsKey("nil"));assertNull(actual.get("nil"));
    }

    @Test void rejectsCoercionInvalidEnumsAndNonFiniteNumbers() {
        for(Object value:List.of("0",Double.NaN,Double.POSITIVE_INFINITY)){
            var input=plan(List.of(field("count","number",false)),Map.of("count",value),Map.of());
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(input));
        }
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(List.of(field("enabled","boolean",false)),Map.of("enabled","false"),Map.of())));
        var choice=new LinkedHashMap<>(field("state","enum",false));choice.put("enumValues",List.of("A","B"));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(List.of(choice),Map.of("state","C"),Map.of())));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(List.of(field("state","enum",false)),Map.of(),Map.of())));
    }

    @Test void rejectsUnknownFieldsSourcesDuplicateParametersAndExecutableMappings() {
        var definition=List.of(field("count","number",false));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(definition,Map.of("unknown",1),Map.of())));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(definition,Map.of(),Map.of("unknown",Map.of("kind","constant","value",1)))));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(definition,Map.of(),Map.of("count",Map.of("kind","direct","from","unknown")))));
        assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(List.of(field("count","number",false),field("count","number",false)),Map.of(),Map.of())));
        for(var mapping:List.of(Map.of("kind","script","value","1+1"),Map.of("kind","constant"),
                Map.of("kind","constant","value",List.of(1)),Map.of("kind","constant","value",Map.of("expression","1+1"))))
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(definition,Map.of(),Map.of("count",mapping))));
    }

    @Test void recursiveDangerAndAuthorityFieldsAreNeverAccepted() {
        var base=plan(List.of(field("note","text",false)),Map.of(),Map.of());
        for(String field:List.of("tenantId","principalId","authRef","endpoint")){
            var input=new LinkedHashMap<>(base);input.put(field,"attacker");
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(input));
        }
        for(Object value:List.of("${system.exec()}","<script>run()</script>","javascript:run()",List.of(Map.of("expression","run()"))))
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(plan(List.of(field("note","text",false)),Map.of("note",value),Map.of())));
    }

    @Test @SuppressWarnings("unchecked") void connectionReferenceOnlyContainsFixedIdentifiersAndExactRevisions() {
        var input=plan(List.of(),Map.of(),Map.of());
        var connection=new LinkedHashMap<String,Object>(Map.of("profileId","profile-read","profileRev",2,"bindingId","binding-read","bindingRev",3,"operation","read","environment","dev"));
        input.put("connectionRef",connection);input.put("modelEntryId",7);
        var normalized=OnboardingPlan.normalize(input);var ref=(Map<String,Object>)normalized.get("connectionRef");
        assertEquals(2L,ref.get("profileRev"));assertEquals(7L,normalized.get("modelEntryId"));assertEquals("dev",ref.get("environment"));
        for(String forbidden:List.of("tenantId","principalId","authRef","endpoint")){
            var changed=new LinkedHashMap<>(connection);changed.put(forbidden,"x");input.put("connectionRef",changed);
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(input));
        }
        input.put("connectionRef",connection);
        for(Number invalid:List.of(0,-1,1.5)){
            var changed=new LinkedHashMap<>(connection);changed.put("bindingRev",invalid);input.put("connectionRef",changed);
            assertThrows(PlatformException.class,()->OnboardingPlan.normalize(input));
        }
    }

    @Test @SuppressWarnings("unchecked") void fieldsAreDerivedFromActualSchemaAndDoNotInventDefaults() {
        var schema=Map.<String,Object>of("type","object","required",List.of("count"),"properties",Map.of(
                "count",Map.of("type","integer","title","数量","default",0),"enabled",Map.of("type","boolean"),
                "state",Map.of("type","string","enum",List.of("A","B")),"nullable",Map.of("type",List.of("string","null")),
                "names",Map.of("type","array","items",Map.of("type","string"))));
        var bundle=OnboardingPlan.fields(schema);var rows=(List<Map<String,Object>>)bundle.get("fields");
        var indexed=new HashMap<String,Map<String,Object>>();for(var row:rows)indexed.put(row.get("param").toString(),row);
        assertEquals("number",indexed.get("count").get("kind"));assertEquals("数量",indexed.get("count").get("label"));assertEquals(true,indexed.get("count").get("required"));
        assertFalse(indexed.get("count").containsKey("default"));assertEquals("enum",indexed.get("state").get("kind"));
        assertEquals(List.of("A","B"),indexed.get("state").get("enumValues"));assertEquals("text",indexed.get("names").get("kind"));
        assertEquals(Map.of("kind","direct","from","enabled"),((Map<?,?>)bundle.get("mappings")).get("enabled"));
    }

    @Test void unsupportedSchemaIsExplicitInsteadOfPretendingToHaveAForm() {
        for(var schema:List.of(Map.<String,Object>of("type","array"),Map.<String,Object>of("type","object","$ref","#/$defs/Input"),
                Map.<String,Object>of("type","object","properties",Map.of("nested",Map.of("type","object"))),
                Map.<String,Object>of("type","object","properties",Map.of("ref",Map.of("$ref","#/$defs/Ref"))),
                Map.<String,Object>of("type","object","required",List.of("missing"))))
            assertThrows(PlatformException.class,()->OnboardingPlan.fields(schema));
    }

    @Test @SuppressWarnings("unchecked") void returnPresentationUsesExistingRuntimeDialectWithoutExecutingAnything() {
        var input=plan(List.of(),Map.of(),Map.of());
        var bundle=(Map<String,Object>)input.get("fieldsBundle");
        bundle.put("returns",Map.of("summaryTemplate","共 {{ data.total }} 条","cardTemplateEnabled",true,
                "cardFields",List.of(Map.of("label","总数","from","$.data.total")),"fileRefPaths",List.of("artifactId","$.data.refs[0]")));
        var spec=OnboardingPlan.returnSpec(input);
        assertEquals("$",spec.get("structuredPath"));assertEquals(Map.of("mode","TEMPLATE","tpl","共 {{ data.total }} 条"),spec.get("modelSummary"));
        assertEquals(Map.of("总数","{{data.total}}"),spec.get("cardTemplate"));assertEquals(List.of("$.artifactId","$.data.refs[0]"),spec.get("fileRefPaths"));
        assertFalse(spec.containsKey("script"));
    }

    @Test @SuppressWarnings("unchecked") void returnExpressionWildcardsAndMalformedTemplatesAreRejected() {
        var input=plan(List.of(),Map.of(),Map.of());var bundle=(Map<String,Object>)input.get("fieldsBundle");
        for(String template:List.of("{{ run() }}","${run()}","{{ item[*] }}","{{broken")){
            bundle.put("returns",Map.of("summaryTemplate",template));assertThrows(PlatformException.class,()->OnboardingPlan.returnSpec(input));
        }
        bundle.put("returns",Map.of("fileRefPaths",List.of("../secret")));assertThrows(PlatformException.class,()->OnboardingPlan.returnSpec(input));
        bundle.put("returns",Map.of("cardTemplateEnabled",true,"cardFields",List.of()));assertThrows(PlatformException.class,()->OnboardingPlan.returnSpec(input));
    }

    @Test void operationSelectionUsesCurrentExactSupportedSet() {
        var input=plan(List.of(),Map.of(),Map.of());
        var selected=OnboardingPlan.selectOperations(input,List.of(Map.of("operationId","read","supported",true,"path","/items")));
        assertEquals("/items",selected.getFirst().get("path"));
        assertThrows(PlatformException.class,()->OnboardingPlan.selectOperations(input,List.of(Map.of("operationId","read","supported",false))));
        assertThrows(PlatformException.class,()->OnboardingPlan.selectOperations(input,List.of(Map.of("operationId","other","supported",true))));
        assertThrows(PlatformException.class,()->OnboardingPlan.selectOperations(input,List.of(Map.of("operationId","read","supported",true),Map.of("operationId","read","supported",true))));
        input.put("selectedOperations",List.of());assertThrows(PlatformException.class,()->OnboardingPlan.selectOperations(input,List.of()));
        input.put("selectedOperations",List.of("read","read"));assertThrows(PlatformException.class,()->OnboardingPlan.normalize(input));
    }
}
