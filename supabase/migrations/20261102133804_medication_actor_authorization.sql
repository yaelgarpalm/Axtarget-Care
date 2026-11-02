begin;

-- Reconcile the hosted actor column without deleting or rewriting historical records.
alter table public.medication_intakes add column if not exists actor_user_id uuid references auth.users(id);
create index if not exists medication_intakes_actor_idx on public.medication_intakes(actor_user_id);
revoke all on table public.medications, public.medication_intakes from anon;
revoke truncate, references, trigger on table public.medications, public.medication_intakes from authenticated;
grant select, insert, update, delete on table public.medications, public.medication_intakes to authenticated;

-- Permissive policies OR together: remove the legacy owner policy as well as the hosted variant.
drop policy if exists medication_intakes_insert_owner on public.medication_intakes;
drop policy if exists medication_intakes_insert_owner_or_linked on public.medication_intakes;
create policy medication_intakes_insert_owner on public.medication_intakes for insert to authenticated
with check (owner_user_id = (select auth.uid()) and (actor_user_id is null or actor_user_id = (select auth.uid())));

drop policy if exists medication_intakes_update_owner on public.medication_intakes;
drop policy if exists medication_intakes_update_owner_or_linked on public.medication_intakes;
create policy medication_intakes_update_owner_or_linked on public.medication_intakes for update to authenticated
using (
    owner_user_id = (select auth.uid()) or (
        public.is_linked_caregiver(owner_user_id) and exists (
            select 1 from public.medications m where m.id = medication_intakes.medication_id
            and m.owner_user_id = medication_intakes.owner_user_id and m.share_with_caregiver
            and m.is_active and not m.is_deleted
        )
    )
)
with check (
    owner_user_id = (select auth.uid()) or (
        actor_user_id = (select auth.uid()) and status in ('taken', 'skipped', 'snoozed')
        and public.is_linked_caregiver(owner_user_id) and exists (
            select 1 from public.medications m where m.id = medication_intakes.medication_id
            and m.owner_user_id = medication_intakes.owner_user_id and m.share_with_caregiver
            and m.is_active and not m.is_deleted
        )
    )
);

-- RLS restricts rows, not changed columns. Preserve schedule identity and truthful attribution.
create or replace function public.guard_medication_intake_action()
returns trigger language plpgsql security invoker set search_path = '' as $$
declare
    v_actor uuid := auth.uid();
begin
    if v_actor is null then return new; end if; -- administrative migrations; RLS still rejects unauthenticated clients
    if tg_op = 'INSERT' then
        if new.actor_user_id is not null and new.actor_user_id <> v_actor then
            raise exception 'intake_actor_mismatch' using errcode = '42501';
        end if;
        return new;
    end if;
    if old.owner_user_id = v_actor then
        -- An owner syncing an unchanged caregiver action preserves its original actor.
        if new.actor_user_id is distinct from old.actor_user_id and new.actor_user_id is distinct from v_actor
           and not (new.actor_user_id is null and new.status in ('pending', 'overdue', 'cancelled')) then
            raise exception 'intake_actor_mismatch' using errcode = '42501';
        end if;
        if new.status in ('taken', 'skipped', 'snoozed')
           and (new.status, new.action_at) is distinct from (old.status, old.action_at)
           and new.actor_user_id is distinct from v_actor then
            raise exception 'intake_actor_mismatch' using errcode = '42501';
        end if;
        return new;
    end if;
    if (new.id, new.owner_user_id, new.medication_id, new.schedule_instance_id, new.scheduled_at, new.created_at)
       is distinct from (old.id, old.owner_user_id, old.medication_id, old.schedule_instance_id, old.scheduled_at, old.created_at) then
        raise exception 'caregiver_cannot_change_schedule' using errcode = '42501';
    end if;
    if new.actor_user_id is distinct from v_actor or new.action_at is null or new.status not in ('taken', 'skipped', 'snoozed') then
        raise exception 'intake_actor_or_action_invalid' using errcode = '42501';
    end if;
    -- Exact retry after a lost response is safe; another actor's completed action is not replaced.
    if (new.status, new.action_at, new.actor_user_id, new.snoozed_until, new.snooze_count)
       is not distinct from (old.status, old.action_at, old.actor_user_id, old.snoozed_until, old.snooze_count) then
        return new;
    end if;
    if old.status not in ('pending', 'snoozed', 'overdue') then
        raise exception 'intake_already_completed' using errcode = '42501';
    end if;
    if new.status = 'snoozed' then
        if old.snooze_count <> 0 or new.snooze_count <> 1 or new.snoozed_until is null then
            raise exception 'intake_snooze_invalid' using errcode = '42501';
        end if;
    elsif (new.snooze_count, new.snoozed_until) is distinct from (old.snooze_count, old.snoozed_until) then
        raise exception 'intake_snooze_invalid' using errcode = '42501';
    end if;
    return new;
end;
$$;
revoke all on function public.guard_medication_intake_action() from public, anon, authenticated;
drop trigger if exists medication_intakes_guard_action on public.medication_intakes;
create trigger medication_intakes_guard_action before insert or update on public.medication_intakes
for each row execute function public.guard_medication_intake_action();

commit;
