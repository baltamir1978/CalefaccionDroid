"""Genera res/values*/strings.xml a partir del catálogo de iOS (Localizable.xcstrings).
Uso: strings-desde-ios.py <Localizable.xcstrings> <carpeta res>
Las claves de Android se asocian a las frases en español de iOS; las propias de Android van al final."""
import json, re, sys, os
cat = json.load(open(sys.argv[1]))["strings"]
res = sys.argv[2]

FROM_IOS = {
 "app_title": "Calefacción", "settings": "Ajustes", "refresh": "Actualizar", "sign_out": "Cerrar sesión",
 "account": "Cuenta", "loading": "Cargando…", "load_failed": "No se pudo cargar", "retry": "Reintentar",
 "all_hidden": "Todas las casas ocultas", "all_hidden_hint": "Actívalas en Ajustes.",
 "apply_failed": "No se pudo aplicar el cambio", "ok": "OK", "cancel": "Cancelar",
 "boiler_on": "Caldera on", "boiler_off": "Caldera off", "boiler_on_a11y": "Caldera encendida", "boiler_off_a11y": "Caldera apagada",
 "current_temp": "Temperatura actual", "offline": "Sin conexión", "target": "objetivo", "applying": "aplicando…",
 "target_temp": "Temperatura objetivo", "raise": "Subir temperatura", "lower": "Bajar temperatura",
 "mode_schedule": "Programación", "mode_away": "Ausente", "mode_hg": "Antihielo", "mode": "Modo",
 "manual_set": "Temperatura fijada a mano", "until": "hasta las %@", "back_to_schedule": "Volver al horario",
 "low_battery_in": "Batería baja en %@", "low_battery_in_n": "Batería baja en %lld dispositivos",
 "schedule_and_usage": "Programación y uso", "active_schedule": "Horario activo: %@",
 "battery": "Batería", "battery_low": "Batería baja", "battery_medium": "Batería media", "battery_high": "Batería alta", "battery_full": "Batería llena",
 "battery_hint": "Sustituye las pilas de los dispositivos marcados en rojo.", "valve": "Válvula", "thermostat": "Termostato",
 "schedules": "Horarios", "schedule": "Horario", "active": "Activo", "new_schedule": "Nuevo horario", "rename": "Renombrar",
 "duplicate": "Duplicar", "delete": "Borrar", "create": "Crear", "schedule_name": "Nombre del horario", "copy_of": "Copia de %@",
 "delete_schedule_q": "¿Borrar este horario?", "delete_cant_undo": "Se borrará en Netatmo y no se puede deshacer.",
 "schedule_change_failed": "No se pudo cambiar el horario", "rename_schedule": "Renombrar horario", "duplicate_schedule": "Duplicar horario",
 "schedules_hint": "Toca un horario para activarlo. Desliza una fila para renombrarlo, duplicarlo o borrarlo. El horario nuevo arranca con una semana tipo (confort de día, noche a partir de las 23:00).",
 "view_week": "Ver horario semanal", "edit_temps": "Editar temperaturas", "edit_temps_hint": "Cambia la temperatura de cada franja del horario «%@». Las horas no se modifican.",
 "week": "Semana", "week_hint": "Cada barra son las 24 h de un día, con marcas cada 6 h. Toca un día para ver sus franjas.",
 "zones": "Zonas", "no_slots": "Sin franjas horarias", "no_week": "Netatmo no ha devuelto la programación semanal de «%@».",
 "today": "%@, hoy", "zone_comfort": "Confort", "zone_night": "Noche", "zone_away": "Ausente", "zone_hg": "Antihielo",
 "zone_comfort_plus": "Confort +", "zone_eco": "Eco", "zone_n": "Zona %lld", "special": "Especiales",
 "changes_to_apply": "Cambios a aplicar", "confirm_changes": "Confirmar cambios", "save_to_netatmo": "Guardar en Netatmo",
 "overwrite_hint": "Se sobrescribe el horario «%@» en Netatmo. Las horas y las demás franjas no cambian.", "save_failed": "No se pudo guardar",
 "review": "Revisar", "usage_title": "Uso de calefacción (caldera encendida)",
 "usage_hint": "Tiempo que la caldera ha estado encendida por periodo. Netatmo no expone el consumo eléctrico real.",
 "no_usage": "Esta casa no reporta datos de uso.", "no_data_period": "Sin datos en este periodo", "temperature": "Temperatura",
 "day": "Día", "month": "Mes", "boiler_time": "Tiempo de caldera encendida",
 "hours_minutes": "%lld h %lld min", "minutes": "%lld min",
 "connect_netatmo": "Conectar con Netatmo", "login_subtitle": "Controla la calefacción de tus casas desde una sola app.",
 "configure_first": "Configura las credenciales de Netatmo Connect en Ajustes antes de conectar.",
 "save_credentials": "Guardar credenciales", "credentials_saved": "Credenciales guardadas",
 "reconnect_hint": "Si cambiaste la app de Netatmo, vuelve a conectar la cuenta.", "connected": "Conectado", "not_connected": "No conectado",
 "preferences": "Preferencias", "manual_duration": "Duración de ajuste manual",
 "manual_duration_hint": "Cuánto se mantiene una temperatura fijada a mano antes de volver a la programación.",
 "use_netatmo": "Usar la de Netatmo", "h1": "1 hora", "h2": "2 horas", "h3": "3 horas", "h4": "4 horas",
 "alerts": "Avisos", "alerts_toggle": "Batería baja y desconexión", "visible_homes": "Casas visibles",
 "visible_homes_hint": "Desactiva una casa para ocultarla de la pantalla de inicio.",
 "warm_up": "Tengo frío", "warm_up_desc": "Sube el objetivo 1 °C por encima de la temperatura actual durante 2 horas.",
 "warm_done": "Subo %@ a %@ durante dos horas.", "warm_which": "¿En qué casa tienes frío?",
 "warm_sign_in": "Abre Calefacción Netatmo e inicia sesión primero.", "warm_no_home": "No sé en qué casa. Elige una en el atajo.",
 "warm_no_thermostat": "Esa casa no tiene termostato configurado.", "home": "Casa",
 "alert_battery_body": "%@ en %@. Conviene cambiar las pilas.", "alert_offline_title": "Termostato sin conexión",
 "alert_offline_body": "%@ no responde. Revisa el termostato o la conexión.", "done": "Listo",
}
# Propias de Android: es, en, fr
OWN = {
 "alerts_hint": ("La app mira las casas cada hora en segundo plano y avisa si un termostato se queda sin pilas o sin conexión.",
                 "The app checks your homes every hour in the background and warns you if a thermostat runs out of batteries or goes offline.",
                 "L’app vérifie vos maisons toutes les heures en arrière-plan et vous prévient si un thermostat n’a plus de piles ou perd la connexion."),
 "notifications_off": ("Las notificaciones están desactivadas para esta app. Actívalas en Ajustes de Android › Apps › Calefacción › Notificaciones.",
                       "Notifications are turned off for this app. Turn them on in Android Settings › Apps › Calefacción › Notifications.",
                       "Les notifications sont désactivées pour cette app. Activez-les dans Paramètres Android › Applications › Calefacción › Notifications."),
 "credentials_hint": ("Obtén estos datos en dev.netatmo.com › My apps. La Redirect URI debe estar registrada allí exactamente igual (es la misma que la de la app de iOS). Se guardan cifrados en el móvil.",
                      "Get these from dev.netatmo.com › My apps. The Redirect URI must be registered there exactly as written (it is the same as the iOS app’s). They are stored encrypted on the phone.",
                      "Récupérez-les sur dev.netatmo.com › My apps. L’URI de redirection doit y être enregistrée à l’identique (c’est la même que celle de l’app iOS). Ils sont chiffrés sur le téléphone."),
 "pick_home": ("Elige una casa", "Choose a home", "Choisissez une maison"),
 "pick_home_hint": ("Este widget mostrará la casa que elijas.", "This widget will show the home you pick.", "Ce widget affichera la maison choisie."),
 "widget_status_desc": ("Temperatura actual y objetivo de una casa", "Current and target temperature of a home", "Température actuelle et consigne d’une maison"),
 "widget_warm_desc": ("Sube 1 °C durante 2 horas sin abrir la app", "Raise 1 °C for 2 hours without opening the app", "Monte de 1 °C pendant 2 heures sans ouvrir l’app"),
 "tile_away": ("Ausente", "Away", "Absent"),
 "tile_no_home": ("Sin casa", "No home", "Pas de maison"),
 "warm_failed": ("No se pudo subir la calefacción", "Couldn’t raise the heating", "Impossible de monter le chauffage"),
 "away_on": ("%1$s en modo ausente", "%1$s set to away", "%1$s en mode absent"),
 "away_off": ("%1$s vuelve a la programación", "%1$s back to schedule", "%1$s revient au programme"),
 "channel_alerts": ("Avisos de los termostatos", "Thermostat alerts", "Alertes des thermostats"),
 "usage_days": ("Últimos 14 días", "Last 14 days", "14 derniers jours"),
 "temp_24h": ("Temperatura, últimas 24 h", "Temperature, last 24 h", "Température, dernières 24 h"),
}

