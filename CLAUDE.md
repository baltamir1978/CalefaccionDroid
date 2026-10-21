# CalefaccionDroid — contexto del repositorio

Versión para Android de **Calefacción Netatmo** (CalefaccionNetatmo, iOS), escrita **desde cero en
Kotlin + Jetpack Compose**. Lo común a las apps de Android (firma, `versionCode`, secretos,
distribución, código compartido) está en el `CLAUDE.md` de la carpeta de proyectos; aquí va lo
propio de esta.

## Estado (21-oct-2026)

**Sin empezar.** Va después de Letrasdroid. Se puede hacer entera en el emulador: no necesita
micrófono ni cámara, sólo la cuenta de Netatmo.

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

## Trampas

- **Netatmo cambia el `refresh_token` en cada renovación** y el viejo deja de valer. Guardar
  siempre el nuevo, de forma atómica, o la sesión se pierde. La app de iOS y esta tienen sesiones
  separadas, cada una con sus tokens.
- **La autenticación de Netatmo se compartirá con TiempoDroid** (la estación meteorológica): la que
  se haga antes se copia a la otra.
- El `client_id` y el `client_secret` en `local.properties`, nunca en el repositorio, que es público
  como el original.
