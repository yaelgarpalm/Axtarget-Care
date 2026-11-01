-- Linked caregivers author journal (bitácora) and routine records on behalf of
-- the person they support. Previously only 'zone' rows were caregiver-writable,
-- so journal/routine rows created on the caregiver phone could never sync.
drop policy if exists care_records_insert_owner_or_linked_zone on public.care_records;
drop policy if exists care_records_update_owner_or_linked_zone on public.care_records;
drop policy if exists care_records_delete_owner_or_linked_zone on public.care_records;

create policy care_records_insert_owner_or_linked on public.care_records
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()) or
        (record_kind in ('zone', 'journal', 'routine') and public.is_linked_caregiver(owner_user_id)));

-- UPDATE covers the client's upsert (on_conflict=id merge-duplicates) re-pushes.
create policy care_records_update_owner_or_linked on public.care_records
    for update to authenticated
    using (owner_user_id = (select auth.uid()) or
        ((record_kind = 'zone' and shared_with_caregiver or record_kind in ('journal', 'routine'))
            and public.is_linked_caregiver(owner_user_id)))
    with check (owner_user_id = (select auth.uid()) or
        (record_kind in ('zone', 'journal', 'routine') and public.is_linked_caregiver(owner_user_id)));

create policy care_records_delete_owner_or_linked on public.care_records
    for delete to authenticated
    using (owner_user_id = (select auth.uid()) or
        ((record_kind = 'zone' and shared_with_caregiver or record_kind in ('journal', 'routine'))
            and public.is_linked_caregiver(owner_user_id)));
