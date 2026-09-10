# noduq-backend

API de identidad de NODUQ. Spring Boot 3, Java 21, hexagonal. Sin suscripciones, sin push, sin Gmail.

El dueño entra con JWT de Supabase Auth. El empleado entra con usuario + código. Una cuenta = un local.

## Qué hay

- Bootstrap del dueño: perfil, negocio, sucursal `Principal`, rol `owner`
- Empleados: crear (usuario escrito o generado), desactivar, regenerar código, borrar
- Sesión de empleado (JWT propio). Regenerar el código invalida sesiones
- Borrar cuenta: hay que escribir el nombre del local. Aún no bloquea por plan (RevenueCat va después)

## Arranque local

Copia `.env.example` a `.env`. En Supabase → Project Settings → Database, usa la URI de Postgres en forma JDBC (`jdbc:postgresql://...:5432/postgres`).

`EMPLOYEE_JWT_SECRET` mínimo 32 caracteres. `SUPABASE_SERVICE_ROLE_KEY` solo hace falta para borrar la cuenta de Auth.

```bash
./mvnw test
./mvnw spring-boot:run
```

En Windows: `mvnw.cmd`. Maven usa `JAVA_HOME`; este proyecto pide JDK 21 (`C:\Program Files\Java\jdk-21`). Si `JAVA_HOME` apunta a 17, el compile falla.

## HTTP

| Método | Ruta | Quién |
| --- | --- | --- |
| GET | `/health` | público |
| POST | `/v1/me/bootstrap` | dueño (JWT Supabase) |
| GET/PATCH | `/v1/me` | dueño |
| DELETE | `/v1/me` | dueño, body `{ "confirmation": "nombre del local" }` |
| GET/PATCH | `/v1/organization` | dueño |
| GET/POST | `/v1/employees` | dueño |
| PATCH/DELETE | `/v1/employees/{id}` | dueño |
| POST | `/v1/employees/{id}/code` | dueño, devuelve el código una vez |
| POST | `/v1/employee/sessions` | empleado, `{ "username", "code" }` |
| GET | `/v1/employee/me` | empleado |
| DELETE | `/v1/employee/sessions/me` | empleado |

El esquema está en Supabase (migración `identity_owner_org_employee`) y copiado en `src/main/resources/db/identity.sql`.
