create table account_avatars (
 tenant_id varchar(64) not null,
 principal_id varchar(64) not null,
 image text not null,
 primary key (tenant_id, principal_id)
);
