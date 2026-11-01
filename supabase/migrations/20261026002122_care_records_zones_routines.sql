-- Shared care journal, safe zones and routines. Records remain owner-controlled;
-- caregivers can read only rows explicitly shared by an active linked owner.
create table if not exists public.care_records (
    id uuid primary key,
    owner_user_id uuid not null references auth.users(id) on delete cascade,
    record_kind text not null check (record_kind in ('journal', 'zone', 'routine')),
    record jsonb not null check (jsonb_typeof(record) = 'object'),
    shared_with_caregiver boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists care_records_owner_kind_updated_idx
    on public.care_records(owner_user_id, record_kind, updated_at desc);

alter table public.care_records enable row level security;
revoke all on public.care_records from anon, authenticated;
grant select, insert, update, delete on public.care_records to authenticated;

create policy care_records_select_owner_or_shared_link on public.care_records
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or
        (shared_with_caregiver and public.is_linked_caregiver(owner_user_id)));

create policy care_records_insert_owner on public.care_records
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()));

create policy care_records_update_owner on public.care_records
    for update to authenticated
    using (owner_user_id = (select auth.uid()))
    with check (owner_user_id = (select auth.uid()));

create policy care_records_delete_owner on public.care_records
    for delete to authenticated
    using (owner_user_id = (select auth.uid()));
