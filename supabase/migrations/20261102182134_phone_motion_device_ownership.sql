begin;
-- The device id alone does not prove ownership. Preserve any legacy rows for review;
-- NOT VALID still enforces the relationship for every new or changed row.
do $$
begin
    if not exists (select 1 from pg_constraint where conrelid='public.phone_motion_status'::regclass
        and conname='phone_motion_status_device_owner_fk') then
        alter table public.phone_motion_status add constraint phone_motion_status_device_owner_fk
            foreign key (user_id,device_id) references public.devices(user_id,id) on delete cascade not valid;
    end if;
    if not exists (
        select 1 from public.phone_motion_status s left join public.devices d
        on d.user_id=s.user_id and d.id=s.device_id where d.id is null
    ) then
        alter table public.phone_motion_status validate constraint phone_motion_status_device_owner_fk;
    else
        raise notice 'Legacy phone motion rows require ownership review; no rows were deleted.';
    end if;
end;
$$;
revoke all on table public.phone_motion_status from anon;
revoke delete, truncate, references, trigger on table public.phone_motion_status from authenticated;
grant select, insert, update on table public.phone_motion_status to authenticated;

drop policy if exists phone_motion_status_insert_owner on public.phone_motion_status;
create policy phone_motion_status_insert_owner on public.phone_motion_status for insert to authenticated
with check (user_id=(select auth.uid()) and exists (
    select 1 from public.devices d where d.id=phone_motion_status.device_id and d.user_id=(select auth.uid())
));
drop policy if exists phone_motion_status_update_owner on public.phone_motion_status;
create policy phone_motion_status_update_owner on public.phone_motion_status for update to authenticated
using (user_id=(select auth.uid()))
with check (user_id=(select auth.uid()) and exists (
    select 1 from public.devices d where d.id=phone_motion_status.device_id and d.user_id=(select auth.uid())
));
commit;
