-- ============================================================================
-- CONSOLIDADO: crea las tablas/políticas/publicaciones que faltan en el
-- proyecto Supabase remoto (medicamentos, rutinas/bitácoras, alert_followups,
-- location_history, realtime y profiles insert).
--
-- Es IDEMPOTENTE: puedes pegarlo completo en el SQL Editor y ejecutarlo.
-- Si un objeto ya existe, se reutiliza o se recrea su política sin error.
-- Orden = mismo orden que las migraciones por timestamp.
-- ============================================================================

begin;

-- ----------------------------------------------------------------------------
-- 1) 20261024103000_medications_module.sql
-- ----------------------------------------------------------------------------
create or replace function public.medication_schedule_times_valid(p_times jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $function$
declare
    v_count bigint;
    v_distinct_count bigint;
    v_invalid boolean;
begin
    if pg_catalog.jsonb_typeof(p_times) <> 'array' then return false; end if;
    v_count := pg_catalog.jsonb_array_length(p_times);
    if v_count < 1 or v_count > 12 then return false; end if;
    select pg_catalog.count(*), pg_catalog.count(distinct item.time_value),
           coalesce(pg_catalog.bool_or(item.time_value is null or item.time_value !~ '^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$'), true)
      into v_count, v_distinct_count, v_invalid
      from pg_catalog.jsonb_array_elements_text(p_times) as item(time_value);
    return not v_invalid and v_count = v_distinct_count;
end;
$function$;
revoke all on function public.medication_schedule_times_valid(jsonb) from public, anon;
grant execute on function public.medication_schedule_times_valid(jsonb) to authenticated;
grant execute on function public.is_linked_caregiver(uuid, uuid) to authenticated;

create table if not exists public.medications (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    name text not null check (char_length(btrim(name)) between 1 and 120),
    prescribed_dose text not null check (char_length(btrim(prescribed_dose)) between 1 and 120),
    presentation text not null default '' check (char_length(presentation) <= 80),
    frequency text not null check (frequency in ('Diaria', 'Cada 2 días', 'Cada 3 días', 'Semanal')),
    schedule_times jsonb not null check (
        public.medication_schedule_times_valid(schedule_times)
    ),
    start_date date not null,
    end_date date,
    instructions text not null default '' check (char_length(instructions) <= 500),
    is_active boolean not null default true,
    share_with_caregiver boolean not null default false,
    is_deleted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint medications_end_after_start check (end_date is null or end_date >= start_date),
    constraint medications_id_owner_unique unique (id, owner_user_id)
);
create index if not exists medications_owner_active_updated_idx
    on public.medications (owner_user_id, is_deleted, is_active, updated_at desc);

create table if not exists public.medication_intakes (
    id uuid primary key default gen_random_uuid(),
    medication_id uuid not null,
    owner_user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    schedule_instance_id uuid not null,
    scheduled_at timestamptz not null,
    action_at timestamptz,
    status text not null check (status in ('pending', 'taken', 'skipped', 'overdue', 'snoozed', 'cancelled')),
    snoozed_until timestamptz,
    snooze_count smallint not null default 0 check (snooze_count between 0 and 1),
    actor_user_id uuid references auth.users (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint medication_intakes_schedule_unique unique (owner_user_id, schedule_instance_id),
    constraint medication_intakes_medication_owner_fk foreign key (medication_id, owner_user_id)
        references public.medications (id, owner_user_id) on delete cascade,
    constraint medication_intakes_snooze_state check (
        (status = 'snoozed' and snoozed_until is not null and snooze_count = 1)
        or (status <> 'snoozed' and snooze_count in (0, 1))
    )
);
create index if not exists medication_intakes_owner_scheduled_idx
    on public.medication_intakes (owner_user_id, scheduled_at desc);
create index if not exists medication_intakes_medication_scheduled_idx
    on public.medication_intakes (medication_id, scheduled_at desc);
-- Para instalaciones donde medication_intakes ya existía sin la columna de autoría.
alter table public.medication_intakes
    add column if not exists actor_user_id uuid references auth.users (id);

alter table public.medications enable row level security;
alter table public.medication_intakes enable row level security;
grant select, insert, update, delete on public.medications to authenticated;
grant select, insert, update, delete on public.medication_intakes to authenticated;

drop policy if exists medications_select_owner_or_linked_caregiver on public.medications;
create policy medications_select_owner_or_linked_caregiver
    on public.medications for select to authenticated
    using (
        owner_user_id = (select auth.uid())
        or (share_with_caregiver and public.is_linked_caregiver(owner_user_id))
    );
drop policy if exists medications_insert_owner on public.medications;
create policy medications_insert_owner
    on public.medications for insert to authenticated
    with check (owner_user_id = (select auth.uid()));
drop policy if exists medications_update_owner on public.medications;
create policy medications_update_owner
    on public.medications for update to authenticated
    using (owner_user_id = (select auth.uid()))
    with check (owner_user_id = (select auth.uid()));
drop policy if exists medications_delete_owner on public.medications;
create policy medications_delete_owner
    on public.medications for delete to authenticated
    using (owner_user_id = (select auth.uid()));

drop policy if exists medication_intakes_select_owner_or_linked_caregiver on public.medication_intakes;
create policy medication_intakes_select_owner_or_linked_caregiver
    on public.medication_intakes for select to authenticated
    using (
        owner_user_id = (select auth.uid())
        or (
            public.is_linked_caregiver(owner_user_id)
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = medication_intakes.owner_user_id
                  and m.share_with_caregiver
            )
        )
    );
drop policy if exists medication_intakes_insert_owner on public.medication_intakes;
drop policy if exists medication_intakes_insert_owner_or_linked on public.medication_intakes;
create policy medication_intakes_insert_owner_or_linked
    on public.medication_intakes for insert to authenticated
    with check (
        (
            owner_user_id = (select auth.uid())
            and (actor_user_id is null or actor_user_id = (select auth.uid()))
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = (select auth.uid())
            )
        )
        or (
            public.is_linked_caregiver(owner_user_id)
            and actor_user_id = (select auth.uid())
            and status in ('taken', 'skipped', 'snoozed')
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = medication_intakes.owner_user_id
                  and m.share_with_caregiver
            )
        )
    );
