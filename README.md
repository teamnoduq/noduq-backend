# noduq-backend

API de NODUQ. Spring Boot 3, Java 21. Identidad, avisos QR (SMS + Gmail) y el plan.

El dueño entra con JWT de Supabase Auth. El empleado entra con usuario + código. Una cuenta = un local. El plan se cobra con RevenueCat (Test Store ahora; Play/Stripe después). RevenueCat **no es pasarela**: el cobro real lo hace Play, App Store o RevenueCat Billing. NODUQ solo recibe el entitlement.

## Qué hay

- Bootstrap del dueño: perfil, negocio, sucursal `Principal`, rol `owner`
- Empleados: crear, lookback (hoy / 3 / 7 días), desactivar, regenerar código, borrar
- Sesión de empleado (JWT propio). Regenerar el código invalida sesiones
- SMS del 85540 (teléfono del dueño) y correo de Bancolombia (Gmail del local)
- Plan `noduq_sms` (Android, SMS + correo). Sin plan activo no entra al mostrador
- Webhook `POST /v1/billing/revenuecat` y `POST /v1/billing/activate` (puente Test Store)
- Borrar cuenta: hay que escribir el nombre del local. Bloqueado si el plan sigue activo

## Arranque local

Copia `.env.example` a `.env`. En Supabase → Project Settings → Database, usa la URI de Postgres en forma JDBC (`jdbc:postgresql://...:5432/postgres`).

`EMPLOYEE_JWT_SECRET` mínimo 32 caracteres. `SUPABASE_SERVICE_ROLE_KEY` solo hace falta para borrar la cuenta de Auth. `REVENUECAT_WEBHOOK_AUTH` es el header `Authorization` que manda RevenueCat.

```bash
./mvnw test
./mvnw spring-boot:run
```

En Windows: `mvnw.cmd`. Maven usa `JAVA_HOME`; este proyecto pide JDK 21.

## HTTP

| Método | Ruta | Quién |
| --- | --- | --- |
| GET | `/health` | público |
| POST | `/v1/me/bootstrap` | dueño |
| GET/PATCH | `/v1/me` | dueño (incluye `plan`) |
| DELETE | `/v1/me` | dueño, body `{ "confirmation": "nombre del local" }` |
| GET/PATCH | `/v1/organization` | dueño |
| GET/POST | `/v1/employees` | dueño |
| PATCH/DELETE | `/v1/employees/{id}` | dueño (`lookbackDays`: 1, 3 o 7) |
| POST | `/v1/employees/{id}/code` | dueño |
| POST | `/v1/employee/sessions` | empleado |
| GET | `/v1/employee/me` | empleado |
| GET | `/v1/payments` | dueño (`q`, `since`, `until`, `source`) |
| POST | `/v1/payments/sms` | dueño (teléfono) |
| GET | `/v1/employee/payments` | empleado (recortado por lookback) |
| GET | `/v1/gmail` · `/v1/gmail/connect` | dueño (`returnTo=web` vuelve al panel) |
| GET | `/v1/gmail/callback` | Google, público |
| POST | `/v1/billing/activate` | dueño |
| POST | `/v1/billing/cancel` | dueño (sigue activo hasta `periodEndsAt`) |
| POST | `/v1/billing/reactivate` | dueño |
| POST | `/v1/billing/revenuecat` | RevenueCat, público con header |

El esquema está en Supabase. El archivo `src/main/resources/db/identity.sql` es copia de referencia; Spring no lo aplica.
