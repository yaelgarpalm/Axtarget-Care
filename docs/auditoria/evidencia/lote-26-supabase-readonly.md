# Lote 26 — Evidencia Supabase remota (solo lectura)

Fecha: 3 de noviembre de 2026, zona America/Mexico_City. La API registró las observaciones después de medianoche UTC.

## Consultas realizadas

- Listado de proyectos; se encontró un proyecto activo.
- Asesores de seguridad y rendimiento de Supabase.
- Ledger de migraciones y esquema detallado de tablas `public`.
- `has_column_privilege` y `has_table_privilege` para `profiles`.
- Políticas y triggers de `medication_intakes`.
- Grants directos y políticas RLS de `camera_device_invites` y `camera_device_links`.
- Definiciones de ocho funciones RPC marcadas `SECURITY DEFINER`.

## Hallazgos confirmados

- `authenticated`: SELECT de `profiles` permitido; INSERT y UPDATE de `role` permitidos; UPDATE de `full_name` y `preferences` también permitido. El remoto tiene una política de UPDATE propia, por lo que el privilege de columna es el límite que falta para el rol local.
- `medication_intakes`: se observan simultáneamente `medication_intakes_insert_owner` + `medication_intakes_insert_owner_or_linked`, y `medication_intakes_update_owner` + `medication_intakes_update_owner_or_linked`. El actor `actor_user_id` y la edición de horarios no tienen un trigger de protección instalado. La columna y su FK sí existen.
- Ledger remoto: la última migración registrada es `20261026004740` (`bidirectional_linked_device_visibility`). El checkout contiene migraciones posteriores, incluida la corrección de roles `20261103070000` y la de atribución de tomas `20261102133804`.
- Asesor de seguridad: tablas de cámara con RLS sin políticas. La consulta de grants encontró únicamente grants para `service_role`, no para `anon` ni `authenticated`; el acceso directo de clientes está denegado. Las funciones RPC observadas están expuestas intencionalmente y sus definiciones fijan `search_path` vacío, comprueban sesión y autorizan mediante vínculo, propiedad o invitación de un solo uso.
- Asesor de Auth: protección contra contraseñas filtradas deshabilitada.
- Asesor de rendimiento: cuatro FK sin índice, cuatro índices sin uso registrado y políticas permisivas duplicadas de `medication_intakes`.

## Límites

Todas las llamadas fueron de lectura. No se ejecutó SQL de escritura, no se aplicaron migraciones, no se cambió Auth y no se leyeron filas personales. No se enviaron alertas. Los resultados describen la configuración observada en esta consulta y no afirman que se haya corregido en remoto.
