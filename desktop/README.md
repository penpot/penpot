# Penpot Desktop

An experimental Electron launcher for running the self-hosted Penpot stack as a desktop application. It starts the official Docker Compose deployment, waits for `http://localhost:9001`, and opens Penpot in a dedicated window.

## Requirements

- Docker Engine or Docker Desktop
- Docker Compose v2 (`docker compose`)
- Permission to access the Docker daemon

Docker remains an explicit dependency. The desktop package does not embed PostgreSQL, Valkey, or the Penpot service images.

## Development

```bash
cd desktop
npm install
npm start
```

The launcher uses `docker/images/docker-compose.yaml` from this repository. To test another Compose file, set:

```bash
PENPOT_COMPOSE_FILE=/path/to/docker-compose.yaml npm start
```

## Build Linux packages

```bash
cd desktop
npm ci
npm test
npm run build
```

The AppImage and DEB packages are written to `desktop/dist/`. The `desktop-release.yml` workflow also publishes both files as workflow artifacts and creates a GitHub release for tags matching `desktop-v*`.

## Data lifecycle

The launcher uses the Compose project name `penpot`. Choosing **Stop services** runs `docker compose stop`; it never runs `down -v`. PostgreSQL and asset volumes therefore survive application restarts and package upgrades. Closing the desktop window leaves the containers running so background work is not interrupted.

## Security

The Penpot window uses Electron sandboxing, context isolation, disabled Node.js integration, and web security. In-app navigation is restricted to the exact local Penpot origin, while external HTTP links open in the system browser. IPC methods are exposed only to the bundled launcher page.

## Current scope

This proposal is Linux-first and produces unsigned packages. Windows/macOS packaging, code signing, automatic updates, and the long-term release cadence need maintainer decisions before this can be considered an official distribution channel.
