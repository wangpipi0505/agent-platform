create table iam_revocation.portal_session (
    origin_id varchar(36) primary key,
    revoked_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null
);
create index portal_session_expiry on iam_revocation.portal_session (expires_at);
