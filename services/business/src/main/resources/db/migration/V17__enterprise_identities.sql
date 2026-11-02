CREATE TABLE enterprise_identities (
 tenant_id VARCHAR(64) NOT NULL, provider_id VARCHAR(64) NOT NULL,
 subject_hash VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(provider_id,subject_hash),
 FOREIGN KEY(tenant_id,principal_id) REFERENCES principals(tenant_id,id)
);
CREATE INDEX enterprise_identity_principal_idx ON enterprise_identities(tenant_id,principal_id);
