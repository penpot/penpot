---
title: 1.4 Install with Hostim.dev
desc: "Deploy Penpot in seconds with Hostim.dev. One-click setup with persistent storage, automatic SSL, and managed infrastructure."
---

# Install with Hostim.dev

This section explains how to get Penpot up and running using [Hostim.dev](https://hostim.dev).

Hostim.dev provides a platform for deploying open-source applications with one click. By choosing Hostim.dev, you get a production-ready Penpot instance with persistent storage, automatic SSL (Let's Encrypt), and built-in observability without the complexity of managing a cluster yourself.

## Deploy Penpot on Hostim.dev

You can deploy a dedicated instance of Penpot in less than a minute:

1. Go to the [Hostim.dev Dashboard](https://console.hostim.dev).
2. Click **Create Project** → **Use a Template**.
3. Select the **Penpot** template from the library.
4. Choose your **resource plan** (2GB RAM is recommended for small teams).
5. Hit **Deploy**.

Alternatively, you can use the direct one-click deployment link:
[**Deploy Penpot Now**](https://console.hostim.dev/dashboard?preview=1&modal=1&template=penpot)

## Post-Deployment

Once the deployment is complete, Hostim.dev will provide you with a unique `*.hostim.dev` subdomain with HTTPS enabled automatically.

- **Accessing Penpot**: Click on the generated URL in your dashboard to open your new Penpot instance.
- **Persistent Storage**: All your designs and data are stored in a persistent volume, ensuring they remain safe across restarts.
- **Custom Domains**: You can easily attach your own custom domain in the **Networking** tab of your project.
- **Logs & Metrics**: Real-time container logs and resource usage metrics are available directly in the dashboard.

## Configuration

If you need to customize your Penpot instance (e.g., configuring SMTP or external storage), you can do so by navigating to the **Environment Variables** tab in your Hostim.dev dashboard.

Common configuration options can be found in the [Configuration][1] section of this guide.

[1]: /technical-guide/configuration/
