# Lectura de medicamentos en la nube

La lectura remota requiere una sesión autenticada de Supabase y la función Edge `medication-label`. El APK no contiene la clave NVIDIA ni decide el modelo o las instrucciones del proveedor. La función valida la sesión consultando Auth, limita la imagen a JPEG de 3 MiB, reserva una cuota en PostgreSQL antes de llamar al proveedor y devuelve solo cinco campos de texto acotados.

## Preparar un proyecto Supabase

1. Revisa primero el historial de migraciones y el esquema del proyecto de destino. El esquema remoto de este proyecto ya tenía divergencias respecto del historial local; no intentes aplicar todas las migraciones desde cero sobre el remoto.
2. Aplica de forma coordinada `supabase/migrations/20261103063532_medication_scan_quota.sql` después de revisar la reserva `public.reserve_medication_scan(uuid)`. La migración crea una tabla privada sin acceso Data API y concede ejecutar la función solo a `service_role`. No migra ni borra datos de negocio.
3. Configura `NVIDIA_API_KEY` como secreto Edge. `NVIDIA_VISION_MODEL` es opcional; por defecto usa `nvidia/nemotron-3.5-super-vl-120b-a12b`. La función también necesita los secretos Supabase de ejecución local/remota (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`). Nunca agregues estas claves a `.env.local` que consume Gradle ni al APK.
4. Despliega solo `medication-label` con la verificación JWT del gateway activada. El handler vuelve a validar el token mediante `/auth/v1/user`; no confía en un identificador enviado por el móvil. Después de activar el despliegue, confirma una lectura con una cuenta de prueba y una imagen no sensible.
5. Retira las versiones anteriores del APK y rota la clave NVIDIA anterior si alguna vez se incluyó en un APK distribuido. Una clave empaquetada puede extraerse incluso si el APK nunca llegó a publicarse en una tienda.

La cuota persistente permite hasta 10 solicitudes por usuario y hora, 30 por usuario y día y 1.000 globales por día UTC. La reserva global serializa las solicitudes entre instancias Edge. Una llamada que supera el límite recibe HTTP 429. Fallas en Auth, PostgreSQL o NVIDIA no se registran como una respuesta válida y la app vuelve al lector local.

## Verificación local realizada

`node --test supabase/functions/medication-label/handler.test.ts`: 15 casos aprobados, incluyendo token inválido, cuerpo transmitido por partes demasiado grande, JPEG inválido, campos de control añadidos, cuotas, respuesta excedida, redirección y tiempo de espera del proveedor. Supabase local ejecutó cuatro llamadas al runtime Edge: sin sesión y token falsificado rechazados; sesión local alcanzó validación JPEG; campo `model` del cliente rechazado. No hubo llamada NVIDIA.

`supabase test db --local supabase/tests/medication_authorization.test.sql supabase/tests/phone_motion_ownership.test.sql supabase/tests/medication_scan_quota.test.sql`: 66 pruebas pgTAP aprobadas. Una prueba concurrente local lanzó 20 reservas para el mismo usuario: 10 aceptadas, 10 rechazadas, contador persistido en 10. Gradle audit pasó 106 unitarias, compilación y APK; 70 instrumentadas aprobaron en `emulator-5580`. Revisa `docs/auditoria/evidencia/lote-13-*`.

Estas comprobaciones usaron usuarios ficticios locales y un valor NVIDIA de marcador. No acreditan el secreto configurado en el proyecto hospedado, la entrega del proveedor, una inferencia real, la política de consumo/coste de NVIDIA ni el flujo de galería/cámara en teléfonos físicos. En Lote 27 se aplicaron al remoto solo migraciones de seguridad de perfiles, tomas y propiedad de movimiento; `medication_scan_quota` y `medication-label` siguen pendientes de autorización/despliegue. Consulta [el registro remoto](auditoria/README.md#lote-28-disponibilidad-remota-de-lectura-de-etiquetas) y su [evidencia](auditoria/evidencia/lote-28-medication-label-remote.md).
