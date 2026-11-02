-- Independent Android session policy. Execute in a single transaction.
-- MD5(client_key) follows the existing identity-client convention; this ID is not a credential.
lock table sys_client in share row exclusive mode;
insert into sys_client(id,client_id,client_key,client_secret,grant_type,device_type,active_timeout,timeout,status,del_flag,create_dept,create_by,create_time,update_by,update_time)
select (select coalesce(max(id),0)+1 from sys_client),'b12c16d37fb30716a9315e197290c56e','funnywork-android','', 'password','android',-1,2592000,'0','0',103,1,now(),1,now()
where not exists(select 1 from sys_client where client_id='b12c16d37fb30716a9315e197290c56e');
