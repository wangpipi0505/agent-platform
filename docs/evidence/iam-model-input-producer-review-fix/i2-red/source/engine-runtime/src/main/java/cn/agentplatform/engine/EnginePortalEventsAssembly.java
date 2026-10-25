package cn.agentplatform.engine;

import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.engine.application.PortalRunEventsService;
import cn.agentplatform.engine.infra.*;
import javax.sql.DataSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;

/** Plain lite bean source; only an explicitly controlled receiver may import it. */
public class EnginePortalEventsAssembly {
    @Bean RunEventReader portalRunEventReader(DataSource source){return new RunEventReader(source);}
    @Bean(destroyMethod="close") PortalRunEventsService portalRunEventsService(RunEventReader reader,HttpCurrentAuthorityClient authority,ChunkBus chunks,Clock clock){return new PortalRunEventsService(reader,authority,chunks,clock);}
}
