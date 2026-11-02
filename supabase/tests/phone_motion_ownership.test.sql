begin;
create extension if not exists pgtap with schema extensions;
set local search_path=public,extensions;
select no_plan();
insert into auth.users(id,email) values
('50000000-0000-0000-0000-000000000001','motion-owner@example.invalid'),
('50000000-0000-0000-0000-000000000002','motion-other@example.invalid'),
('50000000-0000-0000-0000-000000000003','motion-caregiver@example.invalid');
insert into devices(id,user_id,logical_id,name,device_type) values
('60000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','audit-phone-a','Audit A','phone'),
('60000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000002','audit-phone-b','Audit B','phone');
insert into caregiver_links(caregiver_user_id,owner_user_id) values
('50000000-0000-0000-0000-000000000003','50000000-0000-0000-0000-000000000001');
set local role authenticated;
set local request.jwt.claim.sub='50000000-0000-0000-0000-000000000002';
select throws_ok($$insert into phone_motion_status(device_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at)
values('60000000-0000-0000-0000-000000000001',1,0.1,0.2,now())$$,'42501',null,'another account cannot occupy a known foreign device id');
set local request.jwt.claim.sub='50000000-0000-0000-0000-000000000001';
select lives_ok($$insert into phone_motion_status(device_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at)
values('60000000-0000-0000-0000-000000000001',1,0.1,0.2,now())$$,'owner can insert its phone summary');
select lives_ok($$update phone_motion_status set sample_count=2 where device_id='60000000-0000-0000-0000-000000000001'$$,'owner can update summary');
select throws_ok($$update phone_motion_status set device_id='60000000-0000-0000-0000-000000000002'$$,'42501',null,'owner cannot move summary to a foreign device');
select throws_ok($$update phone_motion_status set user_id='50000000-0000-0000-0000-000000000002'$$,'42501',null,'owner cannot reassign summary ownership');
set local request.jwt.claim.sub='50000000-0000-0000-0000-000000000002';
select is((select count(*) from phone_motion_status),0::bigint,'unlinked account cannot read summary');
select results_eq($$update phone_motion_status set sample_count=100 returning device_id$$,array[]::uuid[],'unlinked account cannot overwrite summary');
set local request.jwt.claim.sub='50000000-0000-0000-0000-000000000003';
select is((select count(*) from phone_motion_status),1::bigint,'linked caregiver can read summary');
select results_eq($$update phone_motion_status set sample_count=100 returning device_id$$,array[]::uuid[],'linked caregiver cannot overwrite summary');
select throws_ok($$insert into phone_motion_status(device_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at)
values('60000000-0000-0000-0000-000000000002',1,0.1,0.2,now())$$,'42501',null,'caregiver cannot create data under another device');
select throws_ok('delete from phone_motion_status','42501',null,'client cannot delete phone summaries');
select throws_ok('truncate phone_motion_status','42501',null,'client cannot truncate phone summaries');
reset role;
update caregiver_links set status='revoked' where caregiver_user_id='50000000-0000-0000-0000-000000000003';
set local role authenticated;
select is((select count(*) from phone_motion_status),0::bigint,'revoked caregiver loses read access');
reset role;
-- Structural constraint also protects privileged writes that bypass RLS.
select throws_ok($$insert into phone_motion_status(device_id,user_id,sample_count,average_dynamic_g,peak_dynamic_g,captured_at)
values('60000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000001',1,0.1,0.2,now())$$,'23503',null,'composite FK enforces actual device owner independently of RLS');
select ok((select convalidated from pg_constraint where conrelid='public.phone_motion_status'::regclass and conname='phone_motion_status_device_owner_fk'),'owner FK is validated on clean schema');
set local role anon;
select throws_ok('select * from phone_motion_status','42501',null,'anonymous cannot read phone summaries');
select * from finish();
rollback;
