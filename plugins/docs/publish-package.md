# Publishing Packages

## Introduction

This guide details the process of publishing `plugin-types`,
`plugins-styles` and `plugins-runtime` packages, which are essential
for plugin development. Below is a walkthrough for publishing these
packages and managing releases.

**Warning**
Before generating the release, please, check the update the changelog with
the changes that will be released.

## NPM Authentication

You need to generate a temporary access token in the NPM website.

Once you have the token add the following to the `.npmrc`

```
//registry.npmjs.org/:_authToken=<TOKEN>
```

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
pnpm run release -- --version patch
```

The `--version` option accepts:

- `patch` - Increments the patch version (1.0.0 → 1.0.1)
- `minor` - Increments the minor version (1.0.0 → 1.1.0)
- `major` - Increments the major version (1.0.0 → 2.0.0)
- An exact version like `1.5.0`

### Generating a Real Release

To create an actual release, disable the dry-run option:

```shell
pnpm run release -- --version patch --dry-run false
```

This command will:

- Update the library's `package.json` version
- Build all packages
- Publish to NPM with the `latest` tag

Ensure everything is correct before proceeding with the git push. Once
verified, execute the following commands:

```shell
git commit -m ":arrow_up: Updated plugins release to X.X.X"
git push
```

### Creating a Preview Version

To generate a preview version and avoid publishing it as the latest release, use:

```shell
pnpm run release -- --version prepatch --dry-run false --latest false --preid next
```

For example, if the current version is `0.8.0` and you use `prepatch`,
it will generate the version `0.8.1-next.0` and publish it with the
`next` tag on npm.

### Help

To see more options, run:

```shell
pnpm run release -- --help
```

## Important Reminders

- Ensure to update the [penpot](https://github.com/penpot/penpot/blob/develop/frontend/package.json) and [penpot-plugin-starter-template](https://github.com/penpot/penpot-plugin-starter-template) with every release to provide developers with the latest configuration and features.

- Update the API documentations following [this documentation](api-docs.md).

## Relevant Files and Scripts

- **CSS Build Script**: `./tools/scripts/build-css.mjs`
- **Types Build Script**: `./tools/scripts/build-types.mjs`
- **Release Script**: `./tools/scripts/publish.ts`
