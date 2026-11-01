-- The protected person and the caregiver can inspect the other account's registered
-- device inventory only while their caregiver_links row remains active.
drop policy if exists devices_select_owner_or_linked on public.devices;
create policy devices_select_active_link_members
on public.devices
for select
to authenticated
using (
  user_id = (select auth.uid())
  or exists (
    select 1
    from public.caregiver_links as link
    where link.status = 'active'
      and (
        (link.owner_user_id = devices.user_id and link.caregiver_user_id = (select auth.uid()))
        or (link.caregiver_user_id = devices.user_id and link.owner_user_id = (select auth.uid()))
      )
  )
);

drop policy if exists profiles_select_owner_or_linked on public.profiles;
create policy profiles_select_active_link_members
on public.profiles
for select
to authenticated
using (
  user_id = (select auth.uid())
  or exists (
    select 1
    from public.caregiver_links as link
    where link.status = 'active'
      and (
        (link.owner_user_id = profiles.user_id and link.caregiver_user_id = (select auth.uid()))
        or (link.caregiver_user_id = profiles.user_id and link.owner_user_id = (select auth.uid()))
      )
  )
);
