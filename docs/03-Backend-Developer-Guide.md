# Backend Developer Guide #

This guide intends to explain the essential details of the backend
application.


## Fixtures ##

This is a development feature that allows populate the database with a
good amount of random content (usually used for just test the
application or perform performance tweaks on queries).

In order to load fixtures, enter to the REPL environment executing the
`bin/repl` script, and then execute `(uxbox.fixtures/run :small)`.

You also can execute this as a standalone script with:

```bash
clojure -Adev -m uxbox.fixtures
```

NOTE: It is an optional step because the application can start with an
empty database.

This by default will create a bunch of users that can be used to login
in the aplication. All users uses the following pattern:

- Username: `profileN.test@uxbox.io`
- Password: `123123`

Where `N` is a number from 0 to 49 on the default fixture parameters.


## Sample media ##

There is also a media collection with some libraries (material design
icons and some colors). It may be loaded with:

```bash
clojure -Adev -m uxbox.media-loader ../sample_media/config.edn
```

You have more information at 05-Management-Guide.md document.

