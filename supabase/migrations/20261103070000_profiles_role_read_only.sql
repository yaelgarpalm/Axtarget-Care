begin;

-- The phone's selected mode is local presentation state, never an account claim.
-- Clients may edit profile content, but cannot write the legacy role column.
revoke insert, update on table public.profiles from authenticated;
-- The initial migration granted column-level role privileges; table-level REVOKE does not remove them.
revoke insert (role), update (role) on table public.profiles from authenticated;
grant insert (user_id, full_name, preferences) on table public.profiles to authenticated;
grant update (full_name, preferences) on table public.profiles to authenticated;

commit;
