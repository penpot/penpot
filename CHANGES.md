# CHANGELOG

## :rocket: Next

### :boom: Breaking changes
### :sparkles: New features
### :bug: Bugs fixed
### :arrow_up: Deps updates
### :boom: Breaking changes
### :heart: Community contributions by (Thank you!)

## 1.8.1-alpha

### :bug: Bugs fixed

- Fix project renaming issue (and some other related to the same underlying bug).
- Fix internal exception on audit log persistence layer.
- Set proper environment variable on docker images for chrome executable.
- Fix internal metrics on websocket connections.


## 1.8.0-alpha

### :boom: Breaking changes

- This release includes a new approach for handling share links, and
  this feature is incompatible with the previous one. This means that
  all the public share links generated previously will stop working.

### :sparkles: New features

- Add tooltips to color picker tabs [Taiga #1814](https://tree.taiga.io/project/penpot/us/1814).
- Add styling to the end point of any open paths [Taiga #1107](https://tree.taiga.io/project/penpot/us/1107).
- Allow to zoom with ctrl + middle button [Taiga #1428](https://tree.taiga.io/project/penpot/us/1428).
- Auto placement of duplicated objects [Taiga #1386](https://tree.taiga.io/project/penpot/us/1386).
- Enable penpot SVG metadata only when exporting complete files [Taiga #1914](https://tree.taiga.io/project/penpot/us/1914?milestone=295883).
- Export to PDF all artboards of one page [Taiga #1895](https://tree.taiga.io/project/penpot/us/1895).
- Go to a undo step clicking on a history element of the list [Taiga #1374](https://tree.taiga.io/project/penpot/us/1374).
- Increment font size by 10 with shift+arrows [1047](https://github.com/penpot/penpot/issues/1047).
- New shortcut to detach components Ctrl+Shift+K [Taiga #1799](https://tree.taiga.io/project/penpot/us/1799).
- Set email inputs to type "email", to aid keyboard entry [Taiga #1921](https://tree.taiga.io/project/penpot/issue/1921).
- Use shift+move to move element orthogonally [#823](https://github.com/penpot/penpot/issues/823).
- Use space + mouse drag to pan, instead of only space [Taiga #1800](https://tree.taiga.io/project/penpot/us/1800).
- Allow navigate through pages on the viewer [Taiga #1550](https://tree.taiga.io/project/penpot/us/1550).
- Allow create share links with specific pages [Taiga #1844](https://tree.taiga.io/project/penpot/us/1844).

### :bug: Bugs fixed

- Prevent adding numeric suffix to layer names when not needed [Taiga #1929](https://tree.taiga.io/project/penpot/us/1929).
- Prevent deleting or moving the drafts project [Taiga #1935](https://tree.taiga.io/project/penpot/issue/1935).
- Fix problem with zoom and selection [Taiga #1919](https://tree.taiga.io/project/penpot/issue/1919)
- Fix problem with borders on shape export [#1092](https://github.com/penpot/penpot/issues/1092)
- Fix thumbnail cropping issue [Taiga #1964](https://tree.taiga.io/project/penpot/issue/1964)
- Fix repeated fetch on file selection [Taiga #1933](https://tree.taiga.io/project/penpot/issue/1933)
- Fix rename typography on text options [Taiga #1963](https://tree.taiga.io/project/penpot/issue/1963)
- Fix problems with order in groups [Taiga #1960](https://tree.taiga.io/project/penpot/issue/1960)
- Fix SVG components preview [#1134](https://github.com/penpot/penpot/issues/1134)
- Fix group renaming problem [Taiga #1969](https://tree.taiga.io/project/penpot/issue/1969)
- Fix problem with import broken images links [#1197](https://github.com/penpot/penpot/issues/1197)
- Fix problem while moving imported SVG's [#1199](https://github.com/penpot/penpot/issues/1199)

### :arrow_up: Deps updates
### :boom: Breaking changes
### :heart: Community contributions by (Thank you!)

- eduayme [#1129](https://github.com/penpot/penpot/pull/1129).


## 1.7.4-alpha

### :bug: Bugs fixed

- Fix demo user creation (self-hosted only)
- Add better ldap response validation and reporting (self-hosted only)


## 1.7.3-alpha

### :bug: Bugs fixed

- Fix font uploading issue on Windows.


## 1.7.2-alpha

### :sparkles: New features

- Add many improvements to text tool.

### :bug: Bugs fixed

- Add scroll bar to Teams menu [Taiga #1894](https://tree.taiga.io/project/penpot/issue/1894).
- Fix repeated names when duplicating artboards or groups [Taiga #1892](https://tree.taiga.io/project/penpot/issue/1892).
- Fix properly messages lifecycle on navigate.
- Fix handling repeated names on duplicate object trees.
- Fix group naming on group creation.
- Fix some issues in svg transformation.

### :arrow_up: Deps updates

- Update frontend build tooling.

### :heart: Community contributions by (Thank you!)

- soultipsy [#1100](https://github.com/penpot/penpot/pull/1100)


## 1.7.1-alpha

### :bug: Bugs fixed

- Fix issue related to the GC and images in path shapes.
- Fix issue on the shape order on some undo operations.
- Fix issue on undo page deletion.
- Fix some issues related to constraints.


## 1.7.0-alpha

### :sparkles: New features

- Allow nested asset groups [Taiga #1716](https://tree.taiga.io/project/penpot/us/1716).
- Allow to ungroup assets [Taiga #1719](https://tree.taiga.io/project/penpot/us/1719).
- Allow to rename assets groups [Taiga #1721](https://tree.taiga.io/project/penpot/us/1721).
- Component constraints (left, right, left and right, center, scale...) [Taiga #1125](https://tree.taiga.io/project/penpot/us/1125).
- Export elements to PDF [Taiga #519](https://tree.taiga.io/project/penpot/us/519).
- Memorize collapse state of assets in panel [Taiga #1718](https://tree.taiga.io/project/penpot/us/1718).
- Headers button sets and menus review [Taiga #1663](https://tree.taiga.io/project/penpot/us/1663).
- Preserve components if possible, when pasted into a different file [Taiga #1063](https://tree.taiga.io/project/penpot/issue/1063).
- Add the ability to offload file data to a cheaper storage when file becomes inactive.
- Import/Export Penpot files from dashboard.
- Double click won't make a shape a path until you change a node [Taiga #1796](https://tree.taiga.io/project/penpot/us/1796)
- Incremental area selection [#779](https://github.com/penpot/penpot/discussions/779)

### :bug: Bugs fixed

- Process numeric input changes only if the value actually changed.
- Remove unnecesary redirect from history when user goes to workspace from dashboard [Taiga #1820](https://tree.taiga.io/project/penpot/issue/1820).
- Detach shapes from deleted assets [Taiga #1850](https://tree.taiga.io/project/penpot/issue/1850).
- Fix tooltip position on view application [Taiga #1819](https://tree.taiga.io/project/penpot/issue/1819).
- Fix dashboard navigation on moving file to other team [Taiga #1817](https://tree.taiga.io/project/penpot/issue/1817).
- Fix workspace header presence styles and invalid link [Taiga #1813](https://tree.taiga.io/project/penpot/issue/1813).
- Fix color-input wrong behavior (on workspace page color) [Taiga #1795](https://tree.taiga.io/project/penpot/issue/1795).
- Fix file contextual menu in shared libraries at dashboard [Taiga #1865](https://tree.taiga.io/project/penpot/issue/1865).
- Fix problem with color picker and fonts [#1049](https://github.com/penpot/penpot/issues/1049)
- Fix negative values in blur [Taiga #1815](https://tree.taiga.io/project/penpot/issue/1815)
- Fix problem when editing color in group [Taiga #1816](https://tree.taiga.io/project/penpot/issue/1816)
- Fix resize/rotate with mouse buttons different than left [#1060](https://github.com/penpot/penpot/issues/1060)
- Fix header partialy visible on fullscreen viewer mode [Taiga #1875](https://tree.taiga.io/project/penpot/issue/1875)
- Fix dynamic alignment enabled with hidden objects [#1063](https://github.com/penpot/penpot/issues/1063)


## 1.6.5-alpha

### :bug: Bugs fixed

- Fix problem with paths editing after flip [#1040](https://github.com/penpot/penpot/issues/1040)

## 1.6.4-alpha

### :sparkles: Minor improvements

-  Decrease default bulk buffers on storage tasks.
-  Reduce file_change preserve interval to 24h.

### :bug: Bugs fixed

- Don't allow rename drafts project.
- Fix custom font deletion task.
- Fix custom font rendering on exporting shapes.
- Fix font loading on viewer app.
- Fix problem when moving files with drag & drop.
- Fix unexpected exception on searching without term.
- Properly handle nil values on `update-shapes` function.
- Replace frame term usage by artboard on viewer app.


## 1.6.3-alpha

### :bug: Bugs fixed

- Fix problem with merge and join nodes [#990](https://github.com/penpot/penpot/issues/990)
- Fix problem with empty path editing.
- Fix problem with create component.
- Fix problem with move-objects.
- Fix problem with merge and join nodes.

## 1.6.2-alpha

### :bug: Bugs fixed

- Add better auth module logging.
- Add missing `email` scope to OIDC backend.
- Add missing cause prop on error loging.
- Fix empty font-family handling on custom fonts page.
- Fix incorrect unicode code points handling on draft-to-penpot conversion.
- Fix some problems with paths.
- Fix unexpected exception on duplicate project.
- Fix unexpected exception when user leaves typography name empty.
- Improve error report on uploading invalid image to library.
- Minor fix on previous commit.
- Minor improvements on svg uploading on libraries.


## 1.6.1-alpha

### :bug: Bugs fixed

- Add safety check on reg-objects change impl.
- Fix custom fonts embbedding issue.
- Fix dashboard ordering issue.
- Fix problem when creating a component with empty data.
- Fix problem with moving shapes into frames.
- Fix problems with mov-objects.
- Fix unexpected excetion related to rounding integers.
- Fix wrong type usage on libraries changes.
- Improve editor lifecycle management.
- Make the navigation async by default.


## 1.6.0-alpha

### :sparkles: New features

- Add improved workspace font selector [Taiga US #292](https://tree.taiga.io/project/penpot/us/292).
- Add option to interactively scale text [Taiga #1527](https://tree.taiga.io/project/penpot/us/1527)
- Add performance improvements on dashboard data loading.
- Add performance improvements to indexes handling on workspace.
- Add the ability to upload/use custom fonts (and automatically generate all needed webfonts) [Taiga US #292](https://tree.taiga.io/project/penpot/us/292).
- Transform shapes to path on double click
- Translate automatic names of new files and projects.
- Use shift instead of ctrl/cmd to keep aspect ratio [Taiga 1697](https://tree.taiga.io/project/penpot/issue/1697).
- New translations: Portuguese (Brazil) and Romanias. 


### :bug: Bugs fixed

- Remove interactions when the destination artboard is deleted [Taiga #1656](https://tree.taiga.io/project/penpot/issue/1656).
- Fix problem with fonts that ends with numbers [#940](https://github.com/penpot/penpot/issues/940).
- Fix problem with imported SVG on editing paths [#971](https://github.com/penpot/penpot/issues/971)
- Fix problem with color picker positioning
- Fix order on color palette [#961](https://github.com/penpot/penpot/issues/961)
- Fix issue when group creation leaves an empty group [#1724](https://tree.taiga.io/project/penpot/issue/1724)
- Fix problem with :multiple for colors and typographies [#1668](https://tree.taiga.io/project/penpot/issue/1668)
- Fix problem with locked shapes when change parents [#974](https://github.com/penpot/penpot/issues/974)
- Fix problem with new nodes in paths [#978](https://github.com/penpot/penpot/issues/978)

### :arrow_up: Deps updates

- Update exporter dependencies (puppeteer), that fixes some unexpected exceptions.
- Update string manipulation library.


### :boom: Breaking changes

- The OIDC setting `PENPOT_OIDC_SCOPES` has changed the default semantics. Before this
  configuration added scopes to the default set. Now it replaces it, so use with care, because
  penpot requires at least `name` and `email` props found on the user info object.

### :heart: Community contributions by (Thank you!)

- Translations: Portuguese (Brazil) and Romanias.


## 1.5.4-alpha

### :bug: Bugs fixed

- Fix issues on group rendering.
- Fix problem with text editing auto-height [Taiga #1683](https://tree.taiga.io/project/penpot/issue/1683)


## 1.5.3-alpha

### :bug: Bugs fixed

- Fix problem undo/redo.

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
