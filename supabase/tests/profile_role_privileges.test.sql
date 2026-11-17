begin;
select plan(8);

select ok(has_table_privilege('authenticated', 'public.profiles', 'SELECT'), 'authenticated can still read permitted profiles');
select ok(has_column_privilege('authenticated', 'public.profiles', 'role', 'SELECT'), 'legacy role remains readable for display only');
select ok(has_column_privilege('authenticated', 'public.profiles', 'user_id', 'INSERT'), 'authenticated can create its own profile row');
select ok(has_column_privilege('authenticated', 'public.profiles', 'full_name', 'INSERT'), 'authenticated can create profile name');
select ok(has_column_privilege('authenticated', 'public.profiles', 'preferences', 'INSERT'), 'authenticated can create profile preferences');
select ok(has_column_privilege('authenticated', 'public.profiles', 'full_name', 'UPDATE'), 'authenticated can update profile name');
select ok(has_column_privilege('authenticated', 'public.profiles', 'preferences', 'UPDATE'), 'authenticated can update profile preferences');
select ok(not has_column_privilege('authenticated', 'public.profiles', 'role', 'INSERT')
      and not has_column_privilege('authenticated', 'public.profiles', 'role', 'UPDATE'),
      'authenticated cannot assign or change the legacy role');

select * from finish();
rollback;
