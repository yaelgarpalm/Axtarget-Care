# Auditoría integral de TzindaApp

Inicio: 1 de noviembre de 2026 (America/Mexico_City). Rama: `codex/auditoria-integral-tzinda`.
Base: `0ec5a5ce1035487452d63b99a1cbb12592d1b5e5`. Auditoría e implementación **en curso**.

El checklist aportado es una lista de hipótesis, no evidencia de ejecución. Se prioriza la solicitud actual de implementar sobre su antiguo prompt de auditoría sin cambios. No se trabaja en Tzinda Band V1.

## Reglas y evidencia

- `[x]`: corrección implementada con comprobación indicada; no equivale a aceptación en teléfonos.
- `[~]`: validación parcial o pendiente de emulador, dispositivos o servicios.
- `[ ]`: trabajo pendiente.
- No se han enviado SMS/llamadas ni alertas reales. Las consultas remotas son de metadatos y solo lectura.
- APK de pruebas aislado: `-PtzindaAudit=true`, paquete `com.guardian.tzindaapp.v2.audit`, sin configuración de Supabase/NVIDIA. No sustituye la app instalada del usuario.
- La compilación inicial y tareas unitarias terminaron correctamente (Gradle reutilizó resultados). Las correcciones requieren una nueva ejecución; no se atribuye esta primera salida a pruebas nuevas.

## Inventario funcional y datos

| Área | Entradas y fuente | Verificación pendiente |
|---|---|---|
| Cuenta y perfil | MainActivity, SupabaseBackend, EncryptedSessionStore, AppStore; Auth y profiles | Recuperación, cambio de cuenta, identidad frente a modo del teléfono |
| Navegación | TzindaNavigationPolicy y MainActivity; Inicio, Alertas, Salud, Supervisión; cámara: Inicio, Alertas, Vincular, Ajustes | Atrás, restauración, accesibilidad y permisos |
| Contactos y emergencia | AppStore/ContactsRepository, SQLite sync_contacts, GuardianMonitorService | Retención, cancelación, regresión sin SMS real |
| Historial y biometría | LocalTelemetryDatabase; JSON de 100 eventos visible en AppStore; fall_events, measurements | Fuente canónica de historial y frescura |
| Medicamentos | MedicationRepository/Database/UI/ReminderScheduler; medications y medication_intakes | Caché, concurrencia, revocación, reinicio |
| Cuidados | CareFeatureStore; JSON records/deleted; care_records, alert_followups | Ámbito de cuenta/persona, sincronización y estado geográfico |
| Dispositivos | IDs locales, devices, BLE y Wi-Fi UNO Q | Registro frente a conectividad, identidad del periférico |
| Cámara | CameraFallController, enlaces RPC, CameraFallRealtime | Consentimiento, dos teléfonos y revocación |
| Ubicación | Servicio Android, sync_locations, location_history/caregiver_locations | Consentimiento y separación entre uso local y publicación |
| Sincronización | SupabaseSyncRunner, JobScheduler, colas SQLite y JSON | Estado agregado, reintentos, sesiones concurrentes |

## Hallazgos del checklist

| ID | Estado | Evidencia / siguiente trabajo |
|---|---|---|
| H01 | [~] Corrección local verificada; dos teléfonos pendiente | Modo local no publica profiles.role ni se reemplaza al descargar perfil; 5 pruebas unitarias y 4 instrumentadas nuevas |
| H02 | [x] Corrección local verificada | 404 ya no se convierte en []; descarga paginada, parseo completo, transacción; conservar acciones pendientes |
| H03 | [~] Agregación verificada; cobertura integral pendiente | Estado agregado de telemetría, medicamentos, cuidados/borrados, perfil y registro; falta comprobar escenarios completos de cuenta |
| H04 | [x] Corrección local verificada | Estado pending/error/confirmed; únicamente confirmación permite envío dependiente de dispositivos |
| H05 | [~] Cuenta, caché y propiedad de cuidados verificadas localmente | Lotes 4, 10 y 11: A → B → A, callbacks por vínculo y dueño explícito; pendientes recuperación heredada y revocación integral en dos teléfonos |
| H06 | [ ] Confirmado | MainActivity concentra más de 4.600 líneas; extracción gradual |
| H07 | [ ] En revisión | Rutas y títulos por perfil, navegación secundaria |
| H08 | [ ] Confirmado | JSON visible limitado a 100 entradas |
| H09 | [~] Propiedad y versiones corregidas; sincronización integral pendiente | Lotes 10–11; faltan descarga propia completa, recuperación heredada y borrado idempotente |
| H10 | [x] Texto verificado por prueba unitaria | UI usa «Sin pendientes locales» y conserva por separado última confirmación remota |
| H11 | [x] Corrección local verificada | Estado geográfico en zone_runtime; configuración conserva su versión y cola |
| H12 | [~] Inspección remota | Tablas de medicamentos sí existen; historial remoto omite migraciones que el esquema refleja parcialmente |
| H13 | [~] Android y RLS local verificados; despliegue remoto pendiente | Guardas/textos, siete pruebas de autorización, cuatro HTTP y 31 SQL. Migración preparada; remoto sigue con las políticas anteriores |

