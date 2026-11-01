-- Append-only caregiver/owner alert state history. Existing fall and alert rows
-- remain intact; this records actions and reasons without letting caregivers
-- rewrite the detection event itself.
create table if not exists public.alert_followups (
    id uuid primary key,
    owner_user_id uuid not null references auth.users(id) on delete cascade,
    fall_event_id uuid not null references public.fall_events(id) on delete cascade,
    actor_user_id uuid not null references auth.users(id),
    status text not null check (status in ('confirmed', 'attended', 'dismissed', 'closed')),
    reason text check (reason is null or char_length(reason) <= 500),
    created_at timestamptz not null default now()
);

create index if not exists alert_followups_owner_event_time_idx
    on public.alert_followups(owner_user_id, fall_event_id, created_at desc);

alter table public.alert_followups enable row level security;
revoke all on public.alert_followups from anon, authenticated;
grant select, insert on public.alert_followups to authenticated;

create policy alert_followups_select_owner_or_linked on public.alert_followups
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id));

create policy alert_followups_insert_owner_or_linked_actor on public.alert_followups
    for insert to authenticated
    with check (actor_user_id = (select auth.uid()) and
        (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id)));
