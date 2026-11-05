# CalefaccionDroid

En el móvil se llama **Calefacción**. Controla la calefacción de varias casas con termostato
**Netatmo** desde una sola pantalla, con widgets, mosaicos de Ajustes rápidos y «Tengo frío».

Versión para Android de [Calefacción Netatmo](https://github.com/baltamir1978/calefaccion-netatmo)
(iOS), escrita desde cero en Kotlin + Jetpack Compose. Funciona sin Google Play (GrapheneOS).

**Estado: 0.2** (5-nov-2026), sin probar todavía contra las casas de verdad. Ver `CLAUDE.md`.

## Qué hace

- **Todas las casas en una pantalla**: temperatura actual, objetivo y caldera; control
  `+ / objetivo / −` como el termostato.
- Modos (programación, ausente, antihielo), **ajuste manual con duración** y vuelta al horario.
- Horarios: activar, crear, duplicar, renombrar y borrar; vista semanal y editor de temperaturas.
- Pilas de termostatos y válvulas, y gráficas de uso (temperatura y horas de caldera).
- **Avisos** de pilas bajas y termostato sin conexión.
- **«Tengo frío»**: +1 °C sobre la temperatura actual durante 2 horas, sin abrir la app, desde un
  widget, un mosaico de Ajustes rápidos o un acceso directo. Con varias casas, nunca elige sola.
- **Comparte la sesión de Netatmo con [Tiempo ES](https://github.com/baltamir1978/TiempoDroid)**,
  que lee con ella la estación meteorológica: Netatmo cambia el `refresh_token` en cada
  renovación, así que dos apps no pueden renovar la misma sesión por su cuenta. Sólo se la da a
  Tiempo ES firmada con su clave.
- Español, inglés y francés.

## Puesta en marcha

1. En [dev.netatmo.com/apps](https://dev.netatmo.com/apps), la app de Netatmo Connect tiene que
   tener como **Redirect URI** `calefaccion-netatmo://oauth-callback` (la misma que la de iOS).
2. Pon su `client_id` y `client_secret` en **Ajustes** dentro de la app, o en `local.properties`:
   ```
   netatmo.clientId=…
   netatmo.clientSecret=…
   ```
3. Inicia sesión. La sesión pide también `read_station` (para Tiempo ES): si la iniciaste con la
   0.1, vuelve a entrar.
4. Compila:
   ```sh
   Tools/instalar-entorno.sh      # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
   ./gradlew testDebugUnitTest assembleRelease
   ```

Cualquier `client_secret` dentro de una app se puede sacar del binario: vale para uso personal,
no para distribuirla.

## Licencia

MIT, como el original. No afiliado a Netatmo ni a Legrand.
