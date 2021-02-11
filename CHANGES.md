# CHANGELOG #

## Next

### New features

- Add images lock proportions by default [#541](https://github.com/penpot/penpot/discussions/541), [#609](https://github.com/penpot/penpot/issues/609)
- Shows a pixel grid when zoom greater than 800% [#519](https://github.com/penpot/penpot/discussions/519)
- Increase default deletion delay to 7 days
- Flip horizontal/vertical
- Zstd+nippy based blob storage format
- Improved component testing
- Add user feedback form
- Improved French translations

### Bugs fixed

- Make the team deletion defferred (in the same way other objects).
- Problems when transforming path shapes [Taiga #1170](https://tree.taiga.io/project/penpot/issue/1170)
- Fix 500 when requestion password reset
- Fix ldap function called on login click
- Fix issues when moving shapes outside groups [Taiga #1138](https://tree.taiga.io/project/penpot/issue/1138)
- Fix unexpected exception when uploading image [Taiga #1120](https://tree.taiga.io/project/penpot/issue/1120)
- Fix 404 when access shared link [#615](https://github.com/penpot/penpot/issues/615)
- Fix show correct error when google auth is disabled [Taiga #1119](https://tree.taiga.io/project/penpot/issue/1119)
- Fix apply a color to a text selection from color palette was not working [Taiga #1189](https://tree.taiga.io/project/penpot/issue/1189)
- Fix logo icon in viewer should go to dashboard [Taiga #1149](https://tree.taiga.io/project/penpot/issue/1149)
- Fix text alignment in preview [#594](https://github.com/penpot/penpot/issues/594)
- Fix problem when pasting URL's copied from the browser url bar [Taiga #1187](https://tree.taiga.io/project/penpot/issue/1187)
- Fix ordering when restoring deleted shapes in sync [Taiga #1163](https://tree.taiga.io/project/penpot/issue/1163)
- Fix updates on collaborative editing not updating selection rectangles [Taiga #1127](https://tree.taiga.io/project/penpot/issue/1127)
- Fix problem with multiple selection and groups [Taiga #1128](https://tree.taiga.io/project/penpot/issue/1128)
- Fix problem with red handler indicator on resize [Taiga #1188](https://tree.taiga.io/project/penpot/issue/1188)

### Community contributions by (Thank you! :heart:)

- nisrulz [#586](https://github.com/penpot/penpot/pull/586)
- kdrag0n [#585](https://github.com/penpot/penpot/pull/585)
- tomer [#575](https://github.com/penpot/penpot/pull/575)
- violoncelloCH [#554](https://github.com/penpot/penpot/pull/554)
- abtinmo [#538](https://github.com/penpot/penpot/pull/538)

## 1.1.0-alpha

- Bugfixing and stabilization post-launch
- Some changes to the register flow
- Improved MacOS shortcuts and helpers
- Small changes to shape creation


## 1.0.0-alpha

Initial release
