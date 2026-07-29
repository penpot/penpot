import { execSync } from 'child_process';
import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import { join } from 'path';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';

const PACKAGES = [
  'libs/plugin-types',
  'libs/plugins-styles',
  'libs/plugins-runtime',
];

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
        'Explicit version specifier to use (e.g., 1.5.0, patch, minor, major)',
      type: 'string',
      demandOption: true,
    })
    .option('skip-publish', {
      description: 'Skip publishing the package to the registry',
      type: 'boolean',
      default: false,
    })
    .option('preid', {
      description: 'The prerelease identifier to use (e.g., next, beta, alpha)',
      type: 'string',
      default: undefined,
    })
    .option('latest', {
      description: 'Publish the package with the latest tag',
      type: 'boolean',
      default: true,
    })
    .parseAsync();
};

interface PackageJson {
  name: string;
  version: string;
  [key: string]: unknown;
}

const readPackageJson = (packagePath: string): PackageJson => {
  const filePath = join(process.cwd(), packagePath, 'package.json');
  return JSON.parse(readFileSync(filePath, 'utf-8'));
};

const writePackageJson = (packagePath: string, content: PackageJson): void => {
  const filePath = join(process.cwd(), packagePath, 'package.json');
  writeFileSync(filePath, JSON.stringify(content, null, 2) + '\n');
};

const getPublishPath = (packagePath: string): string => {
  return join('dist', packagePath.split('/').pop()!);
};

const prepareRuntimePackage = (): void => {
  const source = 'libs/plugins-runtime';
  const dist = getPublishPath(source);

  mkdirSync(dist, { recursive: true });
  cpSync(join(source, 'package.json'), join(dist, 'package.json'));
  cpSync(join(source, 'README.md'), join(dist, 'README.md'));
  cpSync('LICENSE', join(dist, 'LICENSE'));
  cpSync(join(source, 'dist'), join(dist, 'dist'), { recursive: true });
};

const incrementVersion = (
  currentVersion: string,
  specifier: string,
  preid?: string,
): string => {
  const [major, minor, patch] = currentVersion
    .split('-')[0]
    .split('.')
    .map(Number);

  switch (specifier) {
    case 'major':
      return preid ? `${major + 1}.0.0-${preid}.0` : `${major + 1}.0.0`;
    case 'minor':
      return preid
        ? `${major}.${minor + 1}.0-${preid}.0`
        : `${major}.${minor + 1}.0`;
    case 'patch':
      return preid
        ? `${major}.${minor}.${patch + 1}-${preid}.0`
        : `${major}.${minor}.${patch + 1}`;
    case 'premajor':
      return `${major + 1}.0.0-${preid || 'next'}.0`;
    case 'preminor':
      return `${major}.${minor + 1}.0-${preid || 'next'}.0`;
    case 'prepatch':
      return `${major}.${minor}.${patch + 1}-${preid || 'next'}.0`;
    case 'prerelease': {
      const preMatch = currentVersion.match(/-([^.]+)\.(\d+)$/);
      if (preMatch) {
        const preId = preid || preMatch[1];
        const preNum = parseInt(preMatch[2], 10) + 1;
        return `${major}.${minor}.${patch}-${preId}.${preNum}`;
      }
      return `${major}.${minor}.${patch + 1}-${preid || 'next'}.0`;
    }
    default:
      // If specifier is an exact version (e.g., "1.5.0"), use it directly
      if (/^\d+\.\d+\.\d+/.test(specifier)) {
        return specifier;
      }
      throw new Error(`Unknown version specifier: ${specifier}`);
  }
};

const log = (message: string, verbose: boolean, forceLog = false): void => {
  if (verbose || forceLog) {
    console.log(message);
  }
};

(async () => {
  const args = await determineArgs();

  // Get current version from one of the packages
  const currentPkg = readPackageJson(PACKAGES[0]);
  const currentVersion = currentPkg.version;

  const newVersion = incrementVersion(currentVersion, args.version, args.preid);

  console.log(`\n📦 Release: ${currentVersion} → ${newVersion}`);
  console.log(`   Mode: ${args.dryRun ? 'DRY RUN' : 'REAL RELEASE'}`);
  console.log(`   Tag: ${args.latest ? 'latest' : 'next'}\n`);

  // Update version in all packages
  for (const packagePath of PACKAGES) {
    const pkg = readPackageJson(packagePath);
    const oldVersion = pkg.version;
    pkg.version = newVersion;

    // Update internal dependencies
    const deps = pkg['dependencies'] as Record<string, string> | undefined;
    if (deps && typeof deps === 'object') {
      for (const dep of Object.keys(deps)) {
        if (dep.startsWith('@penpot/')) {
          deps[dep] = `^${newVersion}`;
        }
      }
    }

    log(`   ${pkg.name}: ${oldVersion} → ${newVersion}`, args.verbose, true);

    if (!args.dryRun) {
      writePackageJson(packagePath, pkg);
    }
  }

  console.log('\n🔨 Building packages...\n');

  // Build all packages
  if (!args.dryRun) {
    execSync(
      'pnpm --filter @penpot/plugins-runtime --filter @penpot/plugin-styles --filter @penpot/plugin-types build',
      {
        cwd: process.cwd(),
        stdio: 'inherit',
      },
    );
    prepareRuntimePackage();
  } else {
    console.log('   [DRY RUN] Skipping build\n');
  }

  // Publish packages
  if (!args.skipPublish) {
    console.log('\n📤 Publishing packages...\n');

    const tag = args.latest ? 'latest' : 'next';

    for (const packagePath of PACKAGES) {
      const pkg = readPackageJson(packagePath);
      const distPath = getPublishPath(packagePath);

      if (args.dryRun) {
        console.log(
          `   [DRY RUN] Would publish ${pkg.name}@${newVersion} with tag "${tag}"`,
        );
      } else {
        try {
          execSync(
            `pnpm publish --tag ${tag} --access public --no-git-checks`,
            {
              cwd: join(process.cwd(), distPath),
              stdio: 'inherit',
            },
          );
          console.log(`   ✅ Published ${pkg.name}@${newVersion}`);
        } catch (error) {
          console.error(`   ❌ Failed to publish ${pkg.name}`);
          throw error;
        }
      }
    }
  } else {
    console.log('\n⏭️  Skipping publish (--skip-publish)\n');
  }

  console.log('\n✨ Release complete!\n');

  if (!args.dryRun) {
    console.log('Next steps:');
    console.log(`  1. Review the changes`);
    console.log(
      `  2. Commit: git commit -am ":arrow_up: Updated plugins release to ${newVersion}"`,
    );
    console.log(`  3. Push: git push\n`);
  }

  process.exit(0);
})();
