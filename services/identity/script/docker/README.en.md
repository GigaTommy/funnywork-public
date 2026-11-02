# Identity service Docker examples

[简体中文](README.md) | **English**

This directory retains RuoYi multi-service deployment examples. See [self-hosted deployment](../../../../deploy/SERVER_DEPLOYMENT.md) for FunnyWork services. Supply database, Redis, and object-store passwords through the deployment environment.

## Prepare credentials

From the repository root, enter this directory and create a restricted environment file:

```sh
cd services/identity/script/docker
cp runtime.env.example runtime.env
chmod 600 runtime.env
```

Fill `runtime.env` with independently generated passwords. `docker-compose.yml` needs MySQL, Redis, and MinIO credentials. SQL Server, PostgreSQL, and PostgreSQL 13 in `database.yml` need their own passwords. `runtime.env` is ignored by Git and remains in the deployment environment.

Before starting, prepare the configuration and data directories under `/docker/` according to the Compose volume mappings. Copy `redis/conf/redis.conf` to `/docker/redis/conf/redis.conf`, and supply the Nginx configuration and certificates. This example uses host networking; restrict database, cache, and object-store ports to trusted services with the host firewall. Existing database data directories retain their account passwords. After changing environment variables, update those accounts through the database administration procedure and update application settings accordingly.

## Validate and start

From this directory, validate without printing expanded settings:

```sh
docker compose --env-file runtime.env -f docker-compose.yml config --quiet
docker compose --env-file runtime.env -f docker-compose.yml up -d
```

Compose rejects missing or empty required passwords. Redis receives its password from the runtime environment; its standalone configuration binds only loopback. SQL Server and MinIO passwords must also meet their services' length and complexity rules. The legacy Oracle image example is for isolated testing; production deployments use maintained databases and their account-initialization procedures.

To validate `database.yml`, substitute that filename above. Keep expanded configuration out of validation logs: full `docker compose config` may contain injected credentials. Preserve backups and data directories before upgrades or cleanup.
