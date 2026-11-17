# Matriz de perfiles y permisos

Esta matriz separa identidad de cuenta, modo local del teléfono, vínculo, dispositivo y datos autorizados. `auth.uid()` identifica la cuenta; `AppStore.role()` selecciona la experiencia de este teléfono y no concede acceso remoto. Las políticas de Supabase deben volver a comprobar cada acceso sin confiar en ese modo local.

| Área/dato | Persona protegida (propietaria) | Cuidador/a con vínculo activo | Teléfono cámara vinculado | Control aplicable |
|---|---|---|---|---|
| Perfil | Lee y edita su nombre/preferencias | Lee nombre y perfil compartido | Sin gestión de perfil | RLS owner/linked; migración `20261103070000` quita escritura del rol legado |
| Contactos y alertas | Gestiona contactos, crea eventos y alertas propios | Lee eventos y registra seguimiento permitido | La app envía detecciones al dueño mediante RPC de cámara | RLS por `auth.uid()`; RPC valida vínculo de cámara. El servidor autoriza por cuenta y vínculo, no por el modo local del teléfono |
| Mediciones | Lee/escribe propias | Solo lectura vinculada | Sin acceso directo | RLS propietario/cuidador activo; dispositivo debe pertenecer al dueño |
| Medicamentos | Crea, edita y elimina prescripciones propias; decide compartir | Lee solo medicamentos compartidos activos y registra tomas permitidas | Sin acceso | RLS `share_with_caregiver`, vínculo y trigger de atribución del actor |
| Cuidados, bitácora y rutinas | Controla sus registros | Lee datos compartidos; cambios permitidos por tipo de registro | Sin acceso | `CareFeatureStore`, propietario/actor y políticas de `care_records` |
| Zonas seguras | Crea y administra las propias | Puede gestionar zonas que el dueño comparte | Sin acceso | Política RLS de zona compartida y validación local; GPS del cuidador no evalúa zonas ajenas |
| Ubicación | Publica cuando otorga consentimiento/monitoreo | Lee ubicación compartida mientras el vínculo siga activo | Sin acceso | Políticas separadas para historial y ubicación del cuidador |
| Dispositivos y vínculos | Registra y administra sus equipos e invitaciones | Consulta equipos vinculados; no empareja pulsera ajena | Acepta vínculo de cámara por RPC y solo reporta su evento | RPC valida actor y vínculo; FKs incluyen propietario |
| Preferencias del teléfono | Solo el teléfono local | Solo el teléfono local | Solo el teléfono local | Almacenamiento aislado por cuenta; no es autorización de servidor |

## Evidencia y límites

- Android deriva la navegación de `AppStore.role()` y restringe pantallas/acciones por perfil. `CaregiverAccess` captura cuenta, actor, vínculo, propietario y generación para proteger cachés y callbacks.
- Migraciones locales: `20261021090000_initial_tzinda_backend.sql`, las migraciones de dispositivos, medicamentos, cuidados, ubicaciones y cámaras, y `20261103070000_profiles_role_read_only.sql`. Las funciones de cámara solo exponen operaciones de emparejamiento/envío declaradas; no se otorga acceso directo a sus tablas.
- `ProfileSyncPolicy.payload` serializa nombre y preferencias sin `role`; prueba unitaria e instrumentada validan esa forma. La nueva prueba pgTAP comprueba privilegios de columna, incluso la revocación del grant por columna concedido en la migración inicial.
- Esto describe el esquema del repositorio. El proyecto remoto ha mostrado divergencias y **no se ha aplicado** la migración de privilegios; no se afirma que sus grants actuales coincidan. La verificación completa RLS por actor requiere un Supabase de pruebas sincronizado y cuentas aisladas.