## Lote 1: integridad de descarga y registro

Problemas: errores HTTP se interpretaban como borrado; consultas limitadas a 500/1000 filas disparaban depuración de caché; acciones pendientes del cuidador se eliminaban; confirmación de envío podía marcar una edición posterior como sincronizada; registro de dispositivo fallido quedaba confirmado.

Archivos: `SyncSafety.kt`, `MedicationRepository.kt`, `MedicationDatabase.kt`, `SupabaseBackend.kt`, `SupabaseSyncJobService.kt`, `app/build.gradle.kts`, pruebas `SyncSafetyTest` y `MedicationDatabaseTest`.

Solución: páginas ordenadas por ID hasta respuesta vacía, rechazo de respuesta malformada/repetida, descarga y validación de ambas colecciones antes de aplicar una transacción, depuración solo de filas confirmadas, acuse condicionado a la versión enviada, propagación del error de envío del cuidador y estado explícito de registro.

Pruebas: errores 401/403/404/409/500/timeout controlados, fila inválida, paginación con límite inferior al solicitado, página repetida, respuesta vacía válida, fallo y recuperación de registro; SQLite: conservación de pendientes/fallidos, acuse atrasado, aislamiento de propietario, reapertura.

Resultado: 100 pruebas unitarias sin fallos (incluidas 6 de SyncSafetyTest); 7 pruebas instrumentadas de MedicationDatabaseTest sin fallos, en paquete audit y emulador API 37. Compilaron debug y APK de instrumentación. Ver `evidencia/lote-1-instrumentadas.log`. Riesgos: páginas no constituyen una transacción remota; cambios concurrentes se reconcilian en la siguiente ejecución. Falta validación con Supabase de pruebas y teléfonos. Un esquema sin actor_user_id se informa como error y conserva datos; ya no se oculta la incompatibilidad.

## Supabase remoto

Proyecto identificado mediante la URL configurada: `vhppdgbmmtfrbzocitob`. Evidencia de inventario, migraciones y asesores en `evidencia/`. Las 18 tablas públicas observadas tienen RLS. Esto no demuestra aislamiento correcto: falta ejecutar casos negativos por actor en una base de pruebas.