drop policy if exists medication_intakes_update_owner on public.medication_intakes;
drop policy if exists medication_intakes_update_owner_or_linked on public.medication_intakes;
create policy medication_intakes_update_owner_or_linked
    on public.medication_intakes for update to authenticated
    using (
        owner_user_id = (select auth.uid())
        or (
            public.is_linked_caregiver(owner_user_id)
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = medication_intakes.owner_user_id
                  and m.share_with_caregiver
            )
        )
    )
    with check (
        owner_user_id = (select auth.uid())
        or (
            public.is_linked_caregiver(owner_user_id)
            and actor_user_id = (select auth.uid())
            and status in ('taken', 'skipped', 'snoozed')
            and exists (
                select 1 from public.medications m
                where m.id = medication_intakes.medication_id
                  and m.owner_user_id = medication_intakes.owner_user_id
                  and m.share_with_caregiver
            )
        )
    );
drop policy if exists medication_intakes_delete_owner on public.medication_intakes;
create policy medication_intakes_delete_owner
    on public.medication_intakes for delete to authenticated
    using (owner_user_id = (select auth.uid()));

-- ----------------------------------------------------------------------------
-- 2) 20261026002122_care_records_zones_routines.sql
--    + 20261026120000_caregiver_journal_routine_writes.sql (políticas finales)
-- ----------------------------------------------------------------------------
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

-- Limpiamos cualquier variante previa (base "owner" o "zone") antes de recrear.
drop policy if exists care_records_select_owner_or_shared_link on public.care_records;
drop policy if exists care_records_insert_owner on public.care_records;
drop policy if exists care_records_update_owner on public.care_records;
drop policy if exists care_records_delete_owner on public.care_records;
drop policy if exists care_records_insert_owner_or_linked_zone on public.care_records;
drop policy if exists care_records_update_owner_or_linked_zone on public.care_records;
drop policy if exists care_records_delete_owner_or_linked_zone on public.care_records;
drop policy if exists care_records_insert_owner_or_linked on public.care_records;
drop policy if exists care_records_update_owner_or_linked on public.care_records;
drop policy if exists care_records_delete_owner_or_linked on public.care_records;

