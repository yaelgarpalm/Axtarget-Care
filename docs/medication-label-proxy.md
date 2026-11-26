# Lectura de medicamentos en la nube

La lectura remota requiere una sesión autenticada de Supabase y la función Edge `medication-label`. La función valida la sesión, acepta JPEG de hasta 3 MiB y reserva una cuota por imagen antes de llamar a NVIDIA. La lectura en el teléfono es la opción inicial. Para probar la nube, usa fotografías de ejemplo sin datos personales identificables.

- **Caja o frasco.** Super VL devuelve el nombre, la concentración y la presentación del medicamento visible.
- **Receta.** Nemotron 3.5 Parse lee el texto y el orden de la página. Super VL consulta esa transcripción y la imagen para separar los medicamentos, dosis, indicaciones y notas generales. El usuario revisa cada registro antes de guardarlo. Si Parse falla, Super VL puede leer directamente la imagen. [API de Nemotron 3.5 Parse](https://build.nvidia.com/nvidia/nemotron-3.5-parse/apireference)

El cliente envía `image_base64` y `document` (`label` o `prescription`). Los clientes anteriores que solo envían la imagen siguen recibiendo la respuesta de un medicamento. Una receta de varias páginas se escanea una hoja a la vez.

## Preparar un proyecto Supabase

1. Revisa primero el historial de migraciones y el esquema del proyecto de destino. El esquema remoto de este proyecto ya tenía divergencias respecto del historial local; no intentes aplicar todas las migraciones desde cero sobre el remoto.
2. Aplica de forma coordinada `supabase/migrations/20261103063532_medication_scan_quota.sql` después de revisar la reserva `public.reserve_medication_scan(uuid)`. La migración crea una tabla privada sin acceso Data API y concede ejecutar la función solo a `service_role`. No migra ni borra datos de negocio.
3. Configura `NVIDIA_API_KEY` como secreto Edge. `NVIDIA_VISION_MODEL` es opcional; por defecto usa `nvidia/nemotron-3.5-super-vl-120b-a12b`. La función también necesita los secretos Supabase de ejecución local/remota (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`). Nunca agregues estas claves a `.env.local` que consume Gradle ni al APK.
4. Despliega solo `medication-label` con la verificación JWT del gateway activada. El handler vuelve a validar el token mediante `/auth/v1/user`; no confía en un identificador enviado por el móvil. Después de activar el despliegue, confirma una lectura con una cuenta de prueba y una imagen no sensible.
5. Retira las versiones anteriores del APK y rota la clave NVIDIA anterior si alguna vez se incluyó en un APK distribuido. Una clave empaquetada puede extraerse incluso si el APK nunca llegó a publicarse en una tienda.

La cuota persistente permite hasta 10 solicitudes por usuario y hora, 30 por usuario y día y 1.000 globales por día UTC. La reserva global serializa las solicitudes entre instancias Edge. Una llamada que supera el límite recibe HTTP 429. Fallas en Auth, PostgreSQL o NVIDIA no se registran como una respuesta válida y la app vuelve al lector local.

## Verificar cambios

Ejecuta `node --test supabase/functions/medication-label/handler.test.ts` para verificar autenticación, cuotas, límites, respuestas de un envase y de una receta, y fallback ante errores de OCR. Las pruebas simulan las respuestas de NVIDIA. Para verificar la inferencia remota se necesita una cuenta de prueba, el secreto del proveedor y una imagen sin datos personales.

`supabase test db --local supabase/tests/medication_authorization.test.sql supabase/tests/phone_motion_ownership.test.sql supabase/tests/medication_scan_quota.test.sql`: 66 pruebas pgTAP aprobadas. Una prueba concurrente local lanzó 20 reservas para el mismo usuario: 10 aceptadas, 10 rechazadas, contador persistido en 10. Gradle audit pasó 106 unitarias, compilación y APK; 70 instrumentadas aprobaron en `emulator-5580`. Revisa `docs/auditoria/evidencia/lote-13-*`.

La auditoría anterior documenta el despliegue inicial de la cuota y la función. [Evidencia del despliegue](auditoria/evidencia/lote-28-medication-label-remote.md)
