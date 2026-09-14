# METIS Database

Database migrations, seed data and PostgreSQL routines are currently stored in
`docker/init/sql` because Docker mounts that directory into PostgreSQL during
initialisation. This Maven module marks the database as an owned component
without coupling SQL execution to Java compilation.

The database component will move to Flyway-managed migrations before the
application is split into independently deployed services.
