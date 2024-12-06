---
title: 4. Integration Guide
---

# Integration Guide

This section explains how to interconnect Penpot with other applications, so
they can collaborate and create new kinds of features, or integrate in DesignOps
or GitOps workflows.

The system is relatively simple for now, but expect more functions in the future.

## Webhooks

Outbound webhooks are event calls from Penpot to other applications, that
notifiy some particular event has occured (e.g. a file has been created or
updated, a comment has been added, etc.).

#### Webhooks managament
In Penpot, webhooks are configured at Team level-

![Webhooks area](/img/tech-guide/webhooks.webp)

#### Add webhooks
When you add a webhook, you specify the URL of a service you own. If the webhook
is active, a POST request will be sent to the URL on any event that occurs anywhere
in the team.
![Add a webhook](/img/tech-guide/add-webhook.webp)

You can specify the format of the call payload.

* JSON is a standard format, accepted by almost every web application.
* <a href="https://github.com/cognitect/transit-format" target="_blank">Transit</a>
is a format, that may be encapsulated inside JSON, that adds datatype
information and enriches the content with semantic information.

### Events list

Unfortunately, we do not have any specific documentation for the webhooks yet.
For the moment you can use the <a href="https://design.penpot.app/api/_doc"
target="_blank">backend API documentation</a>, generated automatically from <a
href="https://github.com/penpot/penpot/tree/main/backend/src/app/rpc"
target="_blank">source code</a>, as a guide.

All backend RPC calls labelled as <code class="language-bash">WEBHOOK</code> trigger webhook calls, if
appropriate, with an equivalent payload.

The payload content is specified as <a href="https://clojure.org/guides/spec"
target="_blank">Clojure Spec</a> predicates:

![Example of a RPC call](/img/tech-guide/webhook-call.webp)

The listed spec details all required (<code class="language-bash">:req</code> or <code class="language-bash">:req-un</code>) and optional
(<code class="language-bash">:opt-un</code>) attributes of the RPC parameters.

The payload of the webhook is similar, but there may be some changes (some
parameters ommited or others added). The recommended way of understanding the
webhook calls is by using <a href="https://webhook.site/" target="_blank">Webhook.site</a>.
Generate a site URL and set it into Penpot. Then you can inspect the calls received.


## Access tokens

Personal access tokens function like an alternative to our login/password authentication system and can be used to allow an application to access the internal Penpot API.

<p class="advice"><strong>Important:</strong> Treat your access tokens like passwords as they provide access to our account.</p>

### Manage access tokens
In Penpot, access tokens are configured at user account level. To manage your access tokens, go to Your account > Access tokens.

![Access tokens](/img/tech-guide/access-tokens.webp)

### Generate access tokens

1. Press the "Generate new token" button.

![Creating token](/img/tech-guide/access-tokens-create-1.webp)

2. Fill the name of the token. Descriptive names are recommended.

3. Choose an expiration date. Current options are: Never, 30 days, 60 days, 90 days or 180 days.

![Token expiration](/img/tech-guide/access-tokens-create-2.webp)

4. Once you're happy with the name and the expiration date, press "Create token". At this step you will be able to copy the token to your clipboard.

![Token created](/img/tech-guide/access-tokens-create-3.webp)

### Delete access tokens

You can delete tokens anytime. You'll find the option at the menu at the right side of each token of the tokens list.


### Using access tokens

Having a personal token will allow you to use it instead of your password.

This is an example of a curl command that you can run at the console to access your Penpot profile using an access token:

```bash
curl -H "Authorization: Token <replace-this-with-token>" https://design.penpot.app/api/rpc/command/get-profile
```
