package cn.agentplatform.common.authority;
import cn.agentplatform.common.Json;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.oauth2.jwt.*;
/** Temporary compiling seam, replaced after observing the behavioral assertion failure. */
public final class TrustedJwtDecoder implements JwtDecoder {
    public TrustedJwtDecoder(AuthorityProperties config, Clock clock, boolean testProfile) {}
    @Override public Jwt decode(String value) {
        var claims=Json.read(new String(Base64.getUrlDecoder().decode(value.split("\\.")[1]),StandardCharsets.UTF_8),java.util.Map.class);
        var converted=MappedJwtClaimSetConverter.withDefaults(java.util.Map.of()).convert(claims);
        return Jwt.withTokenValue(value).header("alg","RS256").claims(c->c.putAll(converted)).build();
    }
}
