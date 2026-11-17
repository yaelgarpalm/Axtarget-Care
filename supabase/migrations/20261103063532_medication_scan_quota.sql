begin;
create schema if not exists tzinda_private;
revoke all on schema tzinda_private from public, anon, authenticated;

create table if not exists tzinda_private.medication_scan_limits (
  scope text primary key,
  user_id uuid references auth.users(id) on delete cascade,
  hour_start timestamptz not null,
  hour_count integer not null default 0 check (hour_count >= 0),
  day_start timestamptz not null,
  day_count integer not null default 0 check (day_count >= 0)
);
alter table tzinda_private.medication_scan_limits enable row level security;
revoke all on tzinda_private.medication_scan_limits from public, anon, authenticated, service_role;

-- Called only by the authenticated Edge handler with its server credential.
-- Lock the global row first to serialize reservations across all Edge instances.
create or replace function public.reserve_medication_scan(p_user_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare
  v_hour timestamptz := date_trunc('hour', now(), 'UTC');
  v_day timestamptz := date_trunc('day', now(), 'UTC');
  v_global tzinda_private.medication_scan_limits%rowtype;
  v_user tzinda_private.medication_scan_limits%rowtype;
begin
  if p_user_id is null or not exists(select 1 from auth.users where id = p_user_id) then return false; end if;
  insert into tzinda_private.medication_scan_limits(scope, hour_start, day_start)
    values ('global', v_hour, v_day) on conflict do nothing;
  select * into v_global from tzinda_private.medication_scan_limits where scope = 'global' for update;
  insert into tzinda_private.medication_scan_limits(scope, user_id, hour_start, day_start)
    values (p_user_id::text, p_user_id, v_hour, v_day) on conflict do nothing;
  select * into v_user from tzinda_private.medication_scan_limits where scope = p_user_id::text for update;
  if v_global.day_start <> v_day then v_global.day_count := 0; end if;
  if v_user.day_start <> v_day then v_user.day_count := 0; end if;
  if v_user.hour_start <> v_hour then v_user.hour_count := 0; end if;
  if v_global.day_count >= 1000 or v_user.day_count >= 30 or v_user.hour_count >= 10 then return false; end if;
  update tzinda_private.medication_scan_limits set day_start = v_day, day_count = v_global.day_count + 1
    where scope = 'global';
  update tzinda_private.medication_scan_limits set hour_start = v_hour, hour_count = v_user.hour_count + 1,
    day_start = v_day, day_count = v_user.day_count + 1 where scope = p_user_id::text;
  return true;
end;
$$;
revoke all on function public.reserve_medication_scan(uuid) from public, anon, authenticated;
grant execute on function public.reserve_medication_scan(uuid) to service_role;
commit;
