package cn.agentplatform.model.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.model.application.InitialStreamIntegrationFixture;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@Timeout(22)
class InitialStreamSendFactsPostgresTest {
    @Test void realCommitThenAckLossPreservesSendingSentAndNeverRefundsOrDispatches() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.dataSource.fault="ack-loss";
            var response=f.post(f.selected(),f.request);
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).doesNotContain("event:final");
            assertThat(f.dataSource.commitsThenThrown).hasValue(1);
            assertThat(f.providerCalls()).isZero();
            var independent=new JdbcTemplate(f.pool);
            var row=independent.queryForMap("select l.status,l.total_tokens,l.usage_reconciliation,q.status as reservation_status,l.metering_snapshot::text as metering from model.call_log l join model.quota_reservation q on q.tenant_id=l.tenant_id and q.reservation_id=l.quota_receipt->>'id' where l.invocation_ref=?",f.invocation());
            assertThat(row).containsEntry("status","SENDING").containsEntry("reservation_status","SENT").containsEntry("total_tokens",null);
            var metadata=Json.read(row.get("metering").toString()).path("initialRequest");
            assertThat(metadata.path("wireUtf8").asText()).isNotBlank();
            assertThat(Digest.sha256(metadata.path("wireUtf8").asText())).isEqualTo(metadata.path("rawWireSha256").asText());
            assertThat(metadata.path("invocationRef").asText()).isEqualTo(f.invocation());
            assertThat(metadata.path("reservationId").asText()).isNotBlank();
            f.dataSource.fault="none"; f.reconcile();
            assertThat(independent.queryForObject("select status from model.quota_reservation where reservation_id=?",String.class,metadata.path("reservationId").asText())).isEqualTo("SENT");
            assertThat(f.providerCalls()).isZero();
            Files.writeString(f.dir.resolve("commit-unknown-facts.json"),Json.write(Map.of("durable",row,"unresolvedRecovery",true,"dispatches",0,"commitThenThrow",1)));
        }
    }

    @Test void realSqlAbortRollsBackSendingAndRefundsOnlyProvenNoSend() throws Exception {
        try(var f=new InitialStreamIntegrationFixture()) {
            f.dataSource.fault="rollback";
            var response=f.post(f.selected(),f.request);
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(f.dataSource.realSqlErrors).hasValue(1);
            assertThat(f.logs()).isZero(); assertThat(f.providerCalls()).isZero();
            var row=new JdbcTemplate(f.pool).queryForMap("select status,usage_reconciliation from model.quota_reservation where invocation_ref=?",f.invocation());
            assertThat(row).containsEntry("status","NO_SEND").containsEntry("usage_reconciliation","RECONCILED");
        }
    }
}
