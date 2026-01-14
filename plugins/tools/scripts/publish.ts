import { execSync } from 'child_process';
import { releaseChangelog, releasePublish, releaseVersion } from 'nx/release';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';

const determineArgs = async () => {
  return await yargs(hideBin(process.argv))
    .version(false)
    .option('dryRun', {
      alias: 'd',
      description:
        'Whether or not to perform a dry-run of the release process, defaults to true',
      type: 'boolean',
      default: true,
    })
    .option('verbose', {
      description:
        'Whether or not to enable verbose logging, defaults to false',
      type: 'boolean',
      default: false,
    })
    .option('version', {
      description:
        'Explicit version specifier to use, if overriding conventional commits',
      type: 'string',
      default: undefined,
    })
    .option('skip-publish', {
      description: 'Skip publishing the package to the registry',
      type: 'boolean',
      default: false,
    })
    .option('preid', {
      description: 'The prerelease identifier to use',
      type: 'string',
      default: undefined,
    })
    .option('latest', {
      description: 'Publish the package with the latest tag',
      type: 'boolean',
      default: true,
    })
    .option('first-release', {
      description: 'Whether or not this is the first release',
      type: 'boolean',
      default: false,
    })
    .parseAsync();
};

(async () => {
  const args = await determineArgs();

  const result = await releaseVersion({
    dryRun: args.dryRun,
    specifier: args.version,
    gitCommit: false,
    gitTag: false,
    stageChanges: true,
    verbose: args.verbose,
    preid: args.preid,
  });

  execSync(
    'npx nx run-many -t build -p plugins-runtime plugins-styles plugin-types --parallel',
    {
      cwd: process.cwd(),
      stdio: 'inherit',
    },
  );

  await releaseChangelog({
    dryRun: args.dryRun,
    versionData: result.projectsVersionData,
    version: result.workspaceVersion,
    gitCommitMessage: `chore(release): publish ${result.workspaceVersion} [skip ci]`,
    gitCommit: true,
    gitTag: true,
    verbose: args.verbose,
    firstRelease: args.firstRelease,
  });

  if (!args.skipPublish) {
    await releasePublish({
      dryRun: args.dryRun,
      verbose: args.verbose,
      tag: args.latest ? 'latest' : 'next',
    });
  }

  process.exit(0);
})();