El asesor reporta funciones SECURITY DEFINER ejecutables por autenticados, dos tablas de cámara sin políticas directas y protección de contraseñas filtradas desactivada. Las funciones y tablas pueden depender deliberadamente de RPC; hay que revisar sus controles antes de cambiar permisos. [Asesor de funciones](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable), [protección de contraseñas](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

No se ha aplicado ninguna migración remota. Al inicio Docker no respondía. En el lote 6 se inició el motor y se creó una base Supabase local; ver pruebas de reproducción y RLS. Hay emulador Android, sin dispositivo físico conectado al inicio.

## Pendientes de aceptación física

- [ ] Dos teléfonos: cuenta protegida y cámara sin alteración del rol remoto; cuidador vinculado y revocado.
- [ ] Offline → editar → cerrar proceso → abrir → reconectar, comprobando filas por ID.
- [ ] Permisos Android, reinicio, batería, recordatorios y GPS.
- [ ] BLE/Wi-Fi con equipos autorizados, reconexión y lecturas antiguas.
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

Riesgos y pendientes: no se ejecutó migración ni actualización de roles remotos; clientes anteriores aún pueden escribir su metadato. H05 sigue abierto: conservar y aislar datos al cambiar de cuenta, incluyendo tareas en curso y recordatorios. El contrato de acceso del cuidador y la recuperación de sesión aún requieren correcciones y validación específica.

## Lote 4: conservación e aislamiento entre cuentas

Problema: `bindLocalDataToUser` borraba SQLite y preferencias de la cuenta anterior, incluyendo cambios sin subir. Las instancias antiguas y los recordatorios tampoco tenían un ámbito persistente explícito.

Solución: `AccountStorage` mantiene intactos los archivos heredados para su propietario inicial y asigna archivos privados independientes a las cuentas siguientes mediante un identificador derivado del userId. El puntero de cuenta activa se publica después de preparar su propiedad. Cada store y helper SQLite captura su ámbito; una tarea antigua conserva A y no cambia silenciosamente a B. Al volver a A se reabren sus archivos, sin descargar para recuperar datos pendientes. No se sustituyó SQLite por otra tecnología ni se ejecutó una migración remota.

La actividad vuelve a abrir los repositorios al cambiar de cuenta, limpia borradores de pantalla, detiene recursos de la cuenta anterior y verifica la cuenta antes de mostrar datos al arrancar. El estado guardado de navegación incluye el ámbito. Las sesiones cifradas usan exclusión entre instancias, renovación condicionada a la sesión esperada y cierre limitado al mismo usuario. Las peticiones de una instancia de backend antigua rechazan una sesión de otro usuario. Los recordatorios nuevos incluyen ámbito en URI y extras; el receptor descarta otro ámbito. La exclusión de bases en backup cubre también sus nuevos nombres.

La recuperación de contraseña ya consulta el usuario con el token provisional sin escribir una sesión incompleta. **Sigue pendiente** vincular el callback a una solicitud de recuperación iniciada por el usuario; no se declara resuelto el candidato de sustitución de sesión.

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

H05/H09 continúan parcialmente abiertos: falta la recuperación guiada de registros heredados puramente locales sin propietario verificable, la descarga completa de contenido propio entre instalaciones y la confirmación idempotente de una eliminación cuya respuesta se perdió. Cero filas en DELETE se conserva como pendiente hasta resolver esa ambigüedad. También sigue pendiente la aceptación en dos teléfonos y la revocación remota integral; este lote no declara toda la sincronización completa.

## Lote 12: recuperación de contraseña vinculada al teléfono

El callback exportado aceptaba access_token/refresh_token proporcionados en el enlace y sustituía la sesión activa. Ahora la solicitud genera un verificador aleatorio de 256 bits, envía su reto S256 y guarda el flujo cifrado con Android Keystore fuera del backup. Solo se acepta la URI configurada con un único parámetro code, sin fragmentos ni otros parámetros. El intercambio usa el verificador local, comprueba el correo solicitado y consume el flujo una sola vez. Los enlaces de tokens directos, no solicitados, duplicados, vencidos o para otro correo no sustituyen la sesión.

La autorización recuperada se guarda por separado durante un máximo de una hora y solo sirve para actualizar la contraseña mediante el token recuperado. No se enlazan archivos de cuenta ni se reemplaza la sesión activa. Tras guardar se solicita iniciar sesión normalmente. Cancelar, iniciar sesión o pedir otro enlace invalida el flujo anterior. Un intercambio cuya respuesta se pierde requiere solicitar otro enlace; no se reutiliza una autorización ambigua.

La revisión independiente detectó una regresión de ciclo de vida: una respuesta completada después de recrear la actividad no llegaba a la pantalla nueva. El intercambio ahora sobrevive a la recreación y la actividad observa cambios del almacén cifrado al reanudarse y durante su actualización visible. El callback se retira del Intent para no repetirlo. La prueba demora la respuesta HTTP, recrea la actividad real y comprueba que la instancia visible muestra Nueva contraseña conservando la cuenta anterior.

Verificación final: **106 unitarias y 67 instrumentadas, cero fallos/errores/omitidas**, compilación audit correcta. Diez pruebas nuevas cubren petición/código S256, persistencia cifrada, sesión separada, callback malicioso, caducidad, repetición, cambio de solicitud, cancelación, errores de correo/intercambio, ausencia de refresco con la cuenta activa y recreación. Se usó ANDROID_SERIAL=emulator-5580; no se ejecutó nada en el teléfono físico conectado. Evidencia: `lote-12-validacion-cierre.log` y resúmenes JSON. Se conservan los intentos anteriores: error de compilación por método protegido del test y fallo de ActivityScenario al cambiar la identidad del Intent; corregidos sin retirar la prueba.

Además se ejecutó el protocolo real contra Auth de Supabase local y Mailpit: correo capturado localmente, redirect con code sin tokens, intercambio PKCE, actualización, rechazo de contraseña anterior, acceso con la nueva y rechazo de reutilización del código. Ocho comprobaciones aprobadas. Se conserva un usuario ficticio @example.invalid exclusivamente en la base local. No hubo correos externos, alertas ni cambios remotos. Script y resultado están retenidos en Codex Security, scan cd27264a-1f0e-404a-9563-2e495747963d, `artifacts/recovery/local_pkce.py` y `local_pkce.result.json`.

Contrato consultado: [flujo PKCE de Supabase](https://supabase.com/docs/guides/auth/sessions/pkce-flow) y [cliente oficial Auth](https://github.com/supabase/auth-js/blob/master/src/GoTrueClient.ts). Compatibilidad: solicitar un enlace nuevo desde la versión corregida y abrirlo en el mismo teléfono. Se revisó estáticamente que el hook de correo del repositorio conserva token_hash, type=recovery y redirect_to; su proveedor remoto y los clientes de correo en teléfonos físicos siguen pendientes de aceptación. El informe global de seguridad y la auditoría integral continúan abiertos.
