-- Owner operations have their own application-scoped permissions. Existing custom rows take precedence.
INSERT INTO app.role_capability(role, capability, updated_by)
SELECT platform_role, capability, 'bootstrap'
FROM (VALUES ('ADMIN'), ('BUILDER'), ('USER')) roles(platform_role)
CROSS JOIN (VALUES ('agent.plan.revise'), ('agent.plan.confirm'), ('agent.plan.execute')) permissions(capability)
ON CONFLICT DO NOTHING;

INSERT INTO app.role_capability(role, capability, updated_by)
VALUES ('OPERATOR', 'agent.plan.operate', 'bootstrap')
ON CONFLICT DO NOTHING;
