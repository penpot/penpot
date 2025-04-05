---
layout: layouts/plugins.njk
title: 3. Deployment
---

# Deployment

When it comes to deploying your plugin there are several platforms to choose from. Each platform has its unique features and benefits, so the choice depends on you.

In this guide you will found some options for static sites that have free plans.

## 3.1. Building your project

The building may vary between frameworks but if you had previously configured your scripts in <code class="language-bash">package.json</code>, <code class="language-bash">npm run build</code> should work.

The resulting build should be located somewhere in the <code class="language-bash">dist/</code> folder, maybe somewhere else if you have configured so.

Be wary that some framework's builders can add additional folders like <code class="language-bash">apps/project-name/</code>, <code class="language-bash">project-name/</code> or <code class="language-bash">browser/</code>.

Examples:

![Vue dist example](/img/plugins/vue_dist.png)
![Angular dist example](/img/plugins/angular_dist.png)


## 3.3. [Vercel](https://vercel.com/)

You need a Vercel account if you don't already have one. You can <a target="_blank" href="https://vercel.com/signup">sign up</a> with Github, GItlab, BItbucket, Passkey or via email and verification code.

### Login by email

If you choose to log in with an email address, you will receive a verification code via email that you need to enter to log in.
Enter your email address and press ‘continue with Email’.
![Vercel_login_by_email](/img/plugins/Vercel_login_by_email.webp)
Fill in your verification code.
![Vercel_login_verefication_code](/img/plugins/Vercel_login_verefication_code.webp)

### Vercel import Git repository

