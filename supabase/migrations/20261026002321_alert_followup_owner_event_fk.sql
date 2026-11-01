-- Ensure an authorized caregiver can record follow-up only for that linked
-- owner's fall event, even though foreign-key checks bypass row-level filters.
alter table public.alert_followups
    drop constraint if exists alert_followups_fall_event_id_fkey;

alter table public.alert_followups
    add constraint alert_followups_owner_event_fkey
    foreign key (owner_user_id, fall_event_id)
    references public.fall_events(user_id, id) on delete cascade;
