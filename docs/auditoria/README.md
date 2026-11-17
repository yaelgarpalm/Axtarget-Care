# Auditoría integral de TzindaApp

Inicio: 1 de noviembre de 2026 (America/Mexico_City). Rama: `codex/auditoria-integral-tzinda`.
Base: `0ec5a5ce1035487452d63b99a1cbb12592d1b5e5`. Correcciones de software **implementadas y verificadas**; aceptación física y ajustes de Dashboard pendientes.

El checklist aportado es una lista de hipótesis, no evidencia de ejecución. Se prioriza la solicitud actual de implementar sobre su antiguo prompt de auditoría sin cambios. No se trabaja en Tzinda Band V1.

## Reglas y evidencia

- `[x]`: corrección implementada con comprobación indicada; no equivale a aceptación en teléfonos.
- `[~]`: validación parcial o pendiente de emulador, dispositivos o servicios.
- `[!]`: riesgo confirmado en un servicio conectado; la corrección está preparada, pero aún requiere despliegue o ajuste remoto.
- `[ ]`: trabajo pendiente.
- No se han enviado SMS/llamadas ni alertas reales. Las migraciones remotas y el despliegue de `medication-label` fueron aplicados únicamente después de la autorización explícita del usuario y están descritos en Lotes 27–28 y 33. Las demás consultas remotas fueron de metadatos; no se leyeron filas personales.
- Matriz de perfiles/permisos y sus límites de verificación: [matriz-perfiles-permisos.md](matriz-perfiles-permisos.md).
- APK de pruebas aislado: `-PtzindaAudit=true`, paquete `com.guardian.tzindaapp.v2.audit`, sin configuración de Supabase/NVIDIA. No sustituye la app instalada del usuario.
- La ejecución Android más reciente es el Lote 33: `BUILD SUCCESSFUL`, 119 pruebas unitarias y 101 instrumentadas sin fallos en emulador, más APK debug. Las APIs UNO Q pasaron 15/15 y 17/17 pruebas Python; ambos ZIP incluyen los módulos nuevos y pasan la verificación de integridad. pgTAP local: 79/79; tres escenarios SQL adicionales de aislamiento/RLS pasan con `ROLLBACK`. La aceptación física, la clave NVIDIA y la protección de contraseñas filtradas siguen pendientes; BLE conserva un riesgo de identidad/frescura documentado en H15.

## Inventario funcional y datos

| Área | Entradas y fuente | Verificación pendiente |
|---|---|---|
| Cuenta y perfil | MainActivity, SupabaseBackend, EncryptedSessionStore, AppStore; Auth y profiles | Recuperación, cambio de cuenta, identidad frente a modo del teléfono |
| Navegación | TzindaNavigationPolicy y MainActivity; Inicio, Alertas, Salud, Supervisión; cámara: Inicio, Alertas, Vincular, Ajustes | Atrás, restauración, accesibilidad y permisos |
| Contactos y emergencia | AppStore/ContactsRepository, SQLite sync_contacts, GuardianMonitorService | Retención, cancelación, regresión sin SMS real |
| Historial y biometría | LocalTelemetryDatabase con 30 días/100000 filas; UI local paginada; historial remoto del cuidador por páginas bajo demanda (caché de hasta 1000); fall_events, measurements | Aceptación física, frescura y prueba Android de páginas remotas |
| Medicamentos | MedicationRepository/Database/UI/ReminderScheduler; medications y medication_intakes | Caché, concurrencia, revocación, reinicio |
| Cuidados | CareFeatureStore; JSON records/deleted; care_records, alert_followups | Ámbito de cuenta/persona, sincronización y estado geográfico |
| Dispositivos | IDs locales, devices, BLE y Wi-Fi UNO Q | Registro frente a conectividad, identidad del periférico |
| Cámara | CameraFallController, enlaces RPC, CameraFallRealtime | Consentimiento, dos teléfonos y revocación |
| Ubicación | Servicio Android, sync_locations, location_history/caregiver_locations | Consentimiento y separación entre uso local y publicación |
| Sincronización | SupabaseSyncRunner, JobScheduler, colas SQLite y JSON | Estado agregado, reintentos, sesiones concurrentes |

## Hallazgos del checklist

| ID | Estado | Evidencia / siguiente trabajo |
|---|---|---|
| H01 | [~] Corrección local/remota verificada; aceptación física pendiente | `20261103070000_profiles_role_read_only` aplicada; consulta remota confirma que `authenticated` ya no puede INSERT/UPDATE `profiles.role` y conserva nombre/preferencias. Prueba local pgTAP 8/8; cambio de cuenta y dos teléfonos pendientes |
| H02 | [x] Corrección local verificada | 404 ya no se convierte en []; descarga paginada, parseo completo, transacción; conservar acciones pendientes |
| H03 | [~] Agregación verificada; cobertura integral pendiente | Estado agregado de telemetría, medicamentos, cuidados/borrados, perfil y registro; falta comprobar escenarios completos de cuenta |
| H04 | [x] Corrección local verificada | Estado pending/error/confirmed; únicamente confirmación permite envío dependiente de dispositivos |
| H05 | [~] Cuenta, caché y propiedad de cuidados verificadas localmente | Lotes 4, 10, 11 y 19: A → B → A, callbacks por vínculo y recuperación heredada guiada; pendiente revocación integral en dos teléfonos |
| H06 | [~] Separación incremental; reglas y subpantalla de dispositivos extraídas | Historial en LocalTelemetryDatabase/AppStore, rutas en TzindaNavigationHistory, seguimientos en AlertFollowupPolicy (Lote 24) y vista de dispositivos compartidos del cuidador en CaregiverDeviceLinksUi (Lote 25); quedan pantallas y flujos de UI por extraer de MainActivity |
| H07 | [~] Historial y entrada de Alertas validados en emulador | Lotes 16 y 19; Lote 29 corrige la pestaña inicial a Notificaciones y conserva Signos vitales; pendiente aceptación física y revisión manual por perfiles |
| H08 | [~] Persistencia e historial paginado validados en emulador | SQLite conserva 30 días/100000 filas y la UI pagina en bloques de 100; pruebas instrumentadas pasan; falta carga prolongada y aceptación física |
| H09 | [~] Propiedad y recuperación heredada guiada implementadas; cuidados, seguimientos y DELETE idempotente validados localmente | Lotes 10–11, 19–22; falta validar sincronización offline en dos instalaciones |
| H10 | [x] Texto verificado por prueba unitaria | UI usa «Sin pendientes locales» y conserva por separado última confirmación remota |
| H11 | [x] Corrección local verificada | Estado geográfico en zone_runtime; configuración conserva su versión y cola |
| H12 | [~] Función/cuota y cinco migraciones recientes aplicadas; ajustes de Dashboard pendientes | El remoto registra las migraciones de tomas/actor, rol, propiedad de `phone_motion_status`, cuota y los cinco índices de FK; `medication-label` está activa con `verify_jwt=true`. Falta conciliar historial antiguo y confirmar `NVIDIA_API_KEY` y protección de contraseñas filtradas |
| H13 | [~] Aislamiento y permisos verificados en código, SQL local y metadatos remotos | Las pruebas pgTAP locales pasan 79/79; tres escenarios transaccionales de RLS también pasan. Las políticas, grants, trigger, FK e índices FK se revisaron en Supabase remoto sin leer filas personales. Falta aceptación con dos cuentas/teléfonos físicos |
| H14 | [~] Transporte Wi-Fi protegido en código; integración física pendiente | El Android exige HTTPS, SAN de host y pin SHA-256, desactiva redirecciones y guarda URL/huella/token atómicamente. Las dos APIs UNO Q requieren TLS 1.2+ y fallan sin certificado/clave. Suites de emulador y Python pasan; no se comprobó el certificado en un UNO Q ni TLS en el firmware que envía lecturas |
| H15 | [~] Vínculo y cifrado BLE exigidos; identidad de aplicación y frescura aún no están resueltas | Android solo conecta dispositivos que Android ya marca como vinculados y BlueZ exige enlace cifrado. Un periférico malicioso que llegue a vincularse puede anunciar el UUID público y aportar valores falsos: no existe identidad criptográfica propia de la pulsera ni secuencia/fecha para detectar replay. Falta validar el modo MITM y diseñar inscripción/autenticación del hardware antes de considerar cerrado este riesgo |
| H16 | [x] Índices de claves foráneas aplicados y comprobados | La migración aditiva `20261109040215_supporting_foreign_key_indexes.sql` crea los cinco índices reportados por el asesor. La consulta remota confirma las definiciones y el asesor ya no reporta `unindexed_foreign_keys`; no se eliminaron filas |

## Lote 1: integridad de descarga y registro

Problemas: errores HTTP se interpretaban como borrado; consultas limitadas a 500/1000 filas disparaban depuración de caché; acciones pendientes del cuidador se eliminaban; confirmación de envío podía marcar una edición posterior como sincronizada; registro de dispositivo fallido quedaba confirmado.

