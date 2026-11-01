-- Append-only GPS history uploaded from the phone's local outbox. Mirrors the
-- caregiver_locations ownership model: rows belong to the authenticated owner
-- and reference a device registered to that same owner (composite FK).
create table if not exists public.location_history (
    id uuid primary key,
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    device_id uuid not null,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_m double precision check (accuracy_m is null or accuracy_m between 0 and 100000),
    captured_at timestamptz not null,
    created_at timestamptz not null default now(),
    foreign key (owner_user_id, device_id) references public.devices (user_id, id) on delete cascade
);

create index if not exists location_history_owner_captured_idx
    on public.location_history (owner_user_id, captured_at desc);

alter table public.location_history enable row level security;
revoke all on public.location_history from anon, authenticated;
grant select, insert, delete on public.location_history to authenticated;

create policy location_history_select_owner_or_linked on public.location_history
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id, device_id));

create policy location_history_insert_owner on public.location_history
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()));

create policy location_history_delete_owner on public.location_history
    for delete to authenticated
    using (owner_user_id = (select auth.uid()));
