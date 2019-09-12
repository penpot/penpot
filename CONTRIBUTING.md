# Contributing Guide #

Thank you for your interest in contributing to UXBox. This guide details how
to contribute to UXBox in a way that is efficient for everyone.


## Reporting Bugs ##

We are using [GitHub Issues](https://github.com/uxbox/uxbox/issues)
for our public bugs. We keep a close eye on this and try to make it
clear when we have an internal fix in progress. Before filing a new
task, try to make sure your problem doesn't already exist.

If you found a bug, please report it, as far as possible with:

- a detailed explanation of steps to reproduce the error
- a browser and the browser version used
- a dev tools console exception stack trace (if it is available)


## Pull requests ##

If you want propose a change or bug fix with the Pull-Request system
firstly you should carefully read the **Contributor License Aggreement**
section and format your commints accordingly.

If you intend to fix a bug it's fine to submit a pull request right
away but we still recommend to file an issue detailing what you're
fixing. This is helpful in case we don't accept that specific fix but
want to keep track of the issue.

If you want to implement or start working in a new feature, please
open a **question** / **discussion** issue for it. No pull-request
will be accepted without previous chat about the changes,
independently if it is a new feature, already planned feature or small
quick win.

If is going to be your first pull request, You can learn how from this
free video series:

https://egghead.io/series/how-to-contribute-to-an-open-source-project-on-github

We will use the `easy fix` mark for tag for indicate issues that are
easy for begginers.


## Commit Message Guidelines ##

We have very precise rules over how our git commit messages can be formatted.

The commit message format is:

```
<type> <subject>

[body]

[footer]
```

Where type is:

- `:bug:` a commit that fixes a bug
- `:sparkles:` a commit that an improvement
- `:tada:` a commit with new feature
- `:recycle:` a commit that introduces a refactor
- `:lipstick:` a commit with cosmetic changes
- `:ambulance:` a commit that fixes critical bug
- `:books:` a commit that improves or adds documentation
- `:construction:`: a wip commit
- `:construction_worker:` a commit with CI related stuff
- `:boom:` a commit with breaking changes
- `:wrench:` a commit for config updates
- `:zap:` a commit with performance improvements
- `:whale:` a commit for docker related stuff
- `:rewind:` a commit that reverts changes
- `:paperclip:` a commit with other not relevant changes
- `:arrow_up:` a commit with dependencies updates

More info: https://gist.github.com/parmentf/035de27d6ed1dce0b36a


## Code of conduct ##

As contributors and maintainers of this project, we pledge to respect
all people who contribute through reporting issues, posting feature
requests, updating documentation, submitting pull requests or patches,
and other activities.

We are committed to making participation in this project a
harassment-free experience for everyone, regardless of level of
experience, gender, gender identity and expression, sexual
orientation, disability, personal appearance, body size, race,
ethnicity, age, or religion.

Examples of unacceptable behavior by participants include the use of
sexual language or imagery, derogatory comments or personal attacks,
trolling, public or private harassment, insults, or other
unprofessional conduct.

Project maintainers have the right and responsibility to remove, edit,
or reject comments, commits, code, wiki edits, issues, and other
contributions that are not aligned to this Code of Conduct. Project
maintainers who do not follow the Code of Conduct may be removed from
the project team.

This code of conduct applies both within project spaces and in public
spaces when an individual is representing the project or its
community.

Instances of abusive, harassing, or otherwise unacceptable behavior
may be reported by opening an issue or contacting one or more of the
project maintainers.

This Code of Conduct is adapted from the Contributor Covenant, version
1.1.0, available from http://contributor-covenant.org/version/1/1/0/


## Contributor License Agreement ##

By submitting code you are agree and can certify the below:

    Developer's Certificate of Origin 1.1

    By making a contribution to this project, I certify that:

    (a) The contribution was created in whole or in part by me and I
        have the right to submit it under the open source license
        indicated in the file; or

    (b) The contribution is based upon previous work that, to the best
        of my knowledge, is covered under an appropriate open source
        license and I have the right under that license to submit that
        work with modifications, whether created in whole or in part
        by me, under the same open source license (unless I am
        permitted to submit under a different license), as indicated
        in the file; or

    (c) The contribution was provided directly to me by some other
        person who certified (a), (b) or (c) and I have not modified
        it.

    (d) I understand and agree that this project and the contribution
        are public and that a record of the contribution (including all
        personal information I submit with it, including my sign-off) is
        maintained indefinitely and may be redistributed consistent with
        this project or the open source license(s) involved.

Then, all your patches should contain a sign-off at the end of the
patch/commit description body. It can be automatically added on adding
`-s` parameter to `git commit`.

This is an example of the aspect of the line:

	Signed-off-by: Andrey Antukh <niwi@niwi.nz>

Please, use your real name (sorry, no pseudonyms or anonymous
contributions are allowed).


