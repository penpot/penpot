---
title: 1.2 Install with Elestio
---

# Install with Elestio

This section explains how to get Penpot up and running using <a href="https://elest.io/open-source/penpot"
target="_blank">Elestio</a>.

This platform offers a fully managed service for on-premise instances of a selection of
open-source software! This means you can deploy a dedicated instance of Penpot in just 3
minutes. You’ll be relieved of the need to worry about DNS configuration, SMTP, backups,
SSL certificates, OS & Penpot upgrades, and much more.

## Get an Elestio account

<p class="advice">
Skip this section if you already have an Elestio account.
</p>

To create your Elestio account <a href="https://dash.elest.io/deploy?soft=Penpot&id=121"
target="_blank">click here</a>. You can choose to deploy on any one of five leading cloud
providers or on-premise.

## Deploy Penpot using Elestio

Now you can Create your service in “Services”:
1. Look for Penpot.
2. Select a Service Cloud Provider.
3. Select Service Cloud Region.
4. Select Service Plan (for a team of 20 you should be fine with 2GB RAM).
5. Select Elestio Service Support.
6. Provide Service Name (this will show in the URL of your instance) & Admin email (used
   to create the admin account).
7. Select Advanced Configuration options (you can also do this later).
8. Hit “Create Service” on the bottom right.

It will take a couple of minutes to get the instance launched. When the status turns to
“Service is running” you are ready to get started.

By clicking on the Service you go to all the details and configuration options.

In Network/CNAME you can find the URL of your instance. Copy and paste this into a browser
and start using Penpot.

## Configure Penpot with Elestio

If you want to make changes to your Penpot setup click on the “Update config” button in
Software. Here you can see the “Docker compose” used to create the instance. In “ENV” top
middle left you can make configuration changes that will be reflected in the Docker
compose.

In this file, a “#” at the start of the line means it is text and not considered part of
the configuration. This means you will need to delete it to get some of the configuration
options to work. Once you made all your changes hit “Update & restart”. After a couple of
minutes, your changes will be active.

You can find all configuration options in the [Configuration][1] section.

Get in contact with us through <a href="mailto:support@penpot.app">support@penpot.app</a>
if you have any questions or need help.

## Update Penpot

Elestio will update your instance automatically to the latest release unless you don't
want this. In that case you need to “Disable auto updates” in Software auto updates.

[1]: /technical-guide/configuration/
