-- Reproduction against the disposable local database BEFORE the ownership migration.
-- Fake accounts only; all rows roll back. Do not run against the hosted project.
begin;
insert into auth.users(id,email) values
('50000000-0000-0000-0000-000000000001','motion-owner@example.invalid'),
('50000000-0000-0000-0000-000000000002','motion-other@example.invalid');
insert into public.devices(id,user_id,logical_id,name,device_type) values
('60000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','audit-phone','Audit phone','phone');
set local role authenticated;
set local request.jwt.claim.sub='50000000-0000-0000-0000-000000000002';
insert into public.phone_motion_status(device_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at)
values('60000000-0000-0000-0000-000000000001',1,0.1,0.2,now());
reset role;
select s.user_id <> d.user_id as foreign_device_write_accepted
from public.phone_motion_status s join public.devices d on d.id=s.device_id
where s.device_id='60000000-0000-0000-0000-000000000001';
rollback;
