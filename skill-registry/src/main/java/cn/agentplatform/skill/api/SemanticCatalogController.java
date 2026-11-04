package cn.agentplatform.skill.api;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.SemanticCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
/** 只读配置选择集；客户端不能提供身份、release集合、URL或凭据。 */
@RestController
public class SemanticCatalogController {
    private final SemanticCatalog catalog;
    public SemanticCatalogController(SemanticCatalog catalog){this.catalog=catalog;}
    @GetMapping("/admin/skills/semantic-catalog")
    public Object list(){return ApiResponse.ok(catalog.list(Actor.current()));}
}
