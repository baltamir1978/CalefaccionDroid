# CalefaccionDroid — contexto del repositorio

Versión para Android de **Calefacción Netatmo** (CalefaccionNetatmo, iOS), escrita **desde cero en
Kotlin + Jetpack Compose**. Lo común a las apps de Android (firma, `versionCode`, secretos,
distribución, código compartido) está en el `CLAUDE.md` de la carpeta de proyectos; aquí va lo
propio de esta.

## Estado (5-nov-2026)

**0.1: compila, pasan 7 pruebas; sin probar contra Netatmo ni en el móvil** (aquí no hay
credenciales). APK firmado en `~/Documentos/Calefaccion-0.1.apk`.

Hecho, traducido de la app de iOS:
- Inicio de sesión con una pestaña del navegador (Custom Tabs), sin AppAuth: el flujo de Netatmo
  es código de autorización sin PKCE, no hace falta más. **Usa la misma Redirect URI que la app de
  iOS** (`calefaccion-netatmo://oauth-callback`), así no hay que tocar dev.netatmo.com.
- Credenciales: en Ajustes dentro de la app (como en iOS) o en `local.properties`
  (`netatmo.clientId`, `netatmo.clientSecret`), nunca en el repo.
- Tokens y credenciales cifrados con una clave AES del Android Keystore (`data/Stores.kt`), sin
  Tink ni `EncryptedSharedPreferences`. Escritura atómica; la renovación va de una en una
  (`Mutex`) y guarda siempre el `refresh_token` nuevo.
- Inicio con una tarjeta por casa (actual, `+ / objetivo / −` con 0,9 s de espera antes de mandar,
  caldera, modo, ajuste manual con «Volver al horario», pilas bajas, horario activo); detalle con
  horarios (activar, crear con la semana tipo, duplicar, renombrar, borrar salvo el activo), vista
  semanal, editor de temperaturas, pilas y gráficas de uso dibujadas con Canvas (sin Vico: dos
  gráficas sencillas no lo justifican); ajustes (credenciales, cuenta, duración del ajuste
  manual, avisos, casas visibles). Textos en es/en/fr sacados del catálogo de iOS
  (`Tools/strings-desde-ios.py`). Colores de iOS: naranja del sistema y fondos agrupados.
- Avisos de pilas bajas y sin conexión con WorkManager **cada hora** (ver los webhooks abajo).
- «Tengo frío» (+1 °C sobre la actual, 2 h, al medio grado) desde el widget, los mosaicos y los
  accesos directos, sin abrir la app. Widgets: Estado (naranja cuando calienta) y Tengo frío.
  Mosaicos: dos de «Tengo frío» y dos de «Ausente» (casa 1 y 2 por orden alfabético), cada uno con
  el nombre de su casa. **Ninguno elige casa por sorteo**: el widget pregunta al ponerlo si hay
  varias; con una, va directo.
- Icono: el PNG de iOS tal cual (`Tools/icono-desde-ios.sh`, sirve para las otras apps).

Falta: probarlo con las casas de verdad; la autenticación compartida con TiempoDroid.

## La especificación es el original

`../CalefaccionNetatmo` (`github.com/baltamir1978/calefaccion-netatmo`): leer su `README.md`. Qué
hace, con la API Energy de Netatmo:

- **Varias casas en una pantalla**: temperatura actual, objetivo y caldera; control `+ / objetivo /
  −` como el termostato.
- Modos del hogar (programación, ausente, antihielo); **ajuste manual con duración** y vuelta al
  horario.
- **Avisos** de batería baja y de termostato sin conexión, con refresco periódico en segundo plano.
- Horario semanal (barra de 24 h por día), editor de temperaturas de un horario y gestión de
  horarios (crear, duplicar, renombrar, borrar; no deja borrar el activo).
- Batería de termostatos y válvulas, gráficas de consumo, ocultar casas, 3 idiomas (es, en, fr) y
  accesibilidad completa.
- **«Tengo frío»**: sube el objetivo 1 °C sobre la temperatura actual durante 2 horas, sin abrir la
  app (Siri, widget y Centro de Control).

**Regla del original que se mantiene:** con dos casas, ningún widget, control o atajo elige casa
por su cuenta; si no hay una configurada, pide que se elija. Con una sola casa, va directo.

## Cómo se hace en Android

| En iOS | Aquí |
|---|---|
| `AuthenticationServices` + `calefaccion-netatmo://oauth-callback` | **AppAuth** con Custom Tabs y un esquema propio (`calefacciondroid://oauth-callback`). Hay que añadirlo como Redirect URI en dev.netatmo.com, en la misma app de Netatmo o en otra |
| Llavero | DataStore cifrado con una clave del Android Keystore (Tink). `EncryptedSharedPreferences` está obsoleto |
| `BackgroundTasks` + `UserNotifications` | WorkManager periódico (mínimo 15 min) + notificaciones (`POST_NOTIFICATIONS`) |
| Widgets con App Intent («Tengo frío») | Glance con `actionRunCallback`: la acción corre sin abrir la app |
| Widgets de la pantalla de bloqueo | No hay en los móviles Android. Se queda fuera |
| **Controles del Centro de Control** | **Mosaicos de Ajustes rápidos** (`TileService`): «Tengo frío» y el interruptor de modo ausente, uno por casa |
| Siri («tengo frío en La Granja») | Accesos directos de la app; el asistente de Google no da para más |
| Swift Charts | Vico |
| String Catalogs | `strings.xml` por idioma |

## Netatmo va a limitar las consultas (correo del 5-nov-2026)

Netatmo anuncia **webhooks** y, **desde el 1-mar-2027, límites más bajos para las integraciones
que consultan periódicamente** (polling). Los webhooks necesitan un servidor con HTTPS público
que reciba los eventos: **el móvil no puede recibirlos**. Esta app hace pocas consultas: al abrirla
(y como mucho cada 2 min al volver), al tocar algo, y los avisos una vez por hora. Si los límites
nuevos no dan, la salida es un servidor en casa que reciba los webhooks y avise al móvil (ntfy o
UnifiedPush, que funcionan sin Google Play). Decidir con Bruno cuando se publiquen los límites.

## Trampas

- **Tiene que funcionar en GrapheneOS**, sin Google Play. Nada de esta app lo necesita: AppAuth
  abre el inicio de sesión en Vanadium por Custom Tabs, y los avisos van por WorkManager, no por
  FCM.
- **Netatmo cambia el `refresh_token` en cada renovación** y el viejo deja de valer. Guardar
  siempre el nuevo, de forma atómica, o la sesión se pierde. La app de iOS y esta tienen sesiones
  separadas, cada una con sus tokens.
- **La autenticación de Netatmo se compartirá con TiempoDroid** (la estación meteorológica): la que
  se haga antes se copia a la otra.
- El `client_id` y el `client_secret` en `local.properties`, nunca en el repositorio, que es público
  como el original.
