CREATE TABLE management_identity_links (
 ruoyi_tenant VARCHAR(64) NOT NULL, ruoyi_user VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL UNIQUE REFERENCES principals(id),
 PRIMARY KEY(ruoyi_tenant,ruoyi_user)
);
-- Explicit local bootstrap binding, never a wildcard tenant or user grant.
INSERT INTO management_identity_links(ruoyi_tenant,ruoyi_user,principal_id)
SELECT '000000','1',p.id FROM principals p JOIN employee_passwords e ON e.principal_id=p.id
WHERE e.username='admin' AND p.tenant_id='demo' AND p.kind='USER' AND p.enabled=true;
