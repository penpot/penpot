# CHANGELOG #

## :rocket: Next

### :sparkles: New features

- Add major refactor of internal pubsub/redis code; improves scalability and performance #640
- Add optional loki integration.
- Bounce & Complaint handling.
- Disable groups interactions when holding "Ctrl" key (deep selection)
- New action in context menu to "edit" some shapes (binded to key "Enter")


### :bug: Bugs fixed

- Add some missing database indexes (mainly improves performance on large databases on file-update rpc method, and some background tasks).
- Fix problem width handoff code generation [Taiga #1204](https://tree.taiga.io/project/penpot/issue/1204)
- Fix problem with indices refreshing on page changes [#646](https://github.com/penpot/penpot/issues/646)
- Have language change notification written in the new language [Taiga #1205](https://tree.taiga.io/project/penpot/issue/1205)
- Properly handle errors on github, gitlab and ldap auth backends.
- Properly mark profile auth backend (on first register/ auth with 3rd party auth provider).


## 1.2.0-alpha

### :sparkles: New features

- Add horizontal/vertical flip
- Add images lock proportions by default [#541](https://github.com/penpot/penpot/discussions/541), [#609](https://github.com/penpot/penpot/issues/609)
- Add new blob storage format (Zstd+nippy)
- Add user feedback form
- Improve French translations
- Improve component testing
- Increase default deletion delay to 7 days
- Show a pixel grid when zoom greater than 800% [#519](https://github.com/penpot/penpot/discussions/519)
- Fix behavior of select all command when there are objects outside frames [Taiga #1209](https://tree.taiga.io/project/penpot/issue/1209)


### :bug: Bugs fixed

- Fix 404 when access shared link [#615](https://github.com/penpot/penpot/issues/615)
- Fix 500 when requestion password reset
- Fix Problems when transforming path shapes [Taiga #1170](https://tree.taiga.io/project/penpot/issue/1170)
- Fix apply a color to a text selection from color palette was not working [Taiga #1189](https://tree.taiga.io/project/penpot/issue/1189)
- Fix issues when moving shapes outside groups [Taiga #1138](https://tree.taiga.io/project/penpot/issue/1138)
- Fix ldap function called on login click
- Fix logo icon in viewer should go to dashboard [Taiga #1149](https://tree.taiga.io/project/penpot/issue/1149)
- Fix ordering when restoring deleted shapes in sync [Taiga #1163](https://tree.taiga.io/project/penpot/issue/1163)
- Fix problem when editing text immediately after creating [Taiga #1207](https://tree.taiga.io/project/penpot/issue/1207)
- Fix problem when pasting URL's copied from the browser url bar [Taiga #1187](https://tree.taiga.io/project/penpot/issue/1187)
- Fix problem with multiple selection and groups [Taiga #1128](https://tree.taiga.io/project/penpot/issue/1128)
- Fix problem with red handler indicator on resize [Taiga #1188](https://tree.taiga.io/project/penpot/issue/1188)
- Fix show correct error when google auth is disabled [Taiga #1119](https://tree.taiga.io/project/penpot/issue/1119)
- Fix text alignment in preview [#594](https://github.com/penpot/penpot/issues/594)
- Fix unexpected exception when uploading image [Taiga #1120](https://tree.taiga.io/project/penpot/issue/1120)
- Fix updates on collaborative editing not updating selection rectangles [Taiga #1127](https://tree.taiga.io/project/penpot/issue/1127)
- Make the team deletion deferred (in the same way other objects)

### :heart: Community contributions by (Thank you!)

- abtinmo [#538](https://github.com/penpot/penpot/pull/538)
- kdrag0n [#585](https://github.com/penpot/penpot/pull/585)
- nisrulz [#586](https://github.com/penpot/penpot/pull/586)
- tomer [#575](https://github.com/penpot/penpot/pull/575)
- violoncelloCH [#554](https://github.com/penpot/penpot/pull/554)

## 1.1.0-alpha

- Bugfixing and stabilization post-launch
- Some changes to the register flow
- Improved MacOS shortcuts and helpers
- Small changes to shape creation


## 1.0.0-alpha

Initial release
