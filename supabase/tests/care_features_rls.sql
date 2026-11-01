-- Run only on an empty/local Supabase database. Fixtures roll back at the end.
begin;
insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
values
  ('a1000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'care-owner@example.invalid', '', now(), now(), now()),
  ('a2000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'care-link@example.invalid', '', now(), now(), now()),
  ('a3000000-0000-4000-8000-000000000003', 'authenticated', 'authenticated', 'care-outside@example.invalid', '', now(), now(), now());
insert into public.caregiver_links(caregiver_user_id, owner_user_id, device_id, status)
values ('a2000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000001', null, 'active');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a1000000-0000-4000-8000-000000000001","role":"authenticated"}', true);
insert into public.fall_events(id, user_id, occurred_at, source, classification, confirmation_status)
values ('a4000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000001', now(), 'manual', 'possible_fall', 'pending');
insert into public.care_records(id, owner_user_id, record_kind, record, shared_with_caregiver)
values
 ('a5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'journal', '{"description":"shared"}', true),
 ('a6000000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000001', 'journal', '{"description":"private"}', false),
 ('a8000000-0000-4000-8000-000000000008', 'a1000000-0000-4000-8000-000000000001', 'zone', '{"name":"shared zone"}', true);

select set_config('request.jwt.claims', '{"sub":"a2000000-0000-4000-8000-000000000002","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.care_records) <> 2 then raise exception 'caregiver must read only explicitly shared care records'; end if;
  insert into public.care_records(id, owner_user_id, record_kind, record, shared_with_caregiver)
  values ('a9000000-0000-4000-8000-000000000009', 'a1000000-0000-4000-8000-000000000001', 'zone', '{"name":"caregiver zone"}', true);
  update public.care_records set record = '{"name":"renamed zone"}'
  where id = 'a8000000-0000-4000-8000-000000000008';
  insert into public.alert_followups(id, owner_user_id, fall_event_id, actor_user_id, status)
  values ('a7000000-0000-4000-8000-000000000007', 'a1000000-0000-4000-8000-000000000001',
    'a4000000-0000-4000-8000-000000000004', 'a2000000-0000-4000-8000-000000000002', 'attended');
end $$;

select set_config('request.jwt.claims', '{"sub":"a3000000-0000-4000-8000-000000000003","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.care_records) <> 0 then raise exception 'unlinked user read another account care record'; end if;
  if (select count(*) from public.alert_followups) <> 0 then raise exception 'unlinked user read another account alert follow-up'; end if;
end $$;
rollback;