Archivos: `SyncSafety.kt`, `MedicationRepository.kt`, `MedicationDatabase.kt`, `SupabaseBackend.kt`, `SupabaseSyncJobService.kt`, `app/build.gradle.kts`, pruebas `SyncSafetyTest` y `MedicationDatabaseTest`.

Solución: páginas ordenadas por ID hasta respuesta vacía, rechazo de respuesta malformada/repetida, descarga y validación de ambas colecciones antes de aplicar una transacción, depuración solo de filas confirmadas, acuse condicionado a la versión enviada, propagación del error de envío del cuidador y estado explícito de registro.

Pruebas: errores 401/403/404/409/500/timeout controlados, fila inválida, paginación con límite inferior al solicitado, página repetida, respuesta vacía válida, fallo y recuperación de registro; SQLite: conservación de pendientes/fallidos, acuse atrasado, aislamiento de propietario, reapertura.

Resultado: 100 pruebas unitarias sin fallos (incluidas 6 de SyncSafetyTest); 7 pruebas instrumentadas de MedicationDatabaseTest sin fallos, en paquete audit y emulador API 37. Compilaron debug y APK de instrumentación. Ver `evidencia/lote-1-instrumentadas.log`. Riesgos: páginas no constituyen una transacción remota; cambios concurrentes se reconcilian en la siguiente ejecución. Falta validación con Supabase de pruebas y teléfonos. Un esquema sin actor_user_id se informa como error y conserva datos; ya no se oculta la incompatibilidad.

## Supabase remoto

Proyecto identificado mediante la URL configurada: `vhppdgbmmtfrbzocitob`. Evidencia de inventario, migraciones y asesores en `evidencia/`. Las 18 tablas públicas observadas tienen RLS. Esto no demuestra aislamiento correcto: falta ejecutar casos negativos por actor en una base de pruebas.

