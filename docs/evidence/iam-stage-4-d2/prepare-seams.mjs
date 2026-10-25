import fs from 'node:fs';
function edit(p,fn){const s=fs.readFileSync(p,'utf8'), cr=s.includes('\r\n');let n=fn(s.replaceAll('\r\n','\n'));fs.writeFileSync(p,cr?n.replaceAll('\n','\r\n'):n);}
edit('admin-common/pom.xml',s=>s.replace('</project>','<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version><executions><execution><id>finite-test-fixtures</id><phase>process-test-classes</phase><goals><goal>test-jar</goal></goals></execution></executions></plugin></plugins></build></project>'));
edit('app-management/pom.xml',s=>s.replace('</dependencies>','<dependency><groupId>cn.agentplatform</groupId><artifactId>admin-common</artifactId><version>${project.version}</version><type>test-jar</type><scope>test</scope></dependency></dependencies>'));
edit('admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java',s=>s.replace('final class LoopbackAuthority','public final class LoopbackAuthority').replace('    LoopbackAuthority()','    public LoopbackAuthority()').replace('    AuthorityProperties config(','    public AuthorityProperties config(').replace('    HttpCurrentAuthorityClient client(','    public HttpCurrentAuthorityClient client(').replace('    String portal(){','    public String portal(){').replace('    String integration(Map','    public String integration(Map').replace('    Map<String,Object> integrationClaims(){','    public Map<String,Object> integrationClaims(){').replace('    Map<String,Object> base(','    public Map<String,Object> base(').replace('    String sign(','    public String sign(').replace('    String policyIssuer(){','    public String policyIssuer(){').replace('    String issuer(){','    public String issuer(){').replace('    void handle(HttpExchange x){',`    public Clock clock(){return clock;}
    public RSAKey policySigningKey(){return policyKey;}
    public RSAKey authoritySigningKey(){return key;}
    public int policyCalls(){return policyCalls.get();}
    public int resolverToAuthorityCalls(){return resolverToAuthority.get();}
    void handle(HttpExchange x){`));
edit('admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java',s=>s.replace('    private final java.lang.reflect.Method healthHandler;','    private final java.lang.reflect.Method healthHandler;\n    private final java.lang.reflect.Method policyHandler;\n    private final PolicyRequestVerifier policyVerifier;').replace('        if(!AuthorityProperties.SERVICES.contains(serviceId))',`        this(serviceId,routes,authority,healthHandler,null,null);
    }
    public ServiceAuthorization(String serviceId,ServiceRoutePolicy routes,CurrentAuthorityClient authority,
            java.lang.reflect.Method healthHandler,java.lang.reflect.Method policyHandler,PolicyRequestVerifier policyVerifier) {
        if((policyHandler==null)!=(policyVerifier==null))throw new IllegalArgumentException("POLICY_HANDLER_VERIFIER_REQUIRED");
        this.policyHandler=policyHandler;this.policyVerifier=policyVerifier;
        if(!AuthorityProperties.SERVICES.contains(serviceId))`));
