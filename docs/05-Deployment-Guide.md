# Deployment Guide #

This document don't intend to be a complete guide of deployment. It
will only contain the essential tips for doing it and show an example
on how we are deploying it using docker and docker-compose.


## Docker Images ##

For build the production images, you need to execute the following
command:

```bash
./manage.sh build-images`
```

This command will build the following images:

- `uxbox-frontend:latest`
- `uxbox-frontend-dbg:latest` (with debug ready frontend build)
- `uxbox-backend:latest`


Complementary to the docker images you can build locally from this
repository, you can find additionnal flavors for backend and frontend
on external repositories:
* [Monogramm/docker-uxbox-frontend](https://github.com/Monogramm/docker-uxbox-frontend)
* [Monogramm/docker-uxbox-backend](https://github.com/Monogramm/docker-uxbox-backend)


## Docker Compose ##

Look at `docker/docker-compose.yml` for a complete example.


## SSL/TLS ##

The default images does not handles anything realted to ssl. They are
intended to be deployed behind a proxy (nginx,haproxy,...).
