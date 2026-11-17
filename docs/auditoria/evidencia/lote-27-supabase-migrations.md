# Lote 27 — Migraciones Supabase autorizadas

Fecha: 3 de noviembre de 2026, zona America/Mexico_City. Proyecto activo conectado; no se registra su ID en esta evidencia.

## Migraciones aplicadas

1. `20261102133804_medication_actor_authorization.sql` (ledger Supabase `medication_actor_authorization`): se eliminaron las políticas permisivas superpuestas de INSERT/UPDATE de tomas y se instaló `medication_intakes_guard_action`.
2. `20261103070000_profiles_role_read_only.sql` (`profiles_role_read_only`): se retiraron INSERT/UPDATE de `profiles.role` a `authenticated`; se preservaron los permisos necesarios para nombre y preferencias.
3. `20261102182134_phone_motion_device_ownership.sql` (`phone_motion_device_ownership`): se añadió FK compuesta `(user_id, device_id)` hacia el dispositivo propietario y se reforzaron los checks de INSERT/UPDATE.

Las llamadas de `apply_migration` devolvieron `success: true`. Las tres autorizaciones se obtuvieron por separado en la conversación. Los SQL no contienen `DELETE`, `TRUNCATE` ni actualizaciones de filas; la migración de movimiento detectó cero filas huérfanas antes de aplicarse y validó la nueva FK.

## Verificación remota posterior

Consulta SQL de solo lectura confirmó:

- `profiles.role`: SELECT permanece; INSERT y UPDATE para `authenticated` son `false`; permisos para `full_name` y `preferences` permanecen.
- `medication_intakes`: una política INSERT, una UPDATE y un trigger guard; sin políticas duplicadas.
- `phone_motion_status`: FK `phone_motion_status_device_owner_fk` validada; cero filas huérfanas; una política INSERT y una UPDATE.
- `supabase_list_migrations`: incluye las tres migraciones nuevas bajo el ledger del proyecto.
- Asesores de Supabase refrescados después de las migraciones: ya no muestran `multiple_permissive_policies` para tomas. Persisten `auth_leaked_password_protection` deshabilitado; las dos tablas de cámara con RLS sin políticas (sin grants a `anon` ni `authenticated`); funciones SECURITY DEFINER autenticadas intencionales; hallazgos de índices.

## Límites

No se hicieron escrituras de datos de usuario, pruebas con cuentas reales, alertas, SMS, llamadas ni inferencias NVIDIA. Las pruebas pgTAP documentadas se ejecutaron localmente. La configuración de protección de contraseñas filtradas no se puede cambiar con las herramientas Supabase disponibles en esta sesión. La función remota `medication-label` todavía no existe y su migración de cuota no se aplicó.
