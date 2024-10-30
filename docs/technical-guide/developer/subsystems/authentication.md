---
title: Authentication
---

# User authentication

Users in Penpot may register via several different methods (if enabled in the
configuration of the Penpot instance). We have implemented this as a series
of "authentication backends" in our code:

 * **penpot**: internal registration with email and password.
 * **ldap**: authentication over an external LDAP directory.
 * **oidc**, **google**, **github**, **gitlab**: authentication over an external
   service using the [OpenID Connect](https://openid.net/connect) protocol. We
   have a generic handler, and other ones already preconfigured for popular
   services.

The main logic resides in the following files:

```text
backend/src/app/rpc/mutations/profile.clj
backend/src/app/rpc/mutations/ldap.clj
backend/src/app/rpc/mutations/verify-token.clj
backend/src/app/http/oauth.clj
backend/src/app/http/session.clj
frontend/src/app/main/ui/auth/verify-token.cljs
```

We store in the user profiles in the database the auth backend used to register
first time (mainly for audit). A user may login with other methods later, if the
email is the same.

## Register and login

The code is organized to try to reuse functions and unify processes as much as
possible for the different auth systems.


### Penpot backend

When a user types an email and password in the basic Penpot registration page,
frontend calls <code class="language-clojure">:prepare-register-profile</code> method. It generates a "register
token", a temporary JWT token that includes the login data.

This is used in the second registration page, that finally calls
<code class="language-clojure">:register-profile</code> with the token and the rest of profile data. This function
is reused in all the registration methods, and it's responsible of creating the
user profile in the database. Then, it sends the confirmation email if using
penpot backend, or directly opens a session (see below) for othe methods or if
the user has been invited from a team.

The confirmation email has a link to <code class="language-clojure">/auth/verify-token</code>, that has a handler
in frontend, that is a hub for different kinds of tokens (registration email,
email change and invitation link). This view uses <code class="language-clojure">:verify-token</code> RPC call and
redirects to the corresponding page with the result.

To login with the penpot backend, the user simply types the email and password
and they are sent to <code class="language-clojure">:login</code> method to check and open session.

### OIDC backend

When the user press one of the "Log in with XXX" button, frontend calls
<code class="language-clojure">/auth/oauth/:provider</code> (provider is google, github or gitlab). The handler
generates a request token and redirects the user to the service provider to
authenticate in it.

If succesful, the provider redirects to the<code class="language-clojure">/auth/oauth/:provider/callback</code>.
This verifies the call with the request token, extracts another access token
from the auth response, and uses it to request the email and full name from the
service provider.

Then checks if this is an already registered profile or not. In the first case
it opens a session, and in the second one calls<code class="language-clojure">:register-profile</code> to create a
new user in the sytem.

For the known service providers, the addresses of the protocol endpoints are
hardcoded. But for a generic OIDC service, there is a discovery protocol to ask
the provider for them, or the system administrator may set them via configuration
variables.

### LDAP

Registration is not possible by LDAP (we use an external user directory managed
outside of Penpot). Typically when LDAP registration is enabled, the plain user
& password login is disabled.

When the user types their user & password and presses "Login with LDAP" button,
the <code class="language-clojure">:login-with-ldap</code> method is called. It connects with the LDAP service to
validate credentials and retrieve email and full name.

Similarly as the OIDC backend, it checks if the profile exists, and calls
<code class="language-clojure">:login</code> or <code class="language-clojure">:register-profile</code> as needed.

## Sessions

User sessions are created when a user logs in via any one of the backends. A
session token is generated (a JWT token that does not currently contain any data)
and returned to frontend as a cookie.

Normally the session is stored in a DB table with the information of the user
profile and the session expiration. But if a frontend connects to the backend in
"read only" mode (for example, to debug something in production with the local
devenv), sessions are stored in memory (may be lost if the backend restarts).

## Team invitations

The invitation link has a call to <code class="language-clojure">/auth/verify-token</code> frontend view (explained
above) with a token that includes the invited email.

When a user follows it, the token is verified and then the corresponding process
is routed, depending if the email corresponds to an existing account or not. The
<code class="language-clojure">:register-profile</code> or <code class="language-clojure">:login</code> services are used, and the invitation token is
attached so that the profile is linked to the team at the end.

## Handling unfinished registrations and bouncing users

All tokens have an expiration date, and when they are put in a permanent
storage, a garbage colector task visits it periodically to cleand old items.

Also our email sever registers email bounces and spam complaint reportings
(see <code class="language-text">backend/src/app/emails.clj</code>). When the email of one profile receives too
many notifications, it becames blocked. From this on, the user cannot login or
register with this email, and no message will be sent to it. If it recovers
later, it needs to be unlocked manually in the database.

## How to test in devenv

To test all normal registration process you can use the devenv [Mail
catcher](/technical-guide/developer/devenv/#email) utility.

To test OIDC, you need to register an application in one of the providers:

* [Github](https://docs.github.com/en/developers/apps/building-oauth-apps/creating-an-oauth-app)
* [Gitlab](https://docs.gitlab.com/ee/integration/oauth_provider.html)
* [Google](https://support.google.com/cloud/answer/6158849)

The URL of the app will be the devenv frontend: [http://localhost:3449]().

And then put the credentials in <code class="language-text">backend/scripts/repl</code> and
<code class="language-text">frontend/resources/public/js/config.js</code>.

Finally, to test LDAP, in the devenv we include a [test LDAP](https://github.com/rroemhild/docker-test-openldap)
server, that is already configured, and only needs to be enabled in frontend
<code class="language-text">config.js</code>:

```js
var penpotFlags = "enable-login-with-ldap";
```