def android(s):
    s = re.sub(r"%(\d)\$@", r"%\1$s", s)
    s = re.sub(r"%(\d)\$lld", r"%\1$d", s)
    n = [0]
    def rep(m):
        n[0] += 1
        return "%%%d$%s" % (n[0], "s" if m.group(0) == "%@" else "d")
    out = re.sub(r"%@|%lld", rep, s)
    if n[0] == 1: out = out.replace("%1$", "%")
    return out.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")

def ios(key, lang):
    if lang == "es": return key
    v = cat.get(key, {}).get("localizations", {}).get(lang, {}).get("stringUnit", {}).get("value")
    return v or key

for lang, d in (("es", "values"), ("en", "values-en"), ("fr", "values-fr")):
    os.makedirs(f"{res}/{d}", exist_ok=True)
    with open(f"{res}/{d}/strings.xml", "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        if lang == "es": f.write('    <string name="app_name" translatable="false">Calefacción</string>\n')
        for k, es in FROM_IOS.items():
            if es not in cat: raise SystemExit(f"falta en iOS: {es}")
            f.write(f'    <string name="{k}">{android(ios(es, lang))}</string>\n')
        i = {"es": 0, "en": 1, "fr": 2}[lang]
        for k, v in OWN.items():
            f.write(f'    <string name="{k}">{android(v[i]) if "%1$s" not in v[i] else v[i]}</string>\n')
        f.write("</resources>\n")
print("ok", len(FROM_IOS) + len(OWN), "textos")
