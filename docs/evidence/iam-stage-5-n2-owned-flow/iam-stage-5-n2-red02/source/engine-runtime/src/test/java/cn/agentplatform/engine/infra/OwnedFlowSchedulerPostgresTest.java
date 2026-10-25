package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;

class OwnedFlowSchedulerPostgresTest {
    @BeforeAll static void isolatedDatabase() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V[0-9]+__.*"))
                    .mapToInt(n -> Integer.parseInt(n.substring(1,n.indexOf("__")))).max().orElseThrow()).isEqualTo(217);
        }
        var admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",System.getenv("AGENT_DB_PASSWORD")));
        boolean exists = Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname='agent_platform_iam_owned_flow_v12')",Boolean.class));
        System.out.println("N2_DATABASE_EXISTED="+exists+",SOURCE_VMAX=217");
        if (!exists) admin.execute("CREATE DATABASE agent_platform_iam_owned_flow_v12");
    }

    static RunAuthorityFixture fixture() throws Exception { return new RunAuthorityFixture("agent_platform_iam_owned_flow_v12"); }

    @Test void protectedClaimOnlyOwnsDispatchBeforeCurrentAndReadsNoPayload() throws Exception {
        try (var f = fixture()) {
            var i=FlowMaterialScopePostgresTest.insert(f);
            int reads=f.ds.payloadReads.get();
            assertThatCode(() -> {
                var claim=new RunScheduleRepository(FlowMaterialScopePostgresTest.trace(f)).claimSpecific(i.run(),Queue.INTERACTIVE,"n2-red",Duration.ofSeconds(30));
                assertThat(claim).isNotNull();
            }).as("protected queue must claim only dispatch metadata before current").doesNotThrowAnyException();
            assertThat(f.db.queryForObject("select status from engine.run where id=?",String.class,i.run())).isEqualTo("PENDING");
            assertThat(f.db.queryForObject("select count(*) from engine.run_event where run_id=?",Long.class,i.run())).isZero();
            assertThat(f.ds.payloadReads.get()).isEqualTo(reads);
        }
    }
}
