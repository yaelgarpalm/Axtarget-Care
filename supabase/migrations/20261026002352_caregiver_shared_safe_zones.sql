-- Linked caregivers may manage only safe-zone records explicitly shared with
-- them. Journal and routine records remain owner writable.
drop policy if exists care_records_insert_owner on public.care_records;
drop policy if exists care_records_update_owner on public.care_records;
drop policy if exists care_records_delete_owner on public.care_records;

create policy care_records_insert_owner_or_linked_zone on public.care_records
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()) or
        (record_kind = 'zone' and public.is_linked_caregiver(owner_user_id)));

create policy care_records_update_owner_or_linked_zone on public.care_records
    for update to authenticated
    using (owner_user_id = (select auth.uid()) or
        (record_kind = 'zone' and shared_with_caregiver and public.is_linked_caregiver(owner_user_id)))
    with check (owner_user_id = (select auth.uid()) or
        (record_kind = 'zone' and public.is_linked_caregiver(owner_user_id)));

create policy care_records_delete_owner_or_linked_zone on public.care_records
    for delete to authenticated
    using (owner_user_id = (select auth.uid()) or
        (record_kind = 'zone' and shared_with_caregiver and public.is_linked_caregiver(owner_user_id)));
