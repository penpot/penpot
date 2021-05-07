# CHANGELOG #

## :rocket: Next

### :sparkles: New features
### :bug: Bugs fixed

- Remove interactions when the destination artboard is deleted [Taiga #1656](https://tree.taiga.io/project/penpot/issue/1656)

### :arrow_up: Deps updates
### :boom: Breaking changes
### :heart: Community contributions by (Thank you!)

## 1.5.2-alpha

### :bug: Bugs fixed

- Fix problem with `close-path` command [#917](https://github.com/penpot/penpot/issues/917)
- Fix wrong query for obtain the profile default project-id
- Fix problems with empty paths and shortcuts [#923](https://github.com/penpot/penpot/issues/923)

## 1.5.1-alpha

### :bug: Bugs fixed

- Fix issue with bitmap image clipboard.
- Fix issue when removing all path points.
- Increase default team invitation token expiration to 48h.
- Fix wrong error message when an expired token is used.


## 1.5.0-alpha

### :sparkles: New features

- Add integration with gitpod.io (an online IDE) [#807](https://github.com/penpot/penpot/pull/807)
- Allow basic math operations in inputs [Taiga 1383](https://tree.taiga.io/project/penpot/us/1383)
- Autocomplete color names in hex inputs [Taiga 1596](https://tree.taiga.io/project/penpot/us/1596)
- Allow to group assets (components and graphics) [Taiga #1289](https://tree.taiga.io/project/penpot/us/1289)
- Change icon of pinned projects [Taiga 1298](https://tree.taiga.io/project/penpot/us/1298)
- Internal: refactor of http client, replace internal xhr usage with more modern Fetch API.
- New features for paths: snap points on edition, add/remove nodes, merge/join/split nodes. [Taiga #907](https://tree.taiga.io/project/penpot/us/907)
- Add OpenID-Connect support.
- Reimplement social auth providers on top of the generic openid impl.

### :bug: Bugs fixed

- Fix problem with pan and space [#811](https://github.com/penpot/penpot/issues/811)
- Fix issue when parsing exponential numbers in paths
- Remove legacy system user and team [#843](https://github.com/penpot/penpot/issues/843)
- Fix ordering of copy pasted objects [Taiga #1618](https://tree.taiga.io/project/penpot/issue/1617)
- Fix problems with blending modes [#837](https://github.com/penpot/penpot/issues/837)
- Fix problem with zoom an selection rect [#845](https://github.com/penpot/penpot/issues/845)
- Fix problem displaying team statistics [#859](https://github.com/penpot/penpot/issues/859)
- Fix problems with text editor selection [Taiga #1546](https://tree.taiga.io/project/penpot/issue/1546)
- Fix problem when opening the context menu in dashboard at the bottom [#856](https://github.com/penpot/penpot/issues/856)
- Fix problem when clicking an interactive group in view mode [#863](https://github.com/penpot/penpot/issues/863)
- Fix visibility of pages in sitemap when changing page [Taiga #1618](https://tree.taiga.io/project/penpot/issue/1618)
- Fix visual problem with group invite [Taiga #1290](https://tree.taiga.io/project/penpot/issue/1290)
- Fix issues with promote owner panel [Taiga #763](https://tree.taiga.io/project/penpot/issue/763)
- Allow use library colors when defining gradients [Taiga #1614](https://tree.taiga.io/project/penpot/issue/1614)
- Fix group selrect not updating after alignment [#895](https://github.com/penpot/penpot/issues/895)

### :arrow_up: Deps updates

### :boom: Breaking changes

- Translations refactor: now penpot uses gettext instead of a custom
  JSON, and each locale has its own separated file. All translations
  should be contributed via the weblate.org service.

### :heart: Community contributions by (Thank you!)

- madmath03 (by [Monogramm](https://github.com/Monogramm)) [#807](https://github.com/penpot/penpot/pull/807)
- zzkt [#814](https://github.com/penpot/penpot/pull/814)


## 1.4.1-alpha

### :bug: Bugs fixed

- Fix typography unlinking.
- Fix incorrect measures on shapes outside artboard.
- Fix issues on svg parsing related to numbers with exponents.
- Fix some race conditions on removing shape from workspace.
- Fix incorrect state management of user lang selection.
- Fix email validation usability issue on team invitation lightbox.


## 1.4.0-alpha

### :sparkles: New features

- Add blob-encoding v3 (uses ZSTD+transit) [#738](https://github.com/penpot/penpot/pull/738)
- Add http caching layer on top of Query RPC.
- Add layer opacity and blend mode to shapes [Taiga #937](https://tree.taiga.io/project/penpot/us/937)
- Add more chinese translations [#726](https://github.com/penpot/penpot/pull/726)
- Add native support for text-direction (RTL, LTR & auto).
- Add several enhancements in shape selection [Taiga #1195](https://tree.taiga.io/project/penpot/us/1195)
- Add thumbnail in memory caching mechanism.
- Add turkish translation strings [#759](https://github.com/penpot/penpot/pull/759), [#794](https://github.com/penpot/penpot/pull/794)
- Duplicate and move files and projects [Taiga #267](https://tree.taiga.io/project/penpot/us/267)
- Hide viewer navbar on fullscreen [Taiga 1375](https://tree.taiga.io/project/penpot/us/1375)
- Import SVG will create Penpot's shapes [Taiga #1006](https://tree.taiga.io/project/penpot/us/1066)
- Improve french translations [#731](https://github.com/penpot/penpot/pull/731)
- Reimplement workspace presence (remove database state).
- Remember last visited team when you re-enter the application [Taiga #1376](https://tree.taiga.io/project/penpot/us/1376)
- Rename artboard with double click on the title [Taiga #1392](https://tree.taiga.io/project/penpot/us/1392)
- Replace Slate-Editor with DraftJS [Taiga #1346](https://tree.taiga.io/project/penpot/us/1346)
- Set proper page title [Taiga #1377](https://tree.taiga.io/project/penpot/us/1377)


### :bug: Bugs fixed

- Disable buttons in view mode for users without permissions [Taiga #1328](https://tree.taiga.io/project/penpot/issue/1328)
- Fix broken profile and profile options form.
- Fix calculate size of some animated gifs [Taiga #1487](https://tree.taiga.io/project/penpot/issue/1487)
- Fix error with the "Navigate to" button on prototypes [Taiga #1268](https://tree.taiga.io/project/penpot/issue/1268)
- Fix issue when undo after changing the artboard of a shape [Taiga #1304](https://tree.taiga.io/project/penpot/issue/1304)
- Fix issue with Alt key in distance measurement [#672](https://github.com/penpot/penpot/issues/672)
- Fix issue with blending modes in masks [Taiga #1476](https://tree.taiga.io/project/penpot/issue/1476)
- Fix issue with blocked shapes [Taiga #1480](https://tree.taiga.io/project/penpot/issue/1480)
- Fix issue with comments styles on dashboard [Taiga #1405](https://tree.taiga.io/project/penpot/issue/1405)
- Fix issue with default square grid [Taiga #1344](https://tree.taiga.io/project/penpot/issue/1344)
- Fix issue with enter key shortcut [#775](https://github.com/penpot/penpot/issues/775)
- Fix issue with enter to edit paths [Taiga #1481](https://tree.taiga.io/project/penpot/issue/1481)
- Fix issue with mask and flip [#715](https://github.com/penpot/penpot/issues/715)
- Fix issue with masks interactions outside bounds [#718](https://github.com/penpot/penpot/issues/718)
- Fix issue with middle mouse button press moving the canvas when not moving mouse [#717](https://github.com/penpot/penpot/issues/717)
- Fix issue with resolved comments [Taiga #1406](https://tree.taiga.io/project/penpot/issue/1406)
- Fix issue with rotated blur [Taiga #1370](https://tree.taiga.io/project/penpot/issue/1370)
- Fix issue with rotation degree input [#741](https://github.com/penpot/penpot/issues/741)
- Fix issue with system shortcuts and application [#737](https://github.com/penpot/penpot/issues/737)
- Fix issue with team management in dashboard [Taiga #1475](https://tree.taiga.io/project/penpot/issue/1475)
- Fix issue with typographies panel cannot be collapsed [#707](https://github.com/penpot/penpot/issues/707)
- Fix text selection in comments [#745](https://github.com/penpot/penpot/issues/745)
- Update Work-Sans font [#744](https://github.com/penpot/penpot/issues/744)
- Fix issue with recent files not showing [Taiga #1493](https://tree.taiga.io/project/penpot/issue/1493)
- Fix issue when promoting to owner [Taiga #1494](https://tree.taiga.io/project/penpot/issue/1494)
- Fix cannot click on blocked elements in viewer [Taiga #1430](https://tree.taiga.io/project/penpot/issue/1430)
- Fix SVG not showing properties at code [Taiga #1437](https://tree.taiga.io/project/penpot/issue/1437)
- Fix shadows when exporting groups [Taiga #1495](https://tree.taiga.io/project/penpot/issue/1495)
- Fix drag-select when renaming layer text [Taiga #1307](https://tree.taiga.io/project/penpot/issue/1307)
- Fix layout problem for editable selects [Taiga #1488](https://tree.taiga.io/project/penpot/issue/1488)
- Fix artboard title wasn't move when resizing [Taiga #1479](https://tree.taiga.io/project/penpot/issue/1479)
- Fix titles in viewer thumbnails too long [Taiga #1474](https://tree.taiga.io/project/penpot/issue/1474)
- Fix when right click on a selected text shows artboard contextual menu [Taiga #1226](https://tree.taiga.io/project/penpot/issue/1226)

### :boom: Breaking changes

- The LDAP configuration variables interpolation starts using `:`
  (example `:username`) instead of `$`. The main reason is avoid
  unnecesary conflict with bash interpolation.


### :arrow_up: Deps updates

- Update backend to JDK16.
- Update exporter nodejs to v14.16.0


### :heart: Community contributions by (Thank you!)

- iblueer [#726](https://github.com/penpot/penpot/pull/726)
- gizembln [#759](https://github.com/penpot/penpot/pull/759)
- girafic [#748](https://github.com/penpot/penpot/pull/748)
- mbrksntrk [#794](https://github.com/penpot/penpot/pull/794)


## 1.3.0-alpha

### :sparkles: New features

- Add emailcatcher and ldap test containers to devenv. [#506](https://github.com/penpot/penpot/pull/506)
- Add major refactor of internal pubsub/redis code; improves scalability and performance [#640](https://github.com/penpot/penpot/pull/640)
- Add more chinese transtions [#687](https://github.com/penpot/penpot/pull/687)
- Add more presets for artboard [#654](https://github.com/penpot/penpot/pull/654)
- Add optional loki integration [#645](https://github.com/penpot/penpot/pull/645)
- Add proper http session lifecycle handling.
- Allow to set border radius of each rect corner individually
- Bounce & Complaint handling [#635](https://github.com/penpot/penpot/pull/635)
- Disable groups interactions when holding "Ctrl" key (deep selection)
- New action in context menu to "edit" some shapes (binded to key "Enter")


### :bug: Bugs fixed

- Add more improvements to french translation strings [#591](https://github.com/penpot/penpot/pull/591)
- Add some missing database indexes (mainly improves performance on large databases on file-update rpc method, and some background tasks).
- Disables filters in masking elements (issue with Firefox rendering)
- Drawing tool will have priority over resize/rotate handlers [Taiga #1225](https://tree.taiga.io/project/penpot/issue/1225)
- Fix broken bounding box on editing paths [Taiga #1254](https://tree.taiga.io/project/penpot/issue/1254)
- Fix corner cases on invitation/signup flows.
- Fix errors on onboarding file [Taiga #1287](https://tree.taiga.io/project/penpot/issue/1287)
- Fix infinite recursion on logout.
- Fix issues with frame selection [Taiga #1300](https://tree.taiga.io/project/penpot/issue/1300), [Taiga #1255](https://tree.taiga.io/project/penpot/issue/1255)
- Fix local fonts error [#691](https://github.com/penpot/penpot/issues/691)
- Fix issue width handoff code generation [Taiga #1204](https://tree.taiga.io/project/penpot/issue/1204)
- Fix issue with indices refreshing on page changes [#646](https://github.com/penpot/penpot/issues/646)
- Have language change notification written in the new language [Taiga #1205](https://tree.taiga.io/project/penpot/issue/1205)
- Hide register screen when registration is disabled [#598](https://github.com/penpot/penpot/issues/598)
- Properly handle errors on github, gitlab and ldap auth backends.
- Properly mark profile auth backend (on first register/ auth with 3rd party auth provider).
- Refactor LDAP auth backend.


### :heart: Community contributions by (Thank you!)

- girafic [#538](https://github.com/penpot/penpot/pull/654)
- arkhi [#591](https://github.com/penpot/penpot/pull/591)


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
- Fix issue when transforming path shapes [Taiga #1170](https://tree.taiga.io/project/penpot/issue/1170)
- Fix apply a color to a text selection from color palette was not working [Taiga #1189](https://tree.taiga.io/project/penpot/issue/1189)
- Fix issues when moving shapes outside groups [Taiga #1138](https://tree.taiga.io/project/penpot/issue/1138)
- Fix ldap function called on login click
- Fix logo icon in viewer should go to dashboard [Taiga #1149](https://tree.taiga.io/project/penpot/issue/1149)
- Fix ordering when restoring deleted shapes in sync [Taiga #1163](https://tree.taiga.io/project/penpot/issue/1163)
- Fix issue when editing text immediately after creating [Taiga #1207](https://tree.taiga.io/project/penpot/issue/1207)
- Fix issue when pasting URL's copied from the browser url bar [Taiga #1187](https://tree.taiga.io/project/penpot/issue/1187)
- Fix issue with multiple selection and groups [Taiga #1128](https://tree.taiga.io/project/penpot/issue/1128)
- Fix issue with red handler indicator on resize [Taiga #1188](https://tree.taiga.io/project/penpot/issue/1188)
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
