-- Initial build permissions. Existing operator changes can update these rows at runtime.
INSERT INTO app.role_capability(role, capability, updated_by) VALUES
 ('ADMIN','agent.view','bootstrap'), ('ADMIN','agent.build','bootstrap'),
 ('ADMIN','agent.publish','bootstrap'), ('ADMIN','skill.manage','bootstrap'),
 ('ADMIN','plugin.manage','bootstrap'), ('ADMIN','model.manage','bootstrap'),
 ('ADMIN','auth.grant','bootstrap'), ('ADMIN','audit.read','bootstrap'),
 ('BUILDER','agent.view','bootstrap'), ('BUILDER','agent.build','bootstrap'),
 ('BUILDER','agent.publish','bootstrap'), ('BUILDER','skill.manage','bootstrap'),
 ('OPERATOR','agent.view','bootstrap'), ('OPERATOR','audit.read','bootstrap'),
 ('USER','agent.view','bootstrap')
ON CONFLICT DO NOTHING;

INSERT INTO app.capability_page(capability, page_route, updated_by) VALUES
 ('agent.view','/apps','bootstrap'),
 ('agent.build','/model/prompt-templates','bootstrap'),
 ('agent.build','/assets/rules','bootstrap'),
 ('agent.build','/evaluations','bootstrap'),
 ('skill.manage','/skills','bootstrap'),
 ('plugin.manage','/assets/plugins','bootstrap'),
 ('plugin.manage','/assets/mcp','bootstrap'),
 ('model.manage','/model/management','bootstrap'),
 ('auth.grant','/platform/authorizations','bootstrap'),
 ('audit.read','/audit','bootstrap')
ON CONFLICT DO NOTHING;