Vercel allows you to import an existing project from GitHub, GitLab, Bitbucket or Azure DevOps.
You can also use the [Vercel CLI to deploy](https://vercel.com/guides/using-vercel-cli-for-custom-workflows) with any git provider.
![Vercel_deploy_new_project](/img/plugins/Vercel_deploy_new_project.webp)

<a target="_blank" href="https://vercel.com/docs/deployments/">Vercel deployment documentation</a>.

1. Go to <a target="_blank" href="https://vercel.com/new">New</a> and connect with your repository or choose [Import Third-Party Git Repository](https://vercel.com/new/git/third-party).

## 3.3. [Netlify](https://www.netlify.com/)

### Create an account

You need a Netlify account if you don't already have one. You can <a target="_blank" href="https://app.netlify.com/signup">sign up</a> with Github, GItlab, BItbucket or via email and password.

### CORS issues

To avoid these issues you can add a <code class="language-bash">_headers</code> file to your plugin. Place it in the <code class="language-bash">public/</code> folder or alongside the main files.

```js
/*
  Access-Control-Allow-Origin: *
```

### Connect to Git

Netlify allows you to import an existing project from GitHub, GitLab, Bitbucket or Azure DevOps.

- <a target="_blank" href="https://docs.netlify.com/configure-builds/overview/">Configure builds</a>.

#### How to deploy

<figure>
  <video title="Deploy your plugin with Netlify using GitHub" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/deploy-netlify-repo.png" height="auto">
    <source src="/img/plugins/deploy-netlify-repo.mp4" type="video/mp4">
  </video>
</figure>

1. Go to <a target="_blank" href="https://app.netlify.com/start">Start</a> and connect with your repository. Allow Netlify to be installed in either all your projects or just the selected ones.

![Netlify git installation](/img/plugins/install_netlify.png)

2. Configure your build settings. Netlify auto-detects your framework and offers a basic configuration. This is usually enough.

![Netlify git configuration](/img/plugins/build_settings.png)

3. Deploy your plugin.

### Drag and drop

Netlify offers a simple drag and drop method. Check <a target="_blank" href="https://app.netlify.com/drop">Netlify Drop</a>.

#### How to deploy

<figure>
  <video title="Deploy your plugin with Netlify using drag and drop" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/deploy-netlify-dragdrop.png" height="auto">
    <source src="/img/plugins/deploy-netlify-dragdrop.mp4" type="video/mp4">
  </video>
</figure>

1. Build your project

```bash
npm run build
```

2. Go to <a target="_blank" href="https://app.netlify.com/drop">Netlify Drop</a>.

3. Drag and drop the build folder into Netlify Sites. Dropping the whole dist may not work, you should drop the folder where the main files are located.

4. Done!

## 3.4. [Cloudflare](https://www.cloudflare.com/)

### Create an account

You need a Cloudflare account if you don't already have one. You can <a target="_blank" href="https://dash.cloudflare.com/sign-up">sign up</a> via email and password.

### CORS issues

To avoid these issues you can add a <code class="language-bash">_headers</code> file to your plugin. Place it in the <code class="language-bash">public/</code> folder or alongside the main files.

```js
/*
  Access-Control-Allow-Origin: *
```

### Connect to Git

Cloudflare allows you to import an existing project from GitHub or GitLab.

- <a target="_blank" href="https://developers.cloudflare.com/pages/get-started/git-integration/">Git integration</a>

#### How to deploy

<figure>
  <video title="Deploy your plugin with Cloudflare using GitHub" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/deploy-cloudflare-repo.png" height="auto">
    <source src="/img/plugins/deploy-cloudflare-repo.mp4" type="video/mp4">
  </video>
</figure>


1. Go to Workers & Pages > Create > Page > Connect to git

2. Select a repository. Allow Cloudflare to be installed in either all your projects or just the selected ones.

![Cloudflare git installation](/img/plugins/install_cloudflare.png)

4. Configure your build settings.

![Cloudflare git configuration](/img/plugins/cf_build_settings.png)

5. Save and deploy.

### Direct upload

You can directly upload your plugin folder.

- <a target="_blank" href="https://developers.cloudflare.com/pages/get-started/direct-upload/">Direct upload</a>

#### How to deploy

<figure>
  <video title="Deploy your plugin with Cloudflare using drag and drop" muted="" playsinline="" controls="" width="100%" poster="/img/plugins/deploy-netlify-dragdrop.png" height="auto">
    <source src="/img/plugins/deploy-netlify-dragdrop.mp4" type="video/mp4">
  </video>
</figure>

1. Build your plugin.

```bash
npm run build
```

2. Go to Workers & Pages > Create > Page > Upload assets.

3. Create a new page.

![Cloudflare new page](/img/plugins/cf_new_page.png)

4. Upload your plugin files. You can drag and drop or select the folder.

![Cloudflare page upload files](/img/plugins/cf_upload_files.png)

5. Deploy site.

## 3.5. [Surge](https://surge.sh/)

Surge provides a CLI tool for easy deployment.

- <a target="_blank" href="https://surge.sh/help/getting-started-with-surge">Getting Started</a>.

### CORS issues

To avoid these issues you can add a <code class="language-bash">CORS</code> file to your plugin. Place it in the <code class="language-bash">public/</code> folder or alongside the main files.

The <code class="language-bash">CORS</code> can contain a <code class="language-bash">*</code> for any domain, or a list of specific domains.

Check <a target="_blank" href="https://surge.sh/help/enabling-cross-origin-resource-sharing">Enabling Cross-Origin Resources sharing</a>.

### How to deploy

1. Install surge CLI globally and log into your account or create one.

```bash
npm install --global surge
surge login
# or
surge signup
```

2. Create a CORS file to allow all sites.

```bash
echo '*' > public/CORS
```

3. Build your project.

```bash
npm run build
```

4. Start surge deployment

```bash
surge

# Your plugin build folder
project: /home/user/example-plugin/dist/

# your domain, surge offers a free .surge.sh domain and free ssl
domain: https://example-plugin-penpot.surge.sh

upload: [====================] 100% eta: 0.0s (10 files, 305761 bytes)
CDN: [====================] 100%
encryption: *.surge.sh, surge.sh (346 days)
IP: XXX.XXX.XXX.XXX

Success! - Published to example-plugin-penpot.surge.sh
```

5. Done!

## 3.6. Submitting to Penpot

To make your finished plugin available in our catalog, submit in on the [plugin submission page](https://penpot.app/penpothub/plugins/create-plugin). Once it becomes available any Penpot user will be able to install and use it.


