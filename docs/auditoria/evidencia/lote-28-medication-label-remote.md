# Lote 28 — Disponibilidad remota de medication-label

Fecha: 3 de noviembre de 2026, zona America/Mexico_City.

## Inspección

- `supabase_list_edge_functions`: el proyecto lista `delete-account` y `auth-email-hook`; no lista `medication-label`.
- `supabase_get_edge_function(medication-label)`: `Function not found`.
- SQL de solo lectura: `tzinda_private` no existe, `tzinda_private.medication_scan_limits` no existe y `public.reserve_medication_scan(uuid)` no existe.
- Código del cliente llama `/functions/v1/medication-label`; por tanto la lectura en nube no está disponible en el proyecto conectado y depende del fallback local.
- El repositorio sí contiene la función y `20261103063532_medication_scan_quota.sql`. La verificación local registrada incluye `node --test` 15/15, 17 assertions pgTAP de cuotas y 66 pruebas SQL de los tres archivos dirigidos.

## Estado

No se aplicó la migración de cuotas, no se desplegó la función y no se ejecutó una inferencia remota. La API Supabase disponible en esta sesión no ofrece administración de Edge secrets; el secreto `NVIDIA_API_KEY` debe configurarse desde Supabase Dashboard y nunca enviarse por chat ni incluirse en el APK. La función debe desplegarse con `verify_jwt=true` y después aceptarse con una cuenta e imagen de prueba no sensible.

## Revalidación remota — 9 de noviembre de 2026

La revisión posterior a la autorización confirmó que la migración de cuota está en el ledger remoto y `medication-label` está activa en versión 1 con `verify_jwt=true`. El código remoto coincide con el handler del repositorio. No se hizo inferencia ni se obtuvo acceso a la configuración de secretos: la presencia de `NVIDIA_API_KEY` permanece sin verificar y aún requiere revisión desde Dashboard. La tabla privada sigue sin grants a `anon`/`authenticated`; el acceso se realiza por el camino de servicio protegido.
