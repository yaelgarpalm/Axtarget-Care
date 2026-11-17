begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select plan(5);

select ok(exists (
    select 1 from pg_indexes
    where schemaname = 'public' and tablename = 'alert_followups'
      and indexname = 'alert_followups_actor_user_id_idx'
      and indexdef like '%(actor_user_id)%'
), 'actor foreign key has a partial index');

select ok(exists (
    select 1 from pg_indexes
    where schemaname = 'public' and tablename = 'location_history'
      and indexname = 'location_history_owner_device_idx'
      and indexdef like '%(owner_user_id, device_id)%'
), 'owner/device foreign key has a covering index');

select ok(exists (
    select 1 from pg_indexes
    where schemaname = 'public' and tablename = 'medication_intakes'
      and indexname = 'medication_intakes_medication_owner_idx'
      and indexdef like '%(medication_id, owner_user_id)%'
), 'medication/owner foreign key has a covering index');

select ok(exists (
    select 1 from pg_indexes
    where schemaname = 'public' and tablename = 'phone_motion_status'
      and indexname = 'phone_motion_status_owner_device_idx'
      and indexdef like '%(user_id, device_id)%'
), 'phone owner/device foreign key has a covering index');

select ok(exists (
    select 1 from pg_indexes
    where schemaname = 'tzinda_private' and tablename = 'medication_scan_limits'
      and indexname = 'medication_scan_limits_user_id_idx'
      and indexdef like '%(user_id)%'
), 'per-user quota foreign key has a partial index');

select * from finish();
rollback;