create policy care_records_select_owner_or_shared_link on public.care_records
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or
        (shared_with_caregiver and public.is_linked_caregiver(owner_user_id)));

create policy care_records_insert_owner_or_linked on public.care_records
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()) or
        (record_kind in ('zone', 'journal', 'routine') and public.is_linked_caregiver(owner_user_id)));

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

-- ----------------------------------------------------------------------------
-- 3) 20261026002241_alert_followup_history.sql
--    + 20261026002321_alert_followup_owner_event_fk.sql
-- ----------------------------------------------------------------------------
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

drop policy if exists alert_followups_select_owner_or_linked on public.alert_followups;
create policy alert_followups_select_owner_or_linked on public.alert_followups
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id));

drop policy if exists alert_followups_insert_owner_or_linked_actor on public.alert_followups;
create policy alert_followups_insert_owner_or_linked_actor on public.alert_followups
    for insert to authenticated
    with check (actor_user_id = (select auth.uid()) and
        (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id)));

-- FK compuesta que obliga a que el follow-up sea de un fall_event del mismo owner.
alter table public.alert_followups
    drop constraint if exists alert_followups_fall_event_id_fkey;
alter table public.alert_followups
    drop constraint if exists alert_followups_owner_event_fkey;
alter table public.alert_followups
    add constraint alert_followups_owner_event_fkey
    foreign key (owner_user_id, fall_event_id)
    references public.fall_events(user_id, id) on delete cascade;

-- ----------------------------------------------------------------------------
-- 4) 20261026120100_location_history.sql
-- ----------------------------------------------------------------------------
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

drop policy if exists location_history_select_owner_or_linked on public.location_history;
create policy location_history_select_owner_or_linked on public.location_history
    for select to authenticated
    using (owner_user_id = (select auth.uid()) or public.is_linked_caregiver(owner_user_id, device_id));
drop policy if exists location_history_insert_owner on public.location_history;
create policy location_history_insert_owner on public.location_history
    for insert to authenticated
    with check (owner_user_id = (select auth.uid()));
drop policy if exists location_history_delete_owner on public.location_history;
create policy location_history_delete_owner on public.location_history
    for delete to authenticated
    using (owner_user_id = (select auth.uid()));

-- ----------------------------------------------------------------------------
-- 5) 20261026120200_realtime_medications_care_records.sql
--    (va DESPUÉS de crear las tablas para que los guards las vean)
-- ----------------------------------------------------------------------------
do $$
begin
    if to_regclass('public.medications') is not null and not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'medications'
    ) then
        alter publication supabase_realtime add table public.medications;
    end if;

    if to_regclass('public.medication_intakes') is not null and not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'medication_intakes'
    ) then
        alter publication supabase_realtime add table public.medication_intakes;
    end if;

    if to_regclass('public.care_records') is not null and not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'care_records'
    ) then
        alter publication supabase_realtime add table public.care_records;
    end if;
end;
$$;

-- ----------------------------------------------------------------------------
-- 6) 20261026130000_profiles_owner_insert_and_backfill.sql
-- ----------------------------------------------------------------------------
drop policy if exists profiles_insert_owner on public.profiles;
create policy profiles_insert_owner on public.profiles
    for insert to authenticated
    with check (user_id = (select auth.uid()));

grant insert (user_id, full_name, role, preferences) on public.profiles to authenticated;

insert into public.profiles (user_id, full_name)
select u.id, coalesce(nullif(trim(u.raw_user_meta_data ->> 'full_name'), ''), 'Usuario')
from auth.users u
where not exists (
    select 1 from public.profiles p where p.user_id = u.id
)
on conflict (user_id) do nothing;

commit;

-- ============================================================================
-- VERIFICACIÓN (ejecuta esto aparte después del commit):
-- todo debe aparecer en "existe = true" y en la publicación las 3 tablas.
-- ============================================================================
-- select t.name, (to_regclass('public.'||t.name) is not null) as existe
-- from (values ('medications'),('medication_intakes'),('care_records'),
--              ('alert_followups'),('location_history'),('profiles')) as t(name)
-- order by t.name;
--
-- select tablename from pg_publication_tables
-- where pubname='supabase_realtime' and schemaname='public'
--   and tablename in ('medications','medication_intakes','care_records')
-- order by tablename;
