begin;

-- Supporting indexes for foreign-key lookups flagged by the hosted advisor.
-- Keep nullable per-actor/quota rows out of their indexes.
create index if not exists alert_followups_actor_user_id_idx
    on public.alert_followups (actor_user_id)
    where actor_user_id is not null;

create index if not exists location_history_owner_device_idx
    on public.location_history (owner_user_id, device_id);

create index if not exists medication_intakes_medication_owner_idx
    on public.medication_intakes (medication_id, owner_user_id);

create index if not exists phone_motion_status_owner_device_idx
    on public.phone_motion_status (user_id, device_id);

create index if not exists medication_scan_limits_user_id_idx
    on tzinda_private.medication_scan_limits (user_id)
    where user_id is not null;

commit;
