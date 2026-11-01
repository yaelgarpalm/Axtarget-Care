-- profiles rows were only ever created by the signup trigger (handle_new_user).
-- If rows were deleted (e.g. a manual data cleanup), clients could not recreate
-- them: no INSERT policy or grant existed and the client's PATCH silently
-- updated 0 rows, leaving the account without name/role on the server.
create policy profiles_insert_owner on public.profiles
    for insert to authenticated
    with check (user_id = (select auth.uid()));

grant insert (user_id, full_name, role, preferences) on public.profiles to authenticated;

-- Recreate missing profiles for auth users that already exist.
insert into public.profiles (user_id, full_name)
select u.id, coalesce(nullif(trim(u.raw_user_meta_data ->> 'full_name'), ''), 'Usuario')
from auth.users u
where not exists (
    select 1 from public.profiles p where p.user_id = u.id
)
on conflict (user_id) do nothing;
