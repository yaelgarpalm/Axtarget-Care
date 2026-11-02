begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select no_plan();

-- Entire fixture rolls back; this suite is invoked with --local only.
insert into auth.users(id, email) values
('10000000-0000-0000-0000-000000000001', 'owner-a@example.invalid'),
('10000000-0000-0000-0000-000000000002', 'owner-b@example.invalid'),
('10000000-0000-0000-0000-000000000003', 'caregiver@example.invalid'),
('10000000-0000-0000-0000-000000000004', 'other-caregiver@example.invalid');
insert into public.caregiver_links(caregiver_user_id, owner_user_id) values
('10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001'),
('10000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000001');
insert into public.medications(id, owner_user_id, name, prescribed_dose, frequency, schedule_times, start_date, share_with_caregiver) values
('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Shared','As prescribed','Diaria','["08:00"]',current_date,true),
('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','Private','As prescribed','Diaria','["08:00"]',current_date,false),
('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000002','Other owner','As prescribed','Diaria','["08:00"]',current_date,true);
insert into public.medication_intakes(id,medication_id,owner_user_id,schedule_instance_id,scheduled_at,status) values
('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001',now(),'pending'),
('30000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000002',now(),'pending'),
('30000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000002','40000000-0000-0000-0000-000000000003',now(),'pending'),
('30000000-0000-0000-0000-000000000004','20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000004',now(),'pending');

set local role anon;
select throws_ok('select * from public.medications', '42501', null, 'anonymous cannot read medication data');
select throws_ok('select * from public.medication_intakes', '42501', null, 'anonymous cannot read intakes');
select throws_ok('truncate public.medication_intakes', '42501', null, 'anonymous cannot truncate');
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000003';
select is((select count(*) from medications),1::bigint,'caregiver sees only explicitly shared medication of linked owner');
select is((select count(*) from medication_intakes),2::bigint,'caregiver cannot see private or unrelated intakes');
select results_eq($$update medications set name='Unauthorized' returning id$$,array[]::uuid[],'caregiver cannot edit prescriptions');
select results_eq($$delete from medications returning id$$,array[]::uuid[],'caregiver cannot delete prescriptions');
select results_eq($$delete from medication_intakes returning id$$,array[]::uuid[],'caregiver cannot delete intakes');
select throws_ok($$insert into medication_intakes(medication_id,owner_user_id,schedule_instance_id,scheduled_at,status,actor_user_id)
 values('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',gen_random_uuid(),now(),'taken',auth.uid())$$,
 '42501',null,'caregiver cannot invent a scheduled intake');
select throws_ok($$update medication_intakes set scheduled_at=now()+interval '1 day',status='taken',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'42501',null,'caregiver cannot move scheduled time');
select throws_ok($$update medication_intakes set medication_id='20000000-0000-0000-0000-000000000002',status='taken',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'42501',null,'caregiver cannot reassign medication');
select throws_ok($$update medication_intakes set status='taken',action_at=now(),actor_user_id='10000000-0000-0000-0000-000000000001'
 where id='30000000-0000-0000-0000-000000000001'$$,'42501',null,'caregiver cannot impersonate the owner');
select lives_ok($$update medication_intakes set status='taken',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'caregiver can mark a shared pending intake taken');
select is((select actor_user_id from medication_intakes where id='30000000-0000-0000-0000-000000000001'),auth.uid(),'actor is preserved');
select lives_ok($$update medication_intakes set status='taken',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'exact retry is idempotent');
select throws_ok($$update medication_intakes set status='skipped',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'42501',null,'completed intake cannot be replaced by another action');
select lives_ok($$update medication_intakes set status='snoozed',action_at=now(),actor_user_id=auth.uid(),snooze_count=1,snoozed_until=now()+interval '10 minutes'
 where id='30000000-0000-0000-0000-000000000004'$$,'caregiver can snooze once');
select throws_ok($$update medication_intakes set snoozed_until=now()+interval '20 minutes'
 where id='30000000-0000-0000-0000-000000000004'$$,'42501',null,'second snooze is rejected');
select throws_ok('truncate medication_intakes','42501',null,'authenticated cannot truncate');

set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000004';
select throws_ok($$update medication_intakes set status='taken',action_at=now(),actor_user_id=auth.uid()
 where id='30000000-0000-0000-0000-000000000001'$$,'42501',null,'second caregiver cannot claim a completed action');

set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select is((select count(*) from medications),2::bigint,'owner sees private and shared prescriptions');
select throws_ok($$update medication_intakes set status='taken',action_at=now(),actor_user_id='10000000-0000-0000-0000-000000000003'
 where id='30000000-0000-0000-0000-000000000002'$$,'42501',null,'owner cannot attribute a new action to caregiver');
select lives_ok($$update medication_intakes set updated_at=now() where id='30000000-0000-0000-0000-000000000001'$$,'owner may preserve unchanged caregiver attribution');
select lives_ok($$update medication_intakes set status='overdue',actor_user_id=null where id='30000000-0000-0000-0000-000000000004'$$,'owner automatic overdue transition has no human actor');
select lives_ok($$update medications set name='Owner edit' where id='20000000-0000-0000-0000-000000000001'$$,'owner can edit own medication');
select lives_ok($$update medications set share_with_caregiver=false where id='20000000-0000-0000-0000-000000000001'$$,'owner can revoke medication sharing');
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000003';
select is((select count(*) from medications),0::bigint,'unshared medication disappears from caregiver');
select is((select count(*) from medication_intakes),0::bigint,'unshared intakes disappear from caregiver');
select results_eq($$update medication_intakes set status='taken',action_at=now(),actor_user_id=auth.uid() returning id$$,array[]::uuid[],'revoked sharing updates zero rows');
reset role;
update medications set share_with_caregiver=true where id='20000000-0000-0000-0000-000000000001';
update caregiver_links set status='revoked' where caregiver_user_id='10000000-0000-0000-0000-000000000003';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000003';
select is((select count(*) from medications),0::bigint,'revoked link cannot read previously shared medication');
select results_eq($$update medication_intakes set status='taken',action_at=now(),actor_user_id=auth.uid() returning id$$,array[]::uuid[],'revoked link cannot record an action');
select * from finish();
rollback;
