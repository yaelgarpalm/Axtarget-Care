# Lote 33 — Revalidación Supabase remota

Fecha: 9 de noviembre de 2026, America/Mexico_City.

## Cambio autorizado

Después de la autorización explícita del usuario se aplicó `20261109040215_supporting_foreign_key_indexes.sql`. Es una migración aditiva: crea índices para claves foráneas y no borra ni reescribe filas. El ledger remoto registra la versión `20261109040215`.

## Comprobaciones de solo lectura

- Se consultó `pg_indexes` para los cinco índices y sus tablas/columnas:
  - `alert_followups_actor_user_id_idx` en `actor_user_id` (parcial, no nulos).
  - `location_history_owner_device_idx` en `(owner_user_id, device_id)`.
  - `medication_intakes_medication_owner_idx` en `(medication_id, owner_user_id)`.
  - `phone_motion_status_owner_device_idx` en `(user_id, device_id)`.
  - `medication_scan_limits_user_id_idx` en `user_id` (parcial, no nulos).
- El asesor de rendimiento ya no reporta `unindexed_foreign_keys`. Ahora reporta 24 índices sin uso registrado, incluyendo los recién creados; no se borraron porque respaldan las rutas de cascada/validación de FK.
- El ledger conserva las migraciones remotas autorizadas para atribución de tomas, rol de perfil, propiedad de `phone_motion_status` y cuota de lectura de medicamentos; la función Edge `medication-label` está activa en versión 1 con `verify_jwt=true`.
- El asesor de seguridad aún informa ocho funciones `SECURITY DEFINER`, las tablas de cámara con RLS y sin políticas y la tabla privada de cuotas sin políticas. La revisión de metadatos confirmó que no tienen grants directos para `anon`/`authenticated`; las rutas previstas son RPC/funciones con controles de identidad, propiedad y `search_path` vacío.
- La protección de contraseñas filtradas permanece deshabilitada. No existe una herramienta disponible en la sesión para modificar ese ajuste de Auth.
- La presencia de `NVIDIA_API_KEY` no puede inspeccionarse con el conector. No se ejecutó inferencia y no se compartió ningún valor secreto.

## Límites

No se leyeron filas personales ni se hicieron writes de negocio. La única escritura remota fue la migración de índices autorizada. No se enviaron alertas, SMS ni llamadas. Las pruebas pgTAP y de aislamiento se ejecutaron en la base Supabase local, con fixtures en transacciones revertidas; no prueban aceptación entre cuentas físicas.
