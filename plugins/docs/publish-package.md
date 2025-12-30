# Publishing Packages

## Introduction

This guide details the process of publishing `plugin-types`,
`plugins-styles` and `plugins-runtime` packages, which are essential
for plugin development. Below is a walkthrough for publishing these
packages and managing releases.

## Publishing Libraries

Publishing packages enables the distribution of types and styles
libraries. Currently, all packages share the same version, meaning
some releases might not contain updates but will still increment the
version. Follow the steps below for the automated publishing
processes.

### Previewing a Release

To generate a preview of the release to check if everything is as
expected, run the following command:

```shell
git checkout main
npm run release
```

### Generating a Real Release

To create an actual release, disable the dry-run option:

```shell
npm run release -- --dry-run false
```

This command will:

- Update the `CHANGELOG.md`
- Update the library's `package.json` version
- Generate a commit
- Create a new git tag
- Publish to NPM with the `latest` tag

Ensure everything is correct before proceeding with the git push. Once
verified, execute the following commands:

```shell
git push
git push origin vX.X.X
```

Replace `vX.X.X` with the new version number.

> 📘 To update the documentation site, you must also update the `stable` branch:

```shell
git checkout stable
git merge main
git push origin stable
```

For detailed information, refer to the [Nx Release
Documentation](https://nx.dev/recipes/nx-release/get-started-with-nx-release).

### Creating a Preview Version

To generate a preview version and avoid publishing it as the latest release, use:

```shell
npm run release -- --dry-run false --latest false --preid next
```

For example, if the current version is `0.8.0` and you select the
`prepatch` option as a version specifier, it will generate the version
`0.8.1-next.0` and publish it with the next tag on npm.

### Help

To see more options, run:

```shell
npm run release -- --help
```

## Important Reminders

- Ensure to update the [penpot](https://github.com/penpot/penpot/blob/develop/frontend/package.json) and [penpot-plugin-starter-template](https://github.com/penpot/penpot-plugin-starter-template) with every release to provide developers with the latest configuration and features.

- Update the API documentations following [this documentation](api-docs.md).

## Relevant Files and Scripts

- **CSS Build Script**: `./tools/scripts/build-css.mjs`
- **Types Build Script**: `./tools/scripts/build-types.mjs`
- **Release Script**: `./tools/scripts/publish.ts`
- **Publish config**: `./nx.json`
