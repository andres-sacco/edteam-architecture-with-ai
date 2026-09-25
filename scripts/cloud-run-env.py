#!/usr/bin/env python3
"""Genera el archivo de variables de entorno del servicio de Cloud Run.

    CORS_ALLOWED_ORIGINS=... JWT_ISSUER=... JWT_AUDIENCE=... JWT_JWK_SET_URI=... \
        python3 scripts/cloud-run-env.py > /tmp/run-env.json
    gcloud run deploy ... --env-vars-file /tmp/run-env.json

POR QUÉ UN ARCHIVO Y NO '--set-env-vars'

1. Repetir '--set-env-vars' NO acumula. gcloud la trata como un diccionario y
   la última aparición PISA a las anteriores, así que once banderas dejan el
   servicio con UNA variable. El modo en que falla es el peor posible: la
   aplicación levanta igual —todas tienen default en application.yml— pero sin
   SECURITY_DEV_TOKENS=false, o sea con el emisor de tokens de desarrollo
   encendido en producción. No lo detecta ningún test; lo detecta la prueba de
   humo, que pide una reserva sin token y exige 401.

2. CORS_ALLOWED_ORIGINS es en sí misma una lista separada por comas, que es el
   separador que usa esa bandera.

POR QUÉ SE GENERA CON json.dump Y NO CON UN HEREDOC

Los valores salen del entorno y nunca se concatenan dentro de un string: una
comilla, un salto de línea o un '$(...)' en una variable de repositorio es un
dato y no puede romper el formato ni escapar a un shell. JSON es YAML válido,
así que gcloud lo acepta tal cual.

QUÉ NO VA ACÁ

Ningún secreto. Este archivo queda en el disco del runner y su contenido pasa
a la definición del servicio, legible por cualquiera que tenga
'run.services.get'. Las credenciales entran por '--set-secrets', que no pasa
valores sino REFERENCIAS a Secret Manager: Cloud Run las resuelve al arrancar
el contenedor y nunca pasan por el runner.
"""

import json
import os
import sys

# Lo que no depende del entorno. Todo string: gcloud rechaza números y
# booleanos en este archivo.
FIXED = {
    "APP_ENV": "production",

    # Actuator en 9090. Cloud Run rutea EXACTAMENTE un puerto —el de '--port',
    # que es 8080— así que el de gestión no es alcanzable desde afuera.
    # '/actuator/metrics' revela volumetría de negocio y
    # 'http.server.requests' revela la superficie real de la API: la
    # restricción se cumple por topología y no por una regla de autorización
    # que haya que acertar endpoint por endpoint.
    "MANAGEMENT_PORT": "9090",

    # Los tres interruptores que tienen que estar apagados fuera de local.
    # El emisor HMAC de desarrollo se apaga y la validación pasa a hacerse
    # contra el JWKS real (JWT_JWK_SET_URI, más abajo).
    "SECURITY_DEV_TOKENS": "false",
    "API_DOCS_ENABLED": "false",
    "SWAGGER_UI_ENABLED": "false",

    # Sin broker ni Redis en la capa gratuita. La aplicación está preparada
    # para eso —el consumidor se apaga por propiedad y el cache cae a memoria
    # del proceso— y arranca sin ninguno de los dos. Encenderlos es agregar las
    # credenciales a Secret Manager y dar vuelta estos dos valores; no hay que
    # tocar la imagen.
    "MESSAGING_ENABLED": "false",
    "CACHE_REDIS_ENABLED": "false",
}

# Lo que sí depende del entorno. No son secretos —son un origen CORS y las
# coordenadas públicas del emisor de tokens— pero sí cambian por entorno, así
# que viven en variables de repositorio y no en este archivo.
#
# Se exige que estén definidas y no vacías. Un JWT_JWK_SET_URI vacío con
# SECURITY_DEV_TOKENS=false deja a la aplicación sin forma de validar ninguna
# firma: arrancaría y rechazaría el 100% del tráfico. Es mejor que falle acá,
# antes de crear la revisión.
FROM_ENV = (
    "CORS_ALLOWED_ORIGINS",
    "JWT_ISSUER",
    "JWT_AUDIENCE",
    "JWT_JWK_SET_URI",
)


def main():
    env = dict(FIXED)
    missing = []
    for name in FROM_ENV:
        value = os.environ.get(name, "").strip()
        if not value:
            missing.append(name)
        env[name] = value

    if missing:
        sys.stderr.write(
            "faltan variables de repositorio: {}\n"
            "Configuralas en Settings → Secrets and variables → Actions → Variables.\n"
            .format(", ".join(missing))
        )
        return 1

    json.dump(env, sys.stdout, indent=2, sort_keys=True, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
