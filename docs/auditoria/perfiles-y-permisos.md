# Cuenta, modo del teléfono y permisos

Contrato del lote 3. La validación integral de permisos sigue abierta.

## Identidades distintas

| Concepto | Fuente | Efecto |
|---|---|---|
| Cuenta autenticada | Supabase Auth, `AuthSession.userId` | Identifica al actor ante RLS; nunca se obtiene del modo local |
| Rol histórico de cuenta | `profiles.role`; caché `account_role` | Metadato compatible con versiones anteriores. El cliente nuevo no lo modifica al cambiar el modo |
| Modo del teléfono | `AppStore.user_role` | Persona protegida, cuidador o cámara; navegación y funciones locales de esta instalación |
| Dispositivo | ID local y fila de `devices` | Identifica origen de telemetría; registro remoto no prueba conexión física |
| Vínculo | `caregiver_links` / RPC de cámara | Autoriza compartir dentro del alcance y estado del vínculo |
| Consentimiento | Campos de compartir por entidad y ubicación | Restringe datos compartidos; elegir un modo no sustituye consentimiento |

Cambiar este teléfono a cámara no publica `role=camera` ni cambia el modo de otro teléfono. Descargar el perfil no cambia `user_role`, incluso si el rol remoto es nulo. Un teléfono nuevo debe escoger su modo local. Las operaciones de perfil conservan los demás campos de `preferences` remotos y confirman la fila devuelta antes de reconocer el envío.

No hace falta migrar ni borrar roles remotos para esta separación: las políticas revisadas usan propietario, actor, vínculo y consentimiento, no `profiles.role`. Los clientes antiguos todavía pueden modificar ese metadato; no deben usarse para probar la independencia del cliente nuevo.

## Matriz de permisos por acción

| Acción | Persona sobre sus datos | Cuidador sobre persona vinculada | Cámara independiente | Estado de revisión |
|---|---|---|---|---|
| Editar nombre y perfil propio | Sí | Solo su propio perfil | Solo su propio perfil | Separación de modo implementada; otros campos locales pendientes |
| Cambiar modo del teléfono | Sí, local | Sí, local | Sí, local | No concede autorización remota |
| Leer alertas/mediciones | Propias | Dentro del vínculo autorizado | Eventos propios y vinculación por RPC | RLS y pruebas de actores pendientes |
| Editar medicamentos | Propietario | No | No en navegación de cámara | Guardas del repositorio probadas en lote 5; RLS pendiente |
| Confirmar tomas compartidas | Propietario | Actor vinculado y medicamento compartido | No | Textos y guardas locales reconciliados; siete pruebas nuevas. Validación RLS pendiente |
| Editar contactos de emergencia | Propietario | No los del protegido | Sin flujo de emergencia del protegido | Revisar cambio de cuenta y servicios |
| Zonas/bitácoras/rutinas | Propietario | Operaciones concedidas por RLS y consentimiento | No en navegación de cámara | Hay contradicciones de UI por corregir |
| Consultar ubicación | Propia | Con consentimiento y vínculo activo | Sin acceso general | Revisar cola y revocación |
| Administrar dispositivos ajenos | No | No | Solo operaciones explícitas de su vínculo | Verificar FKs y RPC |
| Emitir eventos de cámara | Cámara local del propietario | No por cambiar modo | RPC de cámara emparejada | Validación física y RLS pendiente |

La matriz distingue intención y controles observados; no acredita que todas las capas ya estén alineadas. En particular, el modo local no es una barrera de seguridad ante un cliente modificado.

## Pruebas del lote 3

- Política pura: edición local pendiente gana a reloj remoto adelantado; perfil local confirmado descarga nombre remoto más nuevo; fila remota ausente se recrea; perfil sin cambios no se publica por cambiar modo; payload de escritura no incluye `role` ni `user_role`.
- Instrumentación aislada: cámara local sobrevive a perfil remoto wearer y reapertura; rol remoto nulo conserva modo; rol remoto camera no selecciona modo en un teléfono nuevo; descarga/acuse atrasados conservan una edición local posterior.
- Pendiente: dos teléfonos autenticados contra Supabase de pruebas y escenarios de permisos negativos por actor. Las pruebas locales no acreditan ese recorrido completo.

## Siguiente prioridad: conservación de cuentas

El lote 4 sustituye el borrado de `bindLocalDataToUser` por archivos por cuenta y verifica A → B → A. Las instancias antiguas conservan su ámbito y las sesiones tienen comprobaciones de identidad. H05 sigue parcialmente abierto para revocación/cambio de persona vinculada y validación de monitoreo en curso. La prueba de arranque de MainActivity no sustituye todos los flujos de hardware y sesión.
