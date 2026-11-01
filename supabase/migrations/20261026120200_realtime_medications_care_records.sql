-- Publish medication and care-record tables so linked caregivers receive
-- changes through Realtime (RLS still filters which rows are delivered).
-- Idempotent guards follow the pattern of 20261024200000_realtime_replication_guards.sql.
-- The to_regclass guards make this safe to run before/after the module
-- migrations that create each table; re-run it once those tables exist.
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
