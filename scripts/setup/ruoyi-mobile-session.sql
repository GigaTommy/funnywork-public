-- Dedicated iOS session: no management-page idle timeout, bounded 30-day lifetime.
-- Re-running does not overwrite an administrator's existing client policy.
lock table sys_client in share row exclusive mode;
insert into sys_client(id,client_id,client_key,client_secret,grant_type,device_type,active_timeout,timeout,status,del_flag,create_dept,create_by,create_time,update_by,update_time)
select (select coalesce(max(id),0)+1 from sys_client),'f93768fc8eb9821d7838d65e4ba03b3f','funnywork-ios','', 'password','ios',-1,2592000,'0','0',103,1,now(),1,now()
where not exists(select 1 from sys_client where client_id='f93768fc8eb9821d7838d65e4ba03b3f');