El asesor reporta funciones SECURITY DEFINER ejecutables por autenticados, dos tablas de cámara sin políticas directas y protección de contraseñas filtradas desactivada. Las funciones y tablas pueden depender deliberadamente de RPC; hay que revisar sus controles antes de cambiar permisos. [Asesor de funciones](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable), [protección de contraseñas](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

Estado **al inicio** de esta auditoría: no se había aplicado ninguna migración remota. En el lote 6 se inició el motor y se creó una base Supabase local; ver pruebas de reproducción y RLS. Hay emulador Android, sin dispositivo físico conectado al inicio. Los cambios remotos autorizados posteriormente se registran en Lotes 27–28.

## Pendientes de aceptación física

- [ ] Dos teléfonos: cuenta protegida y cámara sin alteración del rol remoto; cuidador vinculado y revocado.
- [ ] Offline → editar → cerrar proceso → abrir → reconectar, comprobando filas por ID.
- [ ] Permisos Android, reinicio, batería, recordatorios y GPS.
- [ ] BLE: emparejar la pulsera real, validar vínculo cifrado y reconexión; revisar pairing MITM y lecturas de aplicación antiguas/replay.
- [ ] Wi-Fi UNO Q: IP estable incluida en el SAN, certificado/clave instalados fuera del ZIP, huella confirmada fuera de banda y compatibilidad HTTPS del firmware emisor.
- [ ] Cámara real, cuenta regresiva y cancelación; cualquier SMS/llamada requiere entorno expresamente autorizado.

El objetivo no está completado mientras queden correcciones confirmadas sin implementar o comprobaciones automatizables sin resolver.

## Lote 2: estado global y cuidados

Implementado y verificado en las pruebas indicadas; no equivale a aceptación integral:

- `AppStore.syncModules` agrega las cinco colas e incorpora el resumen a la decisión de éxito del runner. El reintento general incluye medicamentos.
- `TzindaStatusCopy` no declara éxito remoto solo porque no haya pendientes. Inicio muestra conteos por módulo.
- `CareFeatureStore` comparte el bloqueo entre instancias; versiones crecientes por registro y acuses condicionados a la versión enviada conservan ediciones concurrentes.
- `zone_runtime` conserva el estado geográfico sin modificar la configuración ni crear envíos de zona. Los eventos de entrada/salida siguen generando registros de bitácora.
- Resultado: **101 pruebas unitarias y 10 instrumentadas, cero fallos/errores/omitidas**. Compilación debug y APK de pruebas correctas. Evidencia en `lote-2-compilacion.log`, `lote-2-unitarias.json`, `lote-2-instrumentadas.log` y `lote-2-instrumentadas.json`. Instrumentación limitada a MedicationDatabaseTest y CareFeaturePersistenceTest; no se ejecutó NativeAppTest ni alertas reales.
- Pruebas: agregación con fallo de medicamento y borrado pendiente; acuse atrasado desde otra instancia; borrado pendiente tras reapertura; ubicación inicial sin ensuciar configuración.
- Limitaciones: falta el aislamiento de cuenta/persona en cuidados, pruebas completas de sesión concurrente y la política de autorización después de revocar vínculos. H03 no implica que esas capas estén terminadas.

## Continuidad de revisión de seguridad

Scan existente: `cd27264a-1f0e-404a-9563-2e495747963d`; cobertura parcial, sin informe final. Se guardó un checkpoint con modelo preliminar y candidatos que aún requieren validación del coordinador: callback de recuperación, clave cloud en APK, autenticidad de sensores, propiedad del dispositivo en phone_motion_status y consentimiento de ubicación. No se atribuye cobertura completa a los mensajes parciales de los revisores.

## Lote 3: modo local y perfil de cuenta

Problema: cambiar el modo de un teléfono publicaba `profiles.role`; una descarga podía cambiar el modo en otra instalación. El acuse del perfil también podía reconocer una edición realizada después de iniciar la petición.

Archivos: `ProfileSyncPolicy.kt`, `AppStore.kt`, `SupabaseSyncJobService.kt`, `SupabaseBackend.kt`, `MainActivity.kt`, `TzindaUx.kt`; pruebas `ProfileSyncPolicyTest` y `ProfileIsolationTest`.

Solución: `user_role` es modo local y `account_role` conserva el metadato remoto. Payloads de perfil omiten `role`; la descarga nunca altera el modo local. Snapshot coherente de versión, nombre y consentimiento; versiones locales crecientes; acuse solo de la versión enviada; una respuesta atrasada no sobreescribe una edición posterior. Las escrituras exigen una fila confirmada por el servidor y preservan preferencias remotas no modificadas. UI explica cuenta, modo y permisos por vínculo. Matriz y limitaciones: [perfiles-y-permisos.md](perfiles-y-permisos.md).

Resultado: **106 pruebas unitarias y 14 instrumentadas, cero fallos/errores/omitidas**. Compilación debug y APK de pruebas correctas, con `-PtzindaAudit=true`. Evidencia en `evidencia/lote-3-*`. Las 14 instrumentadas incluyen los tres grupos de persistencia, cuidados y perfil; no son pruebas reales de red ni dos teléfonos.

Riesgos y pendientes: no se ejecutó migración ni actualización de roles remotos; clientes anteriores aún pueden escribir su metadato. H05 sigue abierto para revocación y aislamiento integral entre dos teléfonos. El intercambio de recuperación ya está ligado a una solicitud local PKCE y validado en Lote 12; siguen pendientes el proveedor de correo remoto y su aceptación en teléfonos.

## Lote 4: conservación e aislamiento entre cuentas

Problema: `bindLocalDataToUser` borraba SQLite y preferencias de la cuenta anterior, incluyendo cambios sin subir. Las instancias antiguas y los recordatorios tampoco tenían un ámbito persistente explícito.

Solución: `AccountStorage` mantiene intactos los archivos heredados para su propietario inicial y asigna archivos privados independientes a las cuentas siguientes mediante un identificador derivado del userId. El puntero de cuenta activa se publica después de preparar su propiedad. Cada store y helper SQLite captura su ámbito; una tarea antigua conserva A y no cambia silenciosamente a B. Al volver a A se reabren sus archivos, sin descargar para recuperar datos pendientes. No se sustituyó SQLite por otra tecnología ni se ejecutó una migración remota.

La actividad vuelve a abrir los repositorios al cambiar de cuenta, limpia borradores de pantalla, detiene recursos de la cuenta anterior y verifica la cuenta antes de mostrar datos al arrancar. El estado guardado de navegación incluye el ámbito. Las sesiones cifradas usan exclusión entre instancias, renovación condicionada a la sesión esperada y cierre limitado al mismo usuario. Las peticiones de una instancia de backend antigua rechazan una sesión de otro usuario. Los recordatorios nuevos incluyen ámbito en URI y extras; el receptor descarta otro ámbito. La exclusión de bases en backup cubre también sus nuevos nombres.

La recuperación de contraseña consulta al usuario con el token provisional sin escribir una sesión incompleta. El callback ya requiere un flujo local iniciado por el usuario, verifica el reto PKCE, confirma que el correo coincide y guarda la sesión de recuperación aislada; Lote 12 añadió prueba de recreación de Activity y validación local del protocolo. El proveedor de correo alojado y la aceptación en teléfonos continúan pendientes.

Archivos principales: `AccountStorage.kt`, AppStore, CareFeatureStore, helpers SQLite, EncryptedSessionStore, SupabaseBackend, runner, MainActivity, servicio de monitoreo y recordatorios; reglas de backup. Pruebas nuevas: `AccountStorageTest`. `MedicationDatabaseTest` usa ahora un nombre de base exclusivo de prueba para no borrar un archivo abierto por otro grupo.

Resultado: **106 pruebas unitarias y 18 instrumentadas; cero fallos, errores u omitidas**. Compilación audit correcta. Las cuatro pruebas nuevas cubren A → B → A con perfil/contactos/colas/medicamentos/cuidados; instancia antigua conservada; rechazo de sesión nueva desde backend anterior; renovación atrasada; arranque real de MainActivity con sesión B y datos previos de A, sin mostrar A. Evidencia: `evidencia/lote-4-validacion.log`, `lote-4-instrumentadas.log` y resúmenes JSON.

Limitaciones restantes: probar cambio de sesión con monitoreo/alerta ya en curso, batería/reinicio físico y cuenta remota real. Falta separar datos del cuidador por persona vinculada, confirmar los permisos de acciones de medicamentos y ejecutar revocación RLS. Estas pruebas no enviaron alertas ni contactaron Supabase; las sesiones del emulador son ficticias y el paquete audit no lleva configuración remota.


## Lote 5: permisos de medicamentos y recordatorios propios

El repositorio comprueba cuenta, modo local, propietario de medicamento/toma y vínculo seleccionado en cada acción. El propietario puede editar sus medicamentos en modo persona protegida. El cuidador con sesión puede registrar tomas de medicamentos activos y compartidos de la persona vinculada; no puede editar recetas ni otorgarse permiso para compartirlas. Cambiar de persona, cerrar sesión o descartar un vínculo invalida las acciones desde una instancia ya abierta. Las filas retenidas tras revocar un vínculo no son consultables desde el módulo, sin eliminar evidencia local pendiente.

Antes de subir acciones del cuidador se consulta el vínculo activo. Un fallo de red conserva la cola; una revocación confirmada descarta las preferencias derivadas. Una respuesta atrasada no se aplica tras cambiar la cuenta o el vínculo. El permiso cacheado permite preparar acciones offline; la autorización definitiva la debe imponer RLS al sincronizar. La revocación remota no puede detectarse mientras el teléfono permanezca desconectado.

Los recordatorios se programan únicamente para el propietario en modo persona protegida. El receptor vuelve a comprobarlo, incluso para alarmas antiguas. El marcado automático de tomas atrasadas usa owner_user_id y no modifica la caché del cuidador. Se corrigieron los textos de consentimiento, vinculación y medicamentos que afirmaban simultáneamente «solo lectura» y «confirmar tomas». La preferencia de accesibilidad del módulo usa el archivo de la cuenta actual.

Archivos: MedicationRepository, MedicationReminderScheduler/Receiver, MedicationDatabase, MedicationModuleUi, MainActivity; nuevo MedicationAuthorizationTest. Resultado final: **106 pruebas unitarias y 25 instrumentadas; cero fallos/errores/omitidas**, compilación audit y APK de pruebas correctas. Evidencia: `evidencia/lote-5-validacion.log` y resúmenes JSON. Las siete pruebas nuevas cubren propietario ajeno, edición por cuidador, acción trazada por actor, medicamento privado, otra persona, revocación, cierre de sesión, cambio de modo y marcado de atrasadas por dueño. No ejecutan alertas reales ni llamadas a Supabase.

Pendientes: políticas remotas y columna actor_user_id no están reproducidas en la migración original; preparar y probar reconciliación local. También falta aislar todas las colecciones de cuidados/seguimientos y callbacks Realtime al cambiar de persona. Este lote no declara H05 ni H13 completados integralmente.


## Lote 6: contrato HTTP y políticas de tomas

Inspección remota de solo lectura: existen actor_user_id y su FK; conviven políticas antiguas del propietario con variantes que permiten al cuidador insertar y actualizar filas completas. La nueva migración `20261102133804_medication_actor_authorization.sql` agrega la columna si falta, elimina las políticas redundantes, impide crear horarios al cuidador y permite actualizar únicamente acciones de tomas compartidas, activas y con vínculo. Un trigger SECURITY INVOKER protege la identidad de la toma, su fecha programada y el actor; rechaza reemplazar una acción completada y permite repetir exactamente la misma petición tras una respuesta perdida. Se retiran los permisos anónimos y privilegios no usados de estas dos tablas. No se borra ni reescribe el historial existente.

Android usa PATCH para acciones del cuidador y exige una fila devuelta con ID, propietario, actor y estado correctos. Cero filas no equivale a éxito. Se eliminó el reintento de escritura que quitaba actor_user_id después de un 400; ese reintento todavía existía en el envío, aunque la lectura del lote 1 ya exigía la columna. Una transición automática del propietario publica actor nulo. El acceso HTTP sin TLS al servidor loopback de las pruebas se permite exclusivamente en debug con paquete terminado en .audit; la configuración habitual sigue exigiendo HTTPS.

La reproducción desde cero detectó SQLSTATE 42P13 en `20261025170000_camera_pairing_relink_and_owner_management.sql`: CREATE OR REPLACE intentaba cambiar el tipo de retorno del RPC. Ese archivo ahora recrea solamente la función dentro de una transacción y vuelve a establecer sus permisos. No elimina tablas ni datos. Es una corrección del historial para instalaciones nuevas; **no se deben volver a aplicar todas las migraciones al proyecto hospedado**.

Validación final:

- Compilación audit, **106 unitarias y 29 instrumentadas, cero fallos/errores/omitidas**. Cuatro pruebas HTTP reales contra 127.0.0.1 dentro del emulador verifican PATCH mínimo, respuesta vacía, actor incorrecto y error 400 sin eliminar atribución. La primera ejecución falló porque el cliente rechazaba loopback; se conservó ese log y se corrigió el soporte exclusivo del paquete de pruebas.
- **31 pruebas pgTAP aprobadas** en PostgreSQL/Supabase local: anonimato, datos privados, otra cuenta, permisos de receta/toma, suplantación, cambios de horario, doble acción, posposición, reintento, revocación de compartir y de vínculo.
- Historial completo reproducido desde cero con los archivos finales (`lote-6-replay-completo.log`), seguido del mismo conjunto SQL (`lote-6-rls-final.log`). Se comprobó previamente que esa base local recién creada no tenía usuarios, medicamentos ni tomas. No se reseteó ninguna base remota ni otra base Docker.
- Asesor local de seguridad: dos avisos INFO por tablas de cámara con RLS sin políticas directas; usan RPC y su revisión integral continúa. No acredita por sí solo la seguridad del backend.

Evidencia: `lote-6-validacion-final.log`, resúmenes JSON, `lote-6-rls-final.log`, políticas remotas leídas, lista de migraciones y asesores locales. El proyecto remoto no recibió cambios. Falta coordinar el despliegue de la migración con el cliente nuevo, verificar el recorrido contra un proyecto de pruebas y completar las demás áreas de la auditoría. Los clientes anteriores que crean tomas como cuidador necesitarán actualizarse; el cliente nuevo únicamente actualiza tomas existentes.


## Lote 7: propiedad de dispositivos en resúmenes de movimiento

Se reprodujo el candidato de seguridad en la base local antes de la corrección: un usuario ficticio B insertó phone_motion_status con el UUID del teléfono de A. La política solo comparaba user_id con auth.uid() y la FK únicamente comprobaba que existiera device_id. La transacción de reproducción se revirtió completa; ver `lote-7-propiedad-antes.sql` y `.log`.

La migración `20261102182134_phone_motion_device_ownership.sql` añade una FK compuesta (user_id, device_id) hacia devices y exige propiedad del dispositivo en INSERT/UPDATE. Conserva las filas heredadas: si hay inconsistencias, deja la restricción NOT VALID para revisar esas filas, pero protege escrituras nuevas; si no las hay, la valida. No borra registros. Se retiran permisos anónimos y privilegios no usados. La consulta de solo lectura al remoto devolvió **0 filas con propietario de dispositivo inconsistente** al momento de esta revisión; no se aplicó allí la migración.

Pruebas: **16 casos pgTAP nuevos aprobados; 47 SQL en conjunto con medicamentos**. Verifican inserción y actualización propias, rechazo al usar/reasignar dispositivos ajenos, cuidador autorizado solo para lectura, revocación, aislamiento de anónimo y FK incluso sin RLS. El informe global de Codex Security continúa abierto: esta evidencia valida este defecto específico, no todo el backend. El despliegue remoto sigue pendiente.

Compilación Android audit y tarea de pruebas unitarias correctas (`lote-7-compilacion.log`); este lote solo cambia SQL y no repitió instrumentación Android. Las 106 unitarias quedaron UP-TO-DATE porque no cambió Kotlin. Asesor de seguridad local: dos avisos INFO de cámara, sin advertencias nuevas de esta migración.


## Lote 8: consentimiento de ubicación y cola local

Problema verificado: recordLocation añadía GPS a sync_locations incluso cuando solo se usaban zonas locales, y syncLocationHistory subía esa cola antes de revisar include_location. Los publicadores directo y de JobScheduler actuaban por separado.

LocationSharing centraliza la autorización por cuenta, modo persona protegida y período de consentimiento. El GPS para uso local se conserva en readings, pero solo los puntos posteriores al consentimiento vigente entran en la cola publicable. Al retirar permiso o cambiar de modo, los puntos en cola quedan withheld: no se borran las lecturas, no cuentan como pendientes de envío y un reintento o acuse atrasado no los reactiva. Reautorizar permite puntos nuevos, sin publicar el recorrido previo. La cola heredada no conserva evidencia de consentimiento por fila y se retiene localmente sin subirla.

Todos los publicadores de ubicación comparten una serialización y vuelven a comprobar el consentimiento entre peticiones. La retirada de la última posición en vivo se guarda como trabajo pendiente por dispositivo, se reintenta y aparece en el resumen agregado bajo «Privacidad de ubicación». Si el permiso cambia durante un envío, se solicita retirar esa posición al terminar. El modo cámara/cuidador no publica GPS del modo protegido, y una instancia de cuenta anterior no publica ni retira datos después del cambio de cuenta. La ubicación en vivo requiere además monitoreo activo; el historial recogido con permiso puede sincronizarse después de detenerlo.

Archivos: LocationSharing, LocalTelemetryDatabase, AppStore, runner, servicio de monitoreo y MainActivity. Las zonas locales siguen recibiendo posiciones, usando ahora el store de la cuenta capturada. La confirmación de permiso explica SMS y publicación al cuidador.

Validación final: **106 pruebas unitarias y 39 instrumentadas, cero fallos/errores/omitidas**; compilación audit y APK de pruebas correctas. Evidencia en `lote-8-validacion-final.log` y resúmenes JSON. Diez pruebas instrumentadas nuevas comprueban uso solo local, período de consentimiento, revocación/reapertura/reintento, reautorización, retirada durante envío de historial/en vivo, limpieza fallida, cambio de modo/cuenta y cola heredada. Las funciones de envío de esas pruebas son dobles locales; no ejecutan GPS real ni envían posiciones a Supabase.

Límites: una petición que ya salió puede alcanzar el servidor antes de que este conozca la revocación. La retirada de la última posición requiere conexión y sesión; su pendiente se conserva hasta confirmarse. El historial ya almacenado remotamente no se elimina por este cambio y sus políticas requieren revisión adicional. Siguen pendientes GPS, batería y permisos reales en teléfonos. No se modificó el remoto.


## Lote 9: atribución de tomas sin sesión

La revisión posterior al endurecimiento RLS detectó un caso no cubierto por el lote 6: una acción propia sin sesión podía conservar actor nulo y fallar al actualizar una toma remota pendiente. El repositorio usa ahora la identidad del archivo de cuenta para acciones propias offline. Al adoptar datos anónimos, claimLocalData asigna dueño y actor a las acciones humanas sin actor, preservando ID, fecha e instancia programada. El backend exige que el upsert sea del propietario autenticado y completa únicamente su atribución ausente en registros heredados; el cuidador sigue usando PATCH.

Verificación: **106 unitarias, 42 instrumentadas y 49 SQL locales, sin fallos**; compilación audit correcta. Tres pruebas Android nuevas verifican acción propia offline, adopción anónima conservando identidad y payload heredado; dos casos SQL comprueban el upsert sobre una toma pendiente y su actor resultante. Evidencia: `lote-9-validacion.log`, `lote-9-rls.log` y resúmenes JSON. No se modificaron las políticas remotas ni se enviaron alertas.


## Lote 10: caché del cuidador y respuestas tardías

CaregiverAccess captura cuenta, actor, vínculo, propietario, dispositivo y una generación de selección. Aceptar otro vínculo limpia las preferencias derivadas del anterior; revocar o volver a vincular la misma pareja invalida sus callbacks. Una respuesta HTTP, Realtime o del runner solo escribe si su ámbito sigue vigente. Las peticiones de datos compartidos usan un backend ligado al actor capturado. Se valida el vínculo exacto, incluyendo estado activo y dispositivo; una revocación confirmada vacía la caché, detiene las suscripciones y vuelve a mostrar la pantalla sin persona vinculada. Una respuesta de ubicación vacía confirmada retira la posición cacheada; un fallo de consulta conserva el último dato.

Al cambiar de modo se invalidan las respuestas pendientes y la caché derivada, conservando la selección para volver al modo cuidador. Al actualizar desde una caché antigua sin procedencia se descartan solo sus preferencias derivadas una vez, preservando vínculo, perfil propio y registros locales pendientes. El nuevo controlador permite extraer estas decisiones de MainActivity sin reescribir todas sus pantallas.

Validación final: **106 unitarias y 49 instrumentadas, cero fallos/errores/omitidas**, compilación audit correcta; evidencia en `lote-10-validacion-final.log` y resúmenes JSON. Siete pruebas instrumentadas nuevas ejercitan cambios de persona, generación, sesión, cuenta y modo, migración de caché y validación exacta de vínculo activo. El primer pase quedó fallido en la nueva comprobación de rechazo de una respuesta de vínculo revocado; se añadió la guarda y se recompiló el código final. No se usa Supabase remoto ni se emiten alertas en esas pruebas.

H05 sigue abierto: los registros/tombstones pendientes de CareFeatureStore requieren propiedad explícita, y todavía falta validar el flujo completo de revocación contra un proyecto de pruebas y dos teléfonos. Este lote verifica las preferencias derivadas y el control de callbacks, no todos los datos compartidos de la aplicación.


## Lote 11: propiedad de cuidados y eliminaciones pendientes

CareFeatureStore conserva propietario y actor en notas, rutinas, zonas y seguimientos, así como propietario y versión en las eliminaciones pendientes. Cambiar de persona o de modo ya no reasigna esas operaciones al destinatario seleccionado después. Las vistas locales filtran por propietario y el GPS del cuidador no evalúa zonas de la persona protegida. Los formularios capturan el propietario al abrirse y rechazan guardar si cambia antes de confirmar. Restaurar y volver a eliminar un registro genera otra versión; un acuse tardío no borra la operación nueva.

Los datos anónimos nuevos tienen un ámbito local explícito que se adopta al iniciar sesión conservando IDs y actor. Los registros heredados sin procedencia se retienen sin subirlos: solo un ID presente en un snapshot remoto autorizado permite reconciliar su propietario. La pantalla informa cuántos requieren revisión. Los snapshots de cuidados y seguimientos se paginan hasta el final; el runner comprueba cuenta, modo y vínculo antes de cada envío. DELETE exige la fila exacta devuelta por el servidor y conserva la cola ante una respuesta vacía.

Validación final: **106 unitarias y 57 instrumentadas, cero fallos/errores/omitidas**, compilación audit correcta. Seis pruebas nuevas de propiedad/persistencia y dos de contrato HTTP comprueban separación de personas, reapertura, adopción anónima, rechazo de propietario ajeno, reconciliación heredada, versiones de eliminaciones y respuestas de DELETE. Evidencia: `lote-11-validacion-final.log` y resúmenes JSON. Las pruebas usan datos ficticios y HTTP loopback; no modifican Supabase remoto ni generan alertas.

H05/H09 continúan parcialmente abiertos: se conserva la recuperación guiada de registros heredados puramente locales sin propietario verificable y sigue pendiente la aceptación en dos teléfonos y la revocación remota integral. La descarga completa de contenido propio y los seguimientos se implementaron en Lotes 20–21. Lote 22 resuelve la ambigüedad de un DELETE cuya respuesta se perdió: un snapshot completo y autorizado del propietario puede confirmar que el ID ya falta, mientras que una fila todavía presente conserva la cola y sigue por el DELETE exacto. No se usa ausencia del snapshot compartido del cuidador para reconocer eliminaciones.

## Lote 12: recuperación de contraseña vinculada al teléfono

El callback exportado aceptaba access_token/refresh_token proporcionados en el enlace y sustituía la sesión activa. Ahora la solicitud genera un verificador aleatorio de 256 bits, envía su reto S256 y guarda el flujo cifrado con Android Keystore fuera del backup. Solo se acepta la URI configurada con un único parámetro code, sin fragmentos ni otros parámetros. El intercambio usa el verificador local, comprueba el correo solicitado y consume el flujo una sola vez. Los enlaces de tokens directos, no solicitados, duplicados, vencidos o para otro correo no sustituyen la sesión.

La autorización recuperada se guarda por separado durante un máximo de una hora y solo sirve para actualizar la contraseña mediante el token recuperado. No se enlazan archivos de cuenta ni se reemplaza la sesión activa. Tras guardar se solicita iniciar sesión normalmente. Cancelar, iniciar sesión o pedir otro enlace invalida el flujo anterior. Un intercambio cuya respuesta se pierde requiere solicitar otro enlace; no se reutiliza una autorización ambigua.

La revisión independiente detectó una regresión de ciclo de vida: una respuesta completada después de recrear la actividad no llegaba a la pantalla nueva. El intercambio ahora sobrevive a la recreación y la actividad observa cambios del almacén cifrado al reanudarse y durante su actualización visible. El callback se retira del Intent para no repetirlo. La prueba demora la respuesta HTTP, recrea la actividad real y comprueba que la instancia visible muestra Nueva contraseña conservando la cuenta anterior.

Verificación final: **106 unitarias y 67 instrumentadas, cero fallos/errores/omitidas**, compilación audit correcta. Diez pruebas nuevas cubren petición/código S256, persistencia cifrada, sesión separada, callback malicioso, caducidad, repetición, cambio de solicitud, cancelación, errores de correo/intercambio, ausencia de refresco con la cuenta activa y recreación. Se usó ANDROID_SERIAL=emulator-5580; no se ejecutó nada en el teléfono físico conectado. Evidencia: `lote-12-validacion-cierre.log` y resúmenes JSON. Se conservan los intentos anteriores: error de compilación por método protegido del test y fallo de ActivityScenario al cambiar la identidad del Intent; corregidos sin retirar la prueba.

Además se ejecutó el protocolo real contra Auth de Supabase local y Mailpit: correo capturado localmente, redirect con code sin tokens, intercambio PKCE, actualización, rechazo de contraseña anterior, acceso con la nueva y rechazo de reutilización del código. Ocho comprobaciones aprobadas. Se conserva un usuario ficticio @example.invalid exclusivamente en la base local. No hubo correos externos, alertas ni cambios remotos. Script y resultado están retenidos en Codex Security, scan cd27264a-1f0e-404a-9563-2e495747963d, `artifacts/recovery/local_pkce.py` y `local_pkce.result.json`.

Contrato consultado: [flujo PKCE de Supabase](https://supabase.com/docs/guides/auth/sessions/pkce-flow) y [cliente oficial Auth](https://github.com/supabase/auth-js/blob/master/src/GoTrueClient.ts). Compatibilidad: solicitar un enlace nuevo desde la versión corregida y abrirlo en el mismo teléfono. Se revisó estáticamente que el hook de correo del repositorio conserva token_hash, type=recovery y redirect_to; su proveedor remoto y los clientes de correo en teléfonos físicos siguen pendientes de aceptación. El informe global de seguridad y la auditoría integral continúan abiertos.

## Lote 13: clave de NVIDIA fuera del APK

El APK compilaba `TZINDA_NVIDIA_API_KEY` y llamaba directamente al proveedor desde el teléfono. Gradle ya no genera los campos de clave/modelo; Android conserva la selección explícita de lectura remota y el análisis local. La lectura en nube envía solo JPEG reescalado a una función Edge, requiere sesión y conserva 135 segundos de timeout para la inferencia. Los errores, sesión vencida o límite alcanzado llegan al fallback local. La interfaz y el aviso de privacidad describen el paso por Supabase.

La función valida de nuevo al usuario mediante Auth, rechaza usuarios anónimos, cuerpos sobredimensionados (incluido flujo fragmentado), campos de control añadidos, Base64/JPEG inválido y dimensiones JPEG fuera de límite. Fija endpoint, prompt, modelo y presupuesto de salida; desactiva redirecciones y acota respuesta y tiempo. Reserva cuota en PostgreSQL antes de pagar inferencia: 10/hora y 30/día por usuario, 1000/día global UTC. El contador se guarda en esquema privado, no accesible por usuarios del cliente; una reserva global serializa las instancias.

Validación disponible: **15 pruebas Node del handler aprobadas** y **66 pruebas pgTAP locales aprobadas**; runtime Edge local rechazó petición sin auth, token falso y campo model del cliente, y dejó pasar una sesión ficticia hasta la validación JPEG. Prueba concurrente local: 20 reservas, 10 aceptadas y 10 rechazadas con contador persistido en 10. Los 15 tests Node actuales pasan, evidencia `evidencia/lote-13-edge-tests-current.log`. La batería Android final de esta auditoría registró 111 unitarias y 93 instrumentadas aprobadas (Lote 21), reemplazando los conteos antiguos de este lote. No se invocó NVIDIA, no se desplegó Supabase remoto ni se llamaron alertas. Evidencia adicional en `evidencia/lote-13-*` y `evidencia/lote-21-validacion.log`.

H13 sigue abierto para revisión RLS total. El nuevo RPC privado se probó localmente; su migración requiere aplicar tras revisar el esquema divergente del proyecto alojado. La distribución requiere configurar el secreto del servidor, desplegar la función y retirar APK previos/rotar la antigua clave si se compartió. Esos pasos aún no se han realizado. Falta probar cámara, galería, sesión y envío con cuentas de prueba en teléfonos físicos.

## Lote 14: autenticación de la API Wi-Fi del UNO Q

Hallazgo confirmado en el código: el listener estaba enlazado a `0.0.0.0` y permitía consultar estadísticas/lecturas y escribir muestras sin autenticar. Ahora `/api/v1/stats`, `GET /api/v1/sensor` y `POST /api/v1/sensor` exigen un Bearer token de al menos 32 bytes configurado en `TZINDA_API_TOKEN`; el servicio falla cerrado con HTTP 503 si falta la variable. `/health` sigue público para diagnóstico y el flujo BLE/interno de Arduino Bridge permanece local.

La app cifra el secreto con AES-GCM y Android Keystore, lo guarda en el contexto local aislado de la cuenta, evita incluir el campo visible en autofill/estado de Activity y adjunta el token al sondeo Wi-Fi y a la prueba de conexión. La guía del UNO Q especifica configurar el mismo secreto en Arduino App Lab. Se documenta que HTTP en una red compartida no cifra los datos; Wi-Fi requiere una LAN de confianza. Archivos: `api_auth.py`, `main.py`, `test_api_auth.py`, `UnoQLocalAuth.kt`, `GuardianMonitorService.kt`, `MainActivity.kt` y `arduino/TzindaUnoQPulsera/README.md`.

Verificación local completada en esta continuación: `python -m unittest -v` aprobó **9/9** casos. La primera corrida reveló que Windows abortaba la conexión del test cuando enviaba el cuerpo antes de recibir el rechazo; se ajustó el caso para comprobar el 401 en la fase de encabezados, antes de enviar datos. Gradle ejecutó **111 unitarias y 93 instrumentadas**, sin fallos; esto incluye los controles Android de almacenamiento cifrado. Evidencia final: `evidencia/lote-14-validacion-final.log`; intentos anteriores: `evidencia/lote-14-validacion.log`. El token real, el flujo Wi-Fi y la interacción con la pulsera siguen pendientes de un UNO Q y teléfono físicos.

Checklist: [x] acceso HTTP protegido en código; [x] secreto fuera del APK y cifrado localmente; [x] suite Python y Android ejecutada; [~] configurar/rotar token y validar Wi-Fi en UNO Q/teléfono real. El checklist mencionado en Descargas no se encontró en la ruta provista; se usó este registro del repositorio y el documento de instrucciones adjunto. No se modificó Supabase remoto ni se generaron alertas reales.

## Lote 15: historial local paginado

H08 confirmaba que las pantallas leían el JSON compacto de preferencias, que solo retiene los 100 eventos recientes, aunque el historial duradero se escribía en SQLite. SQLite ya es la fuente de las pantallas de actividad y cámara; la interfaz permite cargar bloques de 100 anteriores y consulta el total real. El resumen JSON de preferencias continúa limitado para evitar crecer sin control. El conteo de posibles caídas del día ahora consulta el historial SQLite completo de la fecha.

Se añadió índice SQLite por fuente/fecha y una migración incremental a la versión 4, sin borrar filas. Cada evento nuevo conserva el mismo ID en preferencias y payload SQLite. Una prueba instrumentada escribe 125 eventos y verifica páginas 100+25 y ausencia de duplicados. Archivos: `LocalTelemetryDatabase.kt`, `AppStore.kt`, `MainActivity.kt`, `EventHistoryDatabaseTest.kt`.

Validación: prueba instrumentada de 125 eventos y páginas 100+25 aprobada en el emulador durante la batería actual (93/93); `git diff --check` correcto, con avisos de conversión de finales de línea. H08 queda parcial solo para carga prolongada y aceptación física. El historial persistente está sujeto a la retención ya implementada de 30 días y 100000 filas.

## Lote 16: regreso por la ruta de navegación

H07 tenía navegación secundaria con destinos de regreso independientes y un fallback que descartaba destinos secundarios. La flecha superior y el botón Atrás ahora usan una pila acotada de 32 pantallas: al entrar a Perfil, Ajustes, Dispositivos, Cuidados o Cámara desde otra pantalla secundaria se conserva el origen real; al seleccionar una pestaña principal se limpia la pila. La ruta se guarda/restaura con el estado de Activity dentro del mismo ámbito de cuenta y se reinicia al cambiar de cuenta o modo.

Se agregó una prueba unitaria del orden de regreso, limpieza al seleccionar destino principal y restauración de ruta. Un control aislado compiló el helper Kotlin actual y comprobó esas rutas; su primera pasada detectó `IntArray?.orEmpty()` inválido, que se corrigió. La batería Android completa volvió a compilar y ejecutar Activity en emulador; todas las instrumentadas pasan. Evidencia del control: `evidencia/lote-16-navigation-check.log`; validación UI final: `evidencia/lote-21-validacion.log`. H07 queda parcial para revisión manual de rutas por perfil y aceptación física.

Esta separación también inicia H06: persistencia/consulta de eventos se concentra en `LocalTelemetryDatabase` y `AppStore`; el estado de rutas queda en `TzindaNavigationHistory`. MainActivity conserva renderizado de pantallas y sigue siendo grande, así que la extracción de pantallas completas queda pendiente.

## Lote 17: historial compartido del cuidador

El cuidador solo descargaba los 100 eventos más recientes y no podía consultar los anteriores. `SupabaseBackend.sharedEventPage` ahora admite páginas de hasta 100 filas con orden estable por fecha e ID. La pantalla de actividad carga páginas adicionales explícitamente, deduplica por ID y conserva hasta 1000 eventos en la caché de la misma persona/vínculo. Al refrescar se conserva el historial ya paginado y el botón de continuación si aún había páginas por consultar, aunque la última página reciente se haya reducido; al cambiar vínculo la caché se invalida por `CaregiverAccess`. La página reciente y los resúmenes rápidos siguen acotados.

Validación: `git diff --check` sin errores. La suite instrumentada completa pasó **89/89** en Medium_Phone_API_37.0; incluye el contrato HTTP loopback de orden, límite, offset y autorización. La suite unitaria pasó **111/111**. Gradle se descargó con ejecución autorizada; el bloqueo inicial de red quedó resuelto. Ver `evidencia/lote-17-validacion.log` y `lote-19-validacion.log`.

## Lote 18: rol del teléfono sin privilegio de escritura remoto

El código ya trataba `AppStore.role()` como modo local y `ProfileSyncPolicy` omitía `role` en sus payloads, pero la migración inicial todavía concedía INSERT/UPDATE de `profiles.role` a `authenticated`. La nueva migración `20261103070000_profiles_role_read_only.sql` revoca privilegios de tabla y los grants explícitos de columna heredados; deja solo INSERT de `user_id`, `full_name`, `preferences` y UPDATE de `full_name`, `preferences`. La lectura del campo legado sigue permitida para mostrar información; no se usa como autorización. Se añadieron una prueba pgTAP de grants y una prueba instrumentada HTTP que comprueba el cuerpo enviado por el cliente. La matriz de perfiles y permisos está en `matriz-perfiles-permisos.md`.

Verificación: la suite unitaria pasó 111/111 y la instrumentada 89/89; la prueba HTTP verifica que el cliente no envíe `role`. La prueba pgTAP de grants se ejecutó después contra la base Supabase local en el Lote 23: **8/8** pasan y la migración fue revertida dentro de la transacción. La migración no se aplicó al proyecto remoto. Ver `evidencia/lote-18-validacion.log` y `evidencia/lote-23-validacion.log`. Los privilegios remotos deben inspeccionarse antes de aplicarla por la divergencia de esquema.

## Lote 19: recuperación explícita de datos locales heredados

La interfaz solo informaba el número de registros sin propietario y la sincronización nunca los asignaba si no aparecía el mismo ID remoto. Ahora, en modo Persona protegida y con la sesión de la cuenta propietaria, se pueden revisar registros y borrados heredados uno por uno. El contenido y el ID se muestran antes de decidir; un registro se asigna a la cuenta y vuelve a quedar pendiente, mientras que un borrado muestra que intentará eliminar la copia remota. UUID inválidos y modos cuidador/cámara no permiten reclamar. No hay adopción automática. Se añadieron pruebas instrumentadas de conservación de IDs/pendientes y rechazo desde el modo cuidador.

La suite instrumentada detectó un test del historial que asumía una segunda página siempre llena y dos pruebas de UI que todavía navegaban por el botón Ajustes oculto del menú anterior. Se ajustaron a páginas parciales y a los accesos de la interfaz vigente, añadiendo tags semánticos a los botones usados por pruebas. Resultado final: **89 instrumentadas y 111 unitarias aprobadas**, cero fallos. Sin SMS, llamadas, acceso remoto ni borrados reales. Los borrados heredados requieren una confirmación explícita por registro y podrían reintentar el DELETE después de sincronizar. Ver `evidencia/lote-19-validacion.log`.

## Lote 20: restauración de cuidados desde Supabase

El runner ya descargaba la colección completa de `care_records`, pero solo usaba sus IDs para asignar propiedad a registros locales heredados. Tras reinstalar la app, la bitácora, las rutinas y las zonas del propietario no se rehidrataban en `CareFeatureStore`. Ahora integra el snapshot remoto después de validar propietario, UUID, tipo, payload y versión. Importa filas ausentes y sustituye filas locales sincronizadas solo cuando la versión remota es más nueva; conserva ediciones pendientes y filas locales que el servidor no devuelve. No fusiona el snapshot del cuidador en el almacén del propietario.

Se añadieron pruebas instrumentadas para restaurar filas, proteger cambios offline y rechazar un snapshot con propietario ajeno sin importación parcial. Validación inicial del 3 de noviembre de 2026: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó con `BUILD SUCCESSFUL`; **111 unitarias y 91 instrumentadas**, cero fallos, errores u omitidas. La restauración adicional de seguimientos se completó y validó en Lote 21. Ver `evidencia/lote-20-validacion.log` y `evidencia/lote-21-validacion.log`.

## Lote 21: restauración del historial de seguimientos

`alert_followups` vive en una tabla append-only separada de `care_records`. El runner ya descargaba estas filas para el cuidador y reconciliaba IDs heredados, pero al propietario no le rehidrataba su historial local después de reinstalar. Ahora normaliza esas filas al modelo local `alert_followup`, comprueba UUIDs, actor, estado, timestamp y propietario, y las integra con la misma regla de merge: registros remotos ausentes se restauran, acciones locales pendientes nunca se sustituyen y no se borran filas locales ausentes del snapshot. El modo cuidador conserva su caché aislado.

Se añadieron pruebas instrumentadas para reponer historial, retener una acción pendiente y rechazar snapshots de otra cuenta sin importación parcial. Validación del 3 de noviembre de 2026: **111 unitarias y 93 instrumentadas**, cero fallos, errores u omitidas; `BUILD SUCCESSFUL` en Medium_Phone_API_37.0 (AVD, API 37). El Lote 22 amplió la cobertura a 94 instrumentadas. Sin dispositivo físico, conexión/mutación de Supabase remoto ni alertas reales. Ver `evidencia/lote-21-validacion.log`. Pendiente: sincronización offline entre dos instalaciones y aceptación física; el resultado no confirma estado ni políticas RLS del Supabase desplegado.

## Lote 22: reconciliación idempotente de eliminaciones

Un DELETE exitoso con respuesta perdida dejaba el mismo trabajo pendiente: el siguiente DELETE vacío no podía distinguirse de un fallo de política o propiedad. En modo propietario, tras descargar y validar completamente `care_records` y `alert_followups`, el runner ahora retira de la cola únicamente los borrados cuyo ID ya no existe en ese snapshot. Si la fila sigue presente, conserva el DELETE con verificación de fila exacta. En modo cuidador no se infiere ausencia a partir de su snapshot filtrado/compartido.

La prueba instrumentada crea dos borrados locales: uno ausente del snapshot, que se confirma, y otro todavía presente, que conserva su versión pendiente. Validación del 3 de noviembre de 2026: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; **111 unitarias y 94 instrumentadas**, cero fallos, errores u omitidas, en Medium_Phone_API_37.0 (AVD, API 37). No se ejecutó ningún DELETE remoto durante la prueba. Ver `evidencia/lote-22-validacion.log`. Pendiente: escenario offline entre dos instalaciones, aceptación física y validación de RLS sobre el Supabase desplegado.

## Lote 23: pgTAP local de cuotas y privilegios

El daemon Docker local quedó disponible; se inició únicamente el contenedor PostgreSQL. El proxy local no publicó el puerto CLI 54322, así que las pruebas se ejecutaron con `psql` directamente dentro del contenedor. Para ambas suites se incluyeron migración y prueba en la misma transacción, sin sus sentencias envolventes `BEGIN/COMMIT`; el archivo de prueba conservó su `ROLLBACK`, que revirtió todo al final.

La suite de cuotas de la función de etiquetas aprobó **17 assertions** (incluye separación por actor, autorización exclusiva de service_role, cuotas hora/día y rollback de identidad); la de privilegios de perfiles aprobó **8/8**. Además, `node --test supabase/functions/medication-label/handler.test.ts` pasó **15/15** con fetches simulados. Ver `evidencia/lote-23-validacion.log`. Estas pruebas usan solo la base local o mocks; no demuestran el estado del proyecto alojado, no ejecutan migraciones remotas y no sustituyen una revisión integral de RLS.

## Lote 24: política de seguimientos fuera de MainActivity

La Activity elegía la última acción del cuidador en más de un lugar y contenía la lógica que clasifica alertas pendientes. Se extrajo esa política pura a `AlertFollowupPolicy`: orden por evento, prioridad del seguimiento más reciente y estados que resuelven o mantienen la alerta. Las pantallas usan la misma política tanto en el resumen del cuidador como en el historial; el filtro de alertas locales conserva sus reglas.

Tres pruebas unitarias cubren orden independiente del input, override por acción resuelta y estados pendientes de caídas sin confirmar. Validación del 3 de noviembre de 2026: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; **114 unitarias y 94 instrumentadas**, cero fallos, errores u omitidas, en Medium_Phone_API_37.0 (AVD, API 37). `git diff --check` no reportó errores de whitespace; Git avisó que convertirá finales LF/CRLF en algunos archivos. Ver `evidencia/lote-24-validacion.log`. La extracción reduce lógica de Activity, pero las pantallas completas aún no están separadas y requieren continuar el refactor.

## Lote 25: vista de dispositivos compartidos fuera de MainActivity

Se extrajo la subpantalla del cuidador a `CaregiverDeviceLinksUi`: estado del vínculo, equipos registrados, cámaras, señal más reciente, límites de administración y botones de navegación/actualización. La clase recibe datos y callbacks; consulta y mutación de estado permanecen en `MainActivity`, sin cambiar el contrato de sincronización ni la autorización.

Validación del 3 de noviembre de 2026: compilación audit correcta y ejecución de `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon`; XML de resultados confirma **114 unitarias y 95 instrumentadas**, sin fallos, errores ni omitidas, en Medium_Phone_API_37.0 (AVD, API 37). La prueba instrumentada nueva renderiza equipos compartidos y verifica que el botón de eventos de cámara delega al callback. No se ejecutaron alertas reales ni cambios en Supabase remoto. Falta revisar esta vista en teléfonos y probar cuentas vinculadas reales. Ver `evidencia/lote-25-validacion.log`. H06 sigue parcial; quedan pantallas y flujos mayores por extraer de MainActivity.

## Lote 26: auditoría remota Supabase de solo lectura

Se inspeccionaron el ledger de migraciones, tablas `public`, asesores de seguridad/rendimiento, grants, políticas de `profiles` y `medication_intakes`, triggers y definiciones de RPC. El remoto permite a `authenticated` insertar y actualizar `profiles.role`, aunque el modo del teléfono debe ser local. En `medication_intakes` conviven las políticas antiguas y las nuevas de INSERT/UPDATE, y no existe el trigger `guard_medication_intake_action`; el esquema remoto tiene `actor_user_id`, por lo que la atribución puede modificarse mediante escritura propia. El checkout ya contiene las migraciones de corrección `20261103070000_profiles_role_read_only.sql` y `20261102133804_medication_actor_authorization.sql`, con pruebas locales documentadas, pero el ledger remoto termina en `20261026004740` y no incluye esas migraciones.

Los dos avisos RLS sin políticas corresponden a `camera_device_invites` y `camera_device_links`; las consultas verificaron que `anon` y `authenticated` no tienen grants directos, de modo que el acceso directo queda denegado. Ocho RPC SECURITY DEFINER listadas por el asesor usan `search_path=''` y sus definiciones exigen sesión y validan propiedad, vínculo o código de un solo uso según la operación; se usan como puertas de acceso del cliente. El asesor de Auth reporta deshabilitada la protección de contraseñas filtradas. Los avisos de rendimiento incluyen cuatro FK sin índice, cuatro índices nunca usados y las políticas duplicadas de tomas.

Este lote inició como solo lectura y documentó el estado antes de corregirlo en Lote 27; no leyó filas personales ni envió alertas. Codex Security no estaba disponible cuando se redactó este lote; la auditoría estática independiente se inició más adelante y sus hallazgos se documentan por separado. Ver `evidencia/lote-26-supabase-readonly.md`. En ese momento seguían pendientes la publicación de `medication-label`, su cuota remota y activar la protección de contraseñas filtradas en Auth; los dos primeros se abordaron en Lotes 27–28. Las pruebas en dispositivos físicos siguen separadas.

## Lote 27: mitigaciones RLS remotas autorizadas

Tras revisar el SQL y obtener autorización explícita por cada conjunto, se aplicaron al proyecto Supabase conectado tres migraciones ya probadas localmente: `medication_actor_authorization`, `profiles_role_read_only` y `phone_motion_device_ownership`. Las operaciones no borraron ni reescribieron filas. Antes de la última migración se verificaron cero filas sin pareja propietario/dispositivo.

Verificación remota posterior: `profiles.role` no tiene permisos INSERT/UPDATE para `authenticated`, mientras `full_name`/`preferences` conservan los permisos esperados; `medication_intakes` tiene una política INSERT y una UPDATE y el trigger `medication_intakes_guard_action` está presente; la FK compuesta de `phone_motion_status` está validada y persisten cero filas huérfanas. El ledger registra las tres migraciones. Los asesores dejaron de reportar políticas duplicadas de tomas; siguen reportando ocho RPC SECURITY DEFINER (revisadas con `search_path=''` y controles de sesión/propiedad), dos tablas de cámara con RLS sin políticas pero sin grants directos a anon/authenticated, protección de contraseña filtrada deshabilitada y avisos de índices. Ver `evidencia/lote-27-supabase-migrations.md`.

No se dispararon alertas. Las pruebas SQL contra Auth/perfiles/tomas/dispositivos se mantienen en la base local; no se ejecutaron writes funcionales con cuentas reales. Los cambios remotos están aplicados, pero la suite de auditoría no prueba aceptación entre cuentas físicas.

## Lote 28: disponibilidad remota de lectura de etiquetas

La app llama `/functions/v1/medication-label` con sesión autenticada y conserva una opción local como fallback. La inspección inicial encontró la función Edge y la cuota ausentes; tras autorización se aplicó `medication_scan_quota` y se desplegó `medication-label` versión 1 con `verify_jwt=true`. La tabla `tzinda_private.medication_scan_limits` tiene RLS y no es accesible a `authenticated`; la reserva solo se puede ejecutar como `service_role`. El handler local cuenta con 15 pruebas y la cuota con 17 assertions aprobadas.

El conector no ofrece una herramienta para administrar Edge secrets. La lectura requiere `NVIDIA_API_KEY` configurada desde Supabase Dashboard; su presencia no se pudo comprobar y no se ejecutó inferencia. Queda pendiente configurar/confirmar ese secreto y aceptar una lectura con imagen de prueba no sensible. No se enviaron alertas. Ver `evidencia/lote-28-medication-label-remote.md` y [guía de la función](../medication-label-proxy.md).

## Lote 29: correcciones de navegación, títulos y etiquetas accesibles

La revisión visual identificó que la pestaña **Alertas** abría primero **Signos vitales**, aunque el contenido principal se titula “Historial y registros”. Ahora abre **Notificaciones** y conserva “Signos vitales” como opción elegible. Los botones de selección de perfil anuncian nombres accesibles que distinguen cada modo y el perfil usa un título de contenido distinto al de la barra superior.

Validación del 8 de noviembre de 2026: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; **114 pruebas unitarias y 98 instrumentadas**, sin fallos, errores u omitidas, en Medium_Phone_API_37.0 (AVD, API 37). Las pruebas instrumentadas verifican pestaña inicial, opción de signos vitales, nombres de perfiles y título único del perfil. Se usó el APK aislado sin credenciales; no se activó SOS, monitoreo, permisos ni servicios remotos. Las capturas de los siete pasos inspeccionados están en [revisión visual y capturas](evidencia/ux/lote-29-ui.md); la primera ejecución tuvo un fallo de selector ambiguo en la prueba, corregido antes de esta ejecución aprobada. Pendientes: TalkBack, fuentes ampliadas, pruebas físicas y recorridos manuales de cuidador/cámara.

## Lote 30: exclusión de datos sensibles de backups y autenticación de API heredada

Las reglas de copia ahora excluyen `sharedpref` del backup heredado, del backup en nube y de la transferencia entre dispositivos; los datos permanecen en el almacenamiento local de la app. La variante `TzindaUnoQLocalApi` ahora requiere Bearer para leer estadísticas/lecturas y escribirlas; sin un token válido de 32 bytes configurado en `TZINDA_API_TOKEN`, falla con 503. `/health` permanece público para salud del servicio y `/` entrega solo una página genérica; los datos de la API requieren Bearer. El ZIP de la variante fue reconstruido con `api_auth.py` y verificado para integridad y archivos esperados. Quité de `.env.local` ignorado una variable NVIDIA sin lector en el código y corregí `.env.example` para indicar que las credenciales/modelo NVIDIA pertenecen a secretos de Edge, nunca al APK. El secreto remoto aún requiere configuración o confirmación desde Supabase Dashboard.

El primer test host de la API heredada reveló que las conexiones SQLite no se cerraban explícitamente y Windows retenía la base temporal; también mostró que el cierre del servidor de test debía preceder al borrado de la base. Ambas variantes UNO Q usan ahora un contexto que finaliza la transacción y cierra la conexión. Validación del 8 de noviembre de 2026: suites Python **8/8** (LocalApi) y **9/9** (Pulsera); Android `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`, con **114 unitarias y 99 instrumentadas**, cero fallos, errores u omitidas, en AVD API 37. La prueba instrumentada comprueba las tres exclusiones XML incluidas en recursos compilados. Evidencia: `evidencia/lote-30-validacion.log`. La autenticación no cifra HTTP: credenciales y telemetría siguen expuestas en una LAN compartida; se documenta y se aborda en el siguiente lote. No se probaron backups Android reales ni UNO Q físico, y no se enviaron alertas.

## Lote 31: conexión Wi-Fi UNO Q mediante HTTPS fijado

Se sustituyeron las dos conexiones HTTP del Android (prueba manual y sondeo del monitor) por un único cliente HTTPS. La app comprueba la huella SHA-256 del certificado, conserva la validación del nombre/IP del certificado, rechaza HTTP, parámetros/fragmentos y redirecciones. Dirección, huella y token cifrado se validan y guardan con una sola escritura; cada lectura usa una instantánea coherente. El manifiesto normal y el recurso principal bloquean tráfico en claro; la variante debug permite únicamente `127.0.0.1` para las pruebas HTTP locales de Supabase.

Ambos servidores UNO Q usan `api_tls.py`, TLS 1.2 como mínimo y HTTPS en el puerto 8081. Sin certificado y clave, o si su pareja no se puede cargar, la app falla antes de inicializar SQLite o abrir un listener; no existe fallback HTTP. Los dos ZIP se reconstruyeron conservando los archivos del paquete; el ZIP de la pulsera ahora incluye `api_auth.py`, que faltaba, y ambos incorporan `api_tls.py`. Las guías documentan SAN para la IP, instalación privada de la clave, confirmación local de huella y renovación fuera de banda. La compatibilidad del firmware existente con HTTPS queda pendiente; la API SQLite ahora rechaza intencionalmente HTTP.

Validación: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; XML de resultados confirma **118 unitarias y 101 instrumentadas**, cero fallos, errores u omitidas, en Medium_Phone_API_37.0 (AVD, API 37). Las pruebas Python pasan **11/11** (LocalApi) y **12/12** (Pulsera): incluyen configuración TLS incompleta, mínimo TLS 1.2 y ausencia de arranque HTTP cuando falta TLS. Los dos ZIP pasan `testzip()` y contienen `main.py`, `api_auth.py`, `api_tls.py`, `app.yaml` y README. No se contactó Supabase ni se enviaron alertas. Ver `evidencia/lote-31-validacion.log`. Pendiente en dispositivo: instalar certificado/clave y token solo en el UNO Q, validar SAN/huella por canal local, verificar HTTPS del firmware emisor y probar lecturas/reconexión sin provocar alertas.

## Lote 32: protección de identidad del periférico BLE

El cliente Android ya no conecta al primer anuncio con el UUID público de Tzinda. Solo intenta conectar un periférico cuyo estado Android sea `BOND_BONDED`, vuelve a comprobarlo al completarse la conexión y orienta al usuario a emparejar `Tzinda` desde Ajustes de Android. No solicita el vínculo automáticamente. Las características BlueZ declaran `encrypt-read` y `encrypt-notify`, por lo que lecturas y notificaciones requieren enlace Bluetooth cifrado. La política comparte una lista única para la propiedad GATT y `GetManagedObjects` del servicio.

Validación: `:app:testDebugUnitTest :app:connectedDebugAndroidTest -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; XML confirma **119 unitarias y 101 instrumentadas**, cero fallos, errores u omitidas, en AVD API 37. Python: **11/11** (LocalSQLite) y **13/13** (Pulsera); se comprueba que las banderas cifradas sean las que publica el servidor. Los ZIP pasan `testzip()`; LocalSQLite contiene 5 entradas y Pulsera 41, incluido `bricks/tzindable/app/gatt_security.py`. No se ejecutó monitoreo ni alerta real. Evidencia: `evidencia/lote-32-validacion.log`.

Queda pendiente emparejar un UNO Q físico y comprobar lectura/notificación cifrada, reconexión y comportamiento con Android real. El protocolo de valores no transporta marca de tiempo/sequence de dispositivo, por lo que la actualidad/replay de datos legítimamente vinculados no queda probada. La resistencia MITM depende del modo de emparejamiento efectivo del UNO Q/Android y también debe verificarse físicamente; no se afirma autenticación con comparación numérica.

## Lote 33: límites de recursos UNO Q y verificación final

La revisión del API UNO Q encontró que una conexión TLS lenta podía bloquear el ciclo de aceptación y que clientes lentos podían ocupar lectores HTTP indefinidamente antes/después de autenticación. Ambas variantes ahora usan `BoundedThreadingHTTPServer`: cuatro conexiones de trabajo simultáneas, cola de aceptación de ocho, handshake TLS de cinco segundos, timeout inactivo de cinco segundos y plazo total de lectura de 15 segundos compartido por la línea, encabezados y cuerpo. Al agotarse los slots se cierra la nueva conexión; se conserva el límite existente de 32 KiB para el JSON. Las pruebas loopback cubren handshake diferido, expiración, saturación/recuperación, cuerpo lento sin escritura y rutas autenticadas. Un revisor encontró que los ZIP no incluían el helper importado; ambos paquetes se reconstruyeron y ahora comparan byte por byte con los módulos fuente.

Validación final: `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`: **119 unitarias y 101 instrumentadas**, cero fallos, errores u omitidas, en Medium_Phone_API_37.0 (AVD, API 37). Python: **15/15** (LocalSQLite) y **17/17** (Pulsera), además de `compileall`; ZIP íntegros, 6 y 41 entradas respectivamente, con `api_server.py`, `api_auth.py`, `api_tls.py` y `main.py` idénticos a sus fuentes. En Supabase local se aplicaron solo las migraciones pendientes sin reinicializar la base; pgTAP pasó **79/79** y los scripts transaccionales `care_features_rls.sql`, `medication_rls.sql` y `rls_isolation.sql` terminaron correctamente en `ROLLBACK`. La configuración temporal de puertos libres se restauró.

Tras autorización explícita, Supabase remoto registró `20261109040215_supporting_foreign_key_indexes`; una consulta de solo lectura confirmó los cinco índices, y el asesor dejó de reportar claves foráneas sin índice. Los avisos de índices sin uso son esperables inmediatamente después de crearlos y no motivan borrarlos. Persisten la advertencia de protección de contraseñas filtradas deshabilitada y la clave Edge `NVIDIA_API_KEY` no verificable desde el conector. No se leyeron filas personales, no se ejecutó inferencia ni se enviaron alertas, SMS o llamadas. Ver [validación del Lote 33](evidencia/lote-33-validacion.log), [revisión remota Supabase](evidencia/lote-33-supabase-readonly.md), [hallazgos de seguridad](evidencia/lote-33-seguridad.md), [compilación Android](evidencia/lote-33-android-build.log) y [pruebas Python](evidencia/lote-33-python-localapi.log) / [pulsera](evidencia/lote-33-python-pulsera.log).

No se probó hardware físico. Pendiente: UNO Q real para handshake/renovación TLS y pairing BLE; dos teléfonos para vínculo, revocación y sincronización offline; SIM/llamadas/SMS, GPS, reinicio, batería, TalkBack y tamaños de fuente. H15 sigue abierto hasta definir y validar identidad y frescura del periférico.

## Lote 34: diálogo de invitación de vinculación hasta “Listo”

Se corrigió el diálogo que muestra el QR y el código de ocho caracteres: al detectar que la otra cuenta o cámara se vinculó, ya no se cierra automáticamente a los pocos segundos. El estado de éxito queda visible junto con el código y la persona puede transcribirlo con calma. El diálogo se cierra únicamente al pulsar **Listo**; también se deshabilitaron el botón Atrás y el toque exterior para evitar perder el código accidentalmente. Al cerrar después de un vínculo confirmado, la app actualiza la pantalla subyacente.

Validación del 10 de noviembre de 2026: `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug -PtzindaAudit=true --no-daemon` terminó `BUILD SUCCESSFUL`; **119 pruebas unitarias y 101 instrumentadas**, sin fallos, errores ni omitidas, en Medium_Phone_API_37.0 (AVD, API 37). `git diff --check` pasó. El modo de auditoría no usa configuración Supabase; no se enviaron alertas ni se hicieron cambios remotos. La aceptación física de escaneo, transcripción manual y cierre con **Listo** entre dos teléfonos sigue pendiente. Ver [evidencia del Lote 34](evidencia/lote-34-vinculacion-dialogo.md).
