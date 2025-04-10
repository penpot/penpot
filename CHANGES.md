# CHANGELOG

## 2.6.1

### :bug: Bugs fixed

- Fix webhooks not shown in list [Taiga #10763](https://tree.taiga.io/project/penpot/issue/10763)
- Fix colorpicker scroll when dropdown displayed [Taiga #10696](https://tree.taiga.io/project/penpot/issue/10696)
- Clean internal workspace state on exit or url changed [Taiga #10619](https://tree.taiga.io/project/penpot/issue/10619)

## 2.6.0

### :rocket: Epics and highlights

- Design Tokens

### :boom: Breaking changes & Deprecations

### :heart: Community contributions (Thank you!)

### :sparkles: New features

- [COMMENTS] "Mark All as Read" Functionality in Dashboard [Taiga #9235](https://tree.taiga.io/project/penpot/us/9235)
- [COMMENTS] Bubble Groups [Taiga #9236](https://tree.taiga.io/project/penpot/us/9236)
- Change templates carrousel [Taiga #9803](https://tree.taiga.io/project/penpot/us/9803)
- [DESIGN TOKENS] Tokens CRUD. Types added: Color, Opacity, Border radius, Dimension, Sizing, Spacing, Rotation and Stroke.
- [DESIGN TOKENS] Create references (alias) that point to other tokens.
- [DESIGN TOKENS] Math operations in token values.
- [DESIGN TOKENS] Sets CRUD, grouping and reordering.
- [DESIGN TOKENS] Multidimensional Themes and Sets management.
- [DESIGN TOKENS] Apply/Remove tokens to/from elements from the Tokens tab.
- [DESIGN TOKENS] Integration with components.
- [DESIGN TOKENS] Import and export tokens from a JSON file.
- [DESIGN TOKENS] Apply Themes and Sets at document level.
- Add more descriptive tooltip to boards for first time users [Taiga #9426](https://tree.taiga.io/project/penpot/us/9426)
- First State of a Project Changes Consolidation [Taia #10605](https://tree.taiga.io/project/penpot/us/10605)

### :bug: Bugs fixed

- Fix opacity in frame containers [Github #5858](https://github.com/penpot/penpot/pull/5858)
- Avoid resizing on click [Taiga #10213](https://tree.taiga.io/project/penpot/issue/10213)
- Hide horizontal scroll from dashboard sidebar [Taiga #10422](https://tree.taiga.io/project/penpot/issue/10422)
- Fix cut and paste a copy a cmponent inside its parent [Taiga #10365](https://tree.taiga.io/project/penpot/us/10365)
- Fix duplicate page with component over frame [Taiga #8151](https://tree.taiga.io/project/penpot/issue/8151) and [Taiga #9698](https://tree.taiga.io/project/penpot/issue/9698)
- The plugin list in the navigation menu lacks scrolling, some plugins are not visible when a large number are installed [Taiga #9360](https://tree.taiga.io/project/penpot/us/9360)
- Fix hidden toolbar click event still available [Taiga #10437](https://tree.taiga.io/project/penpot/us/10437)
- Fix hovering over templates [Taiga #10545](https://tree.taiga.io/project/penpot/issue/10545)
- Fix problem with default shadows value in plugins [Plugins #191](https://github.com/penpot/penpot-plugins/issues/191)
- Fix problem with constraints when creating group [Taiga #10455](https://tree.taiga.io/project/penpot/issue/10455)
- Fix opening pen with shortcut multiple times breaks toolbar [Taiga #10566](https://tree.taiga.io/project/penpot/issue/10566)
- Fix actions when workspace is visited first time [Taiga #10548](https://tree.taiga.io/project/penpot/issue/10548)
- Chat icon overlaps "Show" button in carrousel section [Taiga #10542](https://tree.taiga.io/project/penpot/issue/10542)
- Fix assets name on inspect tab [Taiga #10630](https://tree.taiga.io/project/penpot/issue/10630)
- Fix chat icon overlaps "Show" button in carrousel section [Taiga #10542](https://tree.taiga.io/project/penpot/issue/10542)
- Fix incorrect handling of background task result (now task rows are properly marked as completed)
- Fix available size of resize handler [Taiga #10639](https://tree.taiga.io/project/penpot/issue/10639)
- Internal error when install a plugin by penpothub - Try plugin [Taiga #10542](https://tree.taiga.io/project/penpot/issue/10542)
- Add character limitation to asset inputs [Taiga #10669](https://tree.taiga.io/project/penpot/issue/10669)
- Fix Storybook link 'list of all available icons' wrong path [Taiga #10705](https://tree.taiga.io/project/penpot/issue/10705)

## 2.5.4

### :heart: Community contributions (Thank you!)

- Add support for WEBP format on shape export [Github #6053](https://github.com/penpot/penpot/pull/6053) and [Github #6074](https://github.com/penpot/penpot/pull/6074)

### :bug: Bugs fixed

- Fix feature loading on workspace when opening a file in a background
  tab [Taiga #10377](https://tree.taiga.io/project/penpot/issue/10377)
- Fix minor inconsistencies on RPC `get-file-libraries` and `get-file`
  methods (add missing team-id prop)
- Fix problem with viewer role and inspect mode [Taiga #9751](https://tree.taiga.io/project/penpot/issue/9751)
- Fix error when clicking on a comment at the viewer's sidebar [Taiga #10465](https://tree.taiga.io/project/penpot/issue/10465)

## 2.5.3

### :bug: Bugs fixed

- Component sync issues with multiple tabs [Taiga #10471](https://tree.taiga.io/project/penpot/issue/10471)

## 2.5.2

### :sparkles: New features

- When the workspace is empty, set default the board creation tool [Taiga #9425](https://tree.taiga.io/project/penpot/us/9425)

### :bug: Bugs fixed

- Fix scroll on storybook docs [taiga #9962](https://tree.taiga.io/project/penpot/issue/9962)
- Navigate tracking event firing multiple times [Taiga #10415](https://tree.taiga.io/project/penpot/issue/10415)
- Fix problem with selection colors [Taiga #10376](https://tree.taiga.io/project/penpot/issue/10376)
- Fix scroll on storybook icons list [taiga #9962](https://tree.taiga.io/project/penpot/issue/9962)

## 2.5.1

### :sparkles: New features

- Improve Nginx entryponit to get the resolvers dinamically by default

## 2.5.0

### :boom: Breaking changes & Deprecations

Although this is not a breaking change, we believe it’s important to highlight it in this
section:

This release includes a fix for an internal bug in Penpot that caused incorrect handling
of media assets (e.g., fill images). The issue has been resolved since version 2.4.3, so
no new incorrect references will be generated. However, existing files may still contain
incorrect references.

To address this, we’ve provided a script to correct these references in existing files.

While having incorrect references generally doesn’t result in visible issues, there are
rare cases where it can cause problems. For example, if a component library (containing
images) is deleted, and that library is being used in other files, running the FileGC task
(responsible for freeing up space and performing logical deletions) could leave those
files with broken references to the images.

To execute script:

```bash
docker exec -ti <container-name-or-id> ./run.sh app.migrations.media-refs '{:max-jobs 1}'
```

If you have a big database and many cores available, you can reduce the time of processing
all files by increasing paralelizacion changing the `max-jobs` value from 1 to N (where N
is a number of cores)

### :sparkles: New features

- [GRADIENTS] New gradients UI with multi-stop support. [Taiga #3418](https://tree.taiga.io/project/penpot/epic/3418)
- [GRADIENTS] Radial Gradient [Taiga #8768](https://tree.taiga.io/project/penpot/us/8768)
- Shareable link pointing to an specific board. [Taiga #3219](https://tree.taiga.io/project/penpot/us/3219)
- Copy styles in CSS [Taiga #9401](https://tree.taiga.io/project/penpot/us/9401)
- Copy/paste shape styles (fills, strokes, shadows, etc..) [Taiga #8937](https://tree.taiga.io/project/penpot/us/8937)
- Copy text content to clipboard [Taiga #9970](https://tree.taiga.io/project/penpot/us/9970?milestone=424203)
- Resize board to fit content option [Taiga #4707](https://tree.taiga.io/project/penpot/us/4707)
- Rename selected layer via Board name [Taiga #9430](https://tree.taiga.io/project/penpot/us/9430)
- [COMMENTS] Mention Functionality with and Sidebar Filters [Taiga #9237](https://tree.taiga.io/project/penpot/us/9237)
- [COMMENTS] Visual Changes in Comments [Taiga #9234](https://tree.taiga.io/project/penpot/us/9234)
- [COMMENTS] Notifications in Backend, Profile Section, and Mention Email Notification [Taiga #9233](https://tree.taiga.io/project/penpot/us/9233)

### :bug: Bugs fixed

- Fix menu shadow color [Taiga #10102](https://tree.taiga.io/project/penpot/issue/10102)
- Fix missing state refresh on notifications update [Taiga #10253](https://tree.taiga.io/project/penpot/issue/10253)
- Fix icon visualization on select component [Taiga #8889](https://tree.taiga.io/project/penpot/issue/8889)
- Fix typo on integration tests docs [Taiga #10112](https://tree.taiga.io/project/penpot/issue/10112)
- Fix menu shadow color [Taiga #10102](https://tree.taiga.io/project/penpot/issue/10102)
- Fix problem with alt key measures being stuck [Taiga #9348](https://tree.taiga.io/project/penpot/issue/9348)
- Fix error when reseting stroke cap
- Fix problem with strokes not refreshing in Safari [Taiga #9040](https://tree.taiga.io/project/penpot/issue/9040)
- Fix problem with multiple color changes [Taiga #9631](https://tree.taiga.io/project/penpot/issue/9631)
- Fix create new layers in a component copy [Taiga #10037](https://tree.taiga.io/project/penpot/issue/10037)
- Fix problem in plugins with zoomIntoView [Plugins #189](https://github.com/penpot/penpot-plugins/issues/189)
- Fix problem in plugins with renaming components [Taiga #10060](https://tree.taiga.io/project/penpot/issue/10060)
- Added upload svg with images method [#5489](https://github.com/penpot/penpot/issues/5489)
- Fix problem with root frame parent reference [Taiga #9437](https://tree.taiga.io/project/penpot/issue/9437)
- Fix change flex direction using plugins API [Taiga #9407](https://tree.taiga.io/project/penpot/issue/9407)
- Fix problem opening url when page-id didn't exist [Taiga #10157](https://tree.taiga.io/project/penpot/issue/10157)
- Fix problem with onboarding to a team [Taiga #10143](https://tree.taiga.io/project/penpot/issue/10143)
- Fix problem with grid layout crashing [Taiga #10127](https://tree.taiga.io/project/penpot/issue/10127)
- Fix rename locked boards [Taiga #10174](https://tree.taiga.io/project/penpot/issue/10174)
- Fix update-libraries dialog disappear when clicking outside [Taiga #10238](https://tree.taiga.io/project/penpot/issue/10238)
- Fix incorrect handling of team access requests with deleted/recreated users
- Fix incorect handling of profile settings related to invitation notifications [Taiga #10252](https://tree.taiga.io/project/penpot/issue/10252)

## 2.4.3

### :bug: Bugs fixed

- Fix errors from editable select on measures menu [Taiga #9888](https://tree.taiga.io/project/penpot/issue/9888)
- Fix exception on importing some templates from templates slider
- Consolidate adding share button to workspace
- Fix problem when pasting text [Taiga #9929](https://tree.taiga.io/project/penpot/issue/9929)
- Fix incorrect media reference handling on component instantiation

## 2.4.2

### :bug: Bugs fixed

- Fix detach when top copy is dangling and nested copy is not [Taiga #9699](https://tree.taiga.io/project/penpot/issue/9699)
- Fix problem in plugins with `replaceColor` method [#174](https://github.com/penpot/penpot-plugins/issues/174)
- Fix issue with recursive commponents [Taiga #9903](https://tree.taiga.io/project/penpot/issue/9903)
- Fix missing methods reference on API Docs
- Fix memory usage issue on file-gc asynchronous task (related to snapshots feature)


## 2.4.1

### :bug: Bugs fixed

- Fix error when importing files with touched components [Taiga #9625](https://tree.taiga.io/project/penpot/issue/9625)
- Fix problem when changing color libraries [Plugins #184](https://github.com/penpot/penpot-plugins/issues/184)


## 2.4.0

### :rocket: Epics and highlights

### :boom: Breaking changes & Deprecations

- Use [nginx-unprivileged](https://hub.docker.com/r/nginxinc/nginx-unprivileged) as base image for
  Penpot's frontend docker image. Now all the docker images runs with the same unprivileged user
  (penpot). Because of that, the default NGINX listen port is now 8080 instead of 80, so
  you will have to modify your infrastructure to apply this change.

- Redis 7.2 is explicitly pinned in our example docker-compose.yml file. This is done because,
  starting with the next versions, Redis is no longer distributed under an open-source license.
  On-premise users are obviously free to upgrade to the version they are using or a more modern one.
  Keep in mind that if you were using a version other than 7.2, you may have to recreate the volume
  associated with the Redis container because the 7.2 storage format may not be compatible with what
  you already have stored on the volume, and Redis may not start. In the near future, we will evaluate
  whether to move to an open-source version of Redis (such as https://valkey.io/).

### :heart: Community contributions (Thank you!)

### :sparkles: New features

- Viewer role for team members [Taiga #1056](https://tree.taiga.io/project/penpot/us/1056) & [Taiga #6590](https://tree.taiga.io/project/penpot/us/6590)
- File history versions management [Taiga #187](https://tree.taiga.io/project/penpot/us/187?milestone=411120)
- Rename selected layer via keyboard shortcut and context menu option [Taiga #8882](https://tree.taiga.io/project/penpot/us/8882)
- New .penpot file format [Taiga #8657](https://tree.taiga.io/project/penpot/us/8657)

### :bug: Bugs fixed

- Fix problem with some texts desynchronization [Taiga #9379](https://tree.taiga.io/project/penpot/issue/9379)
- Fix problem with reoder grid layers [#5446](https://github.com/penpot/penpot/issues/5446)
- Fix problem with swap component style [#9542](https://tree.taiga.io/project/penpot/issue/9542)

## 2.3.3

### :bug: Bugs fixed

- Fix problem creating manual overlay interactions [Taiga #9146](https://tree.taiga.io/project/penpot/issue/9146)
- Fix plugins list default URL
- Activate plugins feature by default

## 2.3.2

### :bug: Bugs fixed

- Fix null pointer exception on number checking functions
- Fix problem with grid layout ordering after moving [Taiga #9179](https://tree.taiga.io/project/penpot/issue/9179)

### :books: Documentation

- Add initial documentation for Kubernetes


## 2.3.1

### :bug: Bugs fixed

- Fix unexpected issue on interaction between plugins sandbox and
  internal impl of promise


## 2.3.0

### :rocket: Epics and highlights

- **New plugin system.**

  Penpot now supports custom plugins. Read everything about developing your plugins [HERE](https://help.penpot.app/plugins/)

### :boom: Breaking changes & Deprecations

### :heart: Community contributions (Thank you!)

- All our plugins beta testers :heart:.
- Fix problem when translating multiple path points by @eeropic [#4459](https://github.com/penpot/penpot/issues/4459)

### :sparkles: New features

- **Replace Draft.js completely with a custom editor** [Taiga #7706](https://tree.taiga.io/project/penpot/us/7706)

  This refactor adds better IME support, more performant text editing
  experience and a better clipboard support while keeping full
  retrocompatibility with previous editor.

  You can enable it with the `enable-feature-text-editor-v2` configuration flag.


### :bug: Bugs fixed

- Fix problem with constraints buttons [Taiga #8465](https://tree.taiga.io/project/penpot/issue/8465)
- Fix problem with go back button on error page [Taiga #8887](https://tree.taiga.io/project/penpot/issue/8887)
- Fix problem with shadows in text for Safari [Taiga #8770](https://tree.taiga.io/project/penpot/issue/8770)
- Fix a regression with feedback form subject and content limits [Taiga #8908](https://tree.taiga.io/project/penpot/issue/8908)
- Fix problem with stroke and filter ordering in frames [Github #5058](https://github.com/penpot/penpot/issues/5058)
- Fix problem with hover layers when hidden/blocked [Github #5074](https://github.com/penpot/penpot/issues/5074)
- Fix problem with precision on boolean calculation [Taiga #8482](https://tree.taiga.io/project/penpot/issue/8482)
- Fix problem when translating multiple path points [Github #4459](https://github.com/penpot/penpot/issues/4459)
- Fix problem on importing (and exporting) files with flows [Taiga #8914](https://tree.taiga.io/project/penpot/issue/8914)
- Fix Internal Error page: "go to your penpot" wrong design [Taiga #8922](https://tree.taiga.io/project/penpot/issue/8922)
- Fix problem updating layout when toggle visibility in component copy [Github #5143](https://github.com/penpot/penpot/issues/5143)
- Fix "Done" button on toolbar on inspect mode should go to design mode [Taiga #8933](https://tree.taiga.io/project/penpot/issue/8933)
- Fix problem with shortcuts in text editor [Github #5078](https://github.com/penpot/penpot/issues/5078)
- Fix problems with show in viewer and interactions [Github #4868](https://github.com/penpot/penpot/issues/4868)
- Add visual feedback when moving an element into a board [Github #3210](https://github.com/penpot/penpot/issues/3210)
- Fix percent calculation on grid layout tracks [Github #4688](https://github.com/penpot/penpot/issues/4688)
- Fix problem with caps and inner shadows [Github #4517](https://github.com/penpot/penpot/issues/4517)
- Fix problem with horizontal/vertical lines and shadows [Github #4516](https://github.com/penpot/penpot/issues/4516)
- Fix problem with layers overflowing panel [Taiga #9021](https://tree.taiga.io/project/penpot/issue/9021)
- Fix in workspace you can manage rulers on view mode [Taiga #8966](https://tree.taiga.io/project/penpot/issue/8966)
- Fix problem with swap components in grid layout [Taiga #9066](https://tree.taiga.io/project/penpot/issue/9066)

## 2.2.1

### :bug: Bugs fixed

- Fix problem with Ctrl+F shortcut on the dashboard [Taiga #8876](https://tree.taiga.io/project/penpot/issue/8876)
- Fix visual problem with the font-size dropdown in assets [Taiga #8872](https://tree.taiga.io/project/penpot/issue/8872)
- Add limits for invitation RPC methods (hard limit 25 emails per request)

## 2.2.0

### :rocket: Epics and highlights

### :boom: Breaking changes & Deprecations

- Removed "merge assets" option when exporting ".svg + .json" files. After the components changes the option wasn't
working properly and we're planning to change the format soon. We think it's better to deprecate the option for the
time being.

### :heart: Community contributions (Thank you!)

- Set proper default tenant on exporter (by @june128) [#4946](https://github.com/penpot/penpot/pull/4946)
- Correct a spelling in onboarding.edn (by @n-stha) [#4936](https://github.com/penpot/penpot/pull/4936)

### :sparkles: New features

- **Tiered File Data Storage** [Taiga #8376](https://tree.taiga.io/project/penpot/us/8376)

  This feature allows offloading file data that is not actively used
  from the database to object storage (e.g., filesystem, S3), thereby
  freeing up space in the database. It can be enabled with the
  `enable-enable-tiered-file-data-storage` flag.

  *(On-Premise feature, EXPERIMENTAL).*

- **JSON Interoperability for HTTP API** [Taiga #8372](https://tree.taiga.io/project/penpot/us/8372)

  Enables full JSON interoperability for our HTTP API. Previously,
  JSON was only barely supported for output when the
  `application/json` media type was specified in the `Accept` header,
  or when `_fmt=json` was passed as a query parameter. With this
  update, we now offer proper bi-directional support for using our API
  with plain JSON, instead of Transit.

- **Automatic File Snapshotting**

  Adds the ability to automatically take and maintain a limited set of
  snapshots of active files without explicit user intervention. This
  feature allows on-premise administrators to recover the state of a
  file from a past point in time in a limited manner.

  It can be enabled with the `enable-auto-file-snapshot` flag and
  configured with the following settings:

  ```bash
  # Take snapshots every 10 update operations
  PENPOT_AUTO_FILE_SNAPSHOT_EVERY=10

  # Take a snapshot if it has been more than 3 hours since the file was last modified
  PENPOT_AUTO_FILE_SNAPSHOT_TIMEOUT=3h

  # The total number of snapshots to keep
  PENPOT_AUTO_FILE_SNAPSHOT_TOTAL=10
  ```

  Snapshots are only taken during update operations; there is NO
  active background process for this.

- Add separated flag `enable-oidc-registration` for enable the
  registration only for OIDC authentication backend [Github
  #4882](https://github.com/penpot/penpot/issues/4882)

- Update templates in libraries & templates in dashboard modal [Taiga #8145](https://tree.taiga.io/project/penpot/us/8145)

- **Design System**

  We implemented and subbed in new components from our Design System: `loader*` ([Taiga #8355](https://tree.taiga.io/project/penpot/task/8355))  and `tab-switcher*` ([Taiga #8518](https://tree.taiga.io/project/penpot/task/8518)).

- **Storybook** [Taiga #6329](https://tree.taiga.io/project/penpot/us/6329)

  The Design System components are now published in a Storybook, available at `/storybook`.

### :bug: Bugs fixed

- Fix webhook checkbox position [Taiga #8634](https://tree.taiga.io/project/penpot/issue/8634)
- Fix wrong props on padding input [Taiga #8254](https://tree.taiga.io/project/penpot/issue/8254)
- Fix fill collapsed options [Taiga #8351](https://tree.taiga.io/project/penpot/issue/8351)
- Fix scroll on color picker modal [Taiga #8353](https://tree.taiga.io/project/penpot/issue/8353)
- Fix components are not dragged from the group to the assets tab [Taiga #8273](https://tree.taiga.io/project/penpot/issue/8273)
- Fix problem with SVG import [Github #4888](https://github.com/penpot/penpot/issues/4888)
- Fix problem with overlay positions in viewer [Taiga #8464](https://tree.taiga.io/project/penpot/issue/8464)
- Fix layer panel overflowing [Taiga #8665](https://tree.taiga.io/project/penpot/issue/8665)
- Fix problem when creating a component instance from grid layout [Github #4881](https://github.com/penpot/penpot/issues/4881)
- Fix problem when dismissing shared library update [Taiga #8669](https://tree.taiga.io/project/penpot/issue/8669)
- Fix visual problem with stroke cap menu [Taiga #8730](https://tree.taiga.io/project/penpot/issue/8730)
- Fix issue when exporting libraries when merging libraries [Taiga #8758](https://tree.taiga.io/project/penpot/issue/8758)
- Fix problem with comments max length [Taiga #8778](https://tree.taiga.io/project/penpot/issue/8778)
- Fix copy/paste images in Safari [Taiga #8771](https://tree.taiga.io/project/penpot/issue/8771)
- Fix swap when the copy is the only child of a group [#5075](https://github.com/penpot/penpot/issues/5075)
- Fix file builder hangs when exporting [#5099](https://github.com/penpot/penpot/issues/5099)

## 2.1.5

### :bug: Bugs fixed

- Fix broken webhooks [Taiga #8370](https://tree.taiga.io/project/penpot/issue/8370)

## 2.1.4

### :bug: Bugs fixed

- Fix json encoding on zip encoding decoding.
- Add schema validation for color changes.
- Fix render of some texts without position data.

## 2.1.3

- Don't allow registration when registration is disabled and invitation token is used [Github #4975](https://github.com/penpot/penpot/issues/4975)

## 2.1.2

### :bug: Bugs fixed

- User switch language to "zh_hant" will get 400 [Github #4884](https://github.com/penpot/penpot/issues/4884)
- Smtp config ignoring port if ssl is set [Github #4872](https://github.com/penpot/penpot/issues/4872)
- Ability to let users to authenticate with a private oidc provider only [Github #4963](https://github.com/penpot/penpot/issues/4963)

## 2.1.1

### :sparkles: New features

- Consolidate templates new order and naming  [Taiga #8392](https://tree.taiga.io/project/penpot/task/8392)

### :bug: Bugs fixed

- Fix the “search” label in translations [Taiga #8402](https://tree.taiga.io/project/penpot/issue/8402)
- Fix pencil loader [Taiga #8348](https://tree.taiga.io/project/penpot/issue/8348)
- Fix several issues on the OIDC.
- Fix regression on the `email-verification` flag [Taiga #8398](https://tree.taiga.io/project/penpot/issue/8398)

## 2.1.0 - Things can only get better!

### :rocket: Epics and highlights

### :boom: Breaking changes & Deprecations

### :heart: Communityq contributions (Thank you!)

### :sparkles: New features

- Improve auth process [Taiga #7094](https://tree.taiga.io/project/penpot/us/7094)
- Add locking degrees increment (hold shift) on path edition [Taiga #7761](https://tree.taiga.io/project/penpot/issue/7761)
- Persistence & Concurrent Edition Enhancements [Taiga #5657](https://tree.taiga.io/project/penpot/us/5657)
- Allow library colors as recent colors [Taiga #7640](https://tree.taiga.io/project/penpot/issue/7640)
- Missing scroll in viewmode comments [Taiga #7427](https://tree.taiga.io/project/penpot/issue/7427)
- Comments in View mode should mimic the positioning behavior of the Workspace [Taiga #7346](https://tree.taiga.io/project/penpot/issue/7346)
- Misaligned input on comments [Taiga #7461](https://tree.taiga.io/project/penpot/issue/7461)

### :bug: Bugs fixed

- Fix selection rectangle appears on scroll [Taiga #7525](https://tree.taiga.io/project/penpot/issue/7525)
- Fix layer tree not expanding to the bottom edge [Taiga #7466](https://tree.taiga.io/project/penpot/issue/7466)
- Fix guides move when board is moved by inputs [Taiga #8010](https://tree.taiga.io/project/penpot/issue/8010)
- Fix clickable area of Penptot logo in the viewer [Taiga #7988](https://tree.taiga.io/project/penpot/issue/7988)
- Fix constraints dropdown when selecting multiple shapes [Taiga #7686](https://tree.taiga.io/project/penpot/issue/7686)
- Layout and scrollign fixes for the bottom palette [Taiga #7559](https://tree.taiga.io/project/penpot/issue/7559)
- Fix expand libraries when search results are present [Taiga #7876](https://tree.taiga.io/project/penpot/issue/7876)
- Fix color palette default library [Taiga #8029](https://tree.taiga.io/project/penpot/issue/8029)
- Component Library is lost after exporting/importing in .zip format [Github #4672](https://github.com/penpot/penpot/issues/4672)
- Fix problem with moving+selection not working properly [Taiga #7943](https://tree.taiga.io/project/penpot/issue/7943)
- Fix problem with flex layout fit to content not positioning correctly children [Taiga #7537](https://tree.taiga.io/project/penpot/issue/7537)
- Fix black line is displaying after show main [Taiga #7653](https://tree.taiga.io/project/penpot/issue/7653)
- Fix "Share prototypes" modal remains open [Taiga #7442](https://tree.taiga.io/project/penpot/issue/7442)
- Fix "Components visibility and opacity" [#4694](https://github.com/penpot/penpot/issues/4694)
- Fix "Attribute overrides in copies are not exported in zip file" [Taiga #8072](https://tree.taiga.io/project/penpot/issue/8072)
- Fix group not automatically selected in the Layers panel after creation [Taiga #8078](https://tree.taiga.io/project/penpot/issue/8078)
- Fix export boards loses opacity [Taiga #7592](https://tree.taiga.io/project/penpot/issue/7592)
- Fix change color on imported svg also changes the stroke alignment[Taiga #7673](https://github.com/penpot/penpot/pull/7673)
- Fix show in view mode and interactions workflow [Taiga #4711](https://github.com/penpot/penpot/pull/4711)
- Fix internal error when I set up a stroke for some objects without and with stroke [Taiga #7558](https://tree.taiga.io/project/penpot/issue/7558)
- Toolbar keeps toggling on and off on spacebar press [Taiga #7654](https://github.com/penpot/penpot/pull/7654)
- Fix toolbar keeps hiding when click outside workspace [Taiga #7776](https://tree.taiga.io/project/penpot/issue/7776)
- Fix open overlay relative to a frame [Taiga #7563](https://tree.taiga.io/project/penpot/issue/7563)
- Workspace-palette items stay hidden when opening with keyboard-shortcut [Taiga #7489](https://tree.taiga.io/project/penpot/issue/7489)
- Fix SVG attrs are not handled correctly when exporting/importing in .zip [Taiga #7920](https://tree.taiga.io/project/penpot/issue/7920)
- Fix validation error when detaching with two nested copies and a swap [Taiga #8095](https://tree.taiga.io/project/penpot/issue/8095)
- Export shapes that are rotated act a bit strange when reimported [Taiga #7585](https://tree.taiga.io/project/penpot/issue/7585)
- Penpot crashes when a new colorpicker is created while uploading an image to another instance [Taiga #8119](https://tree.taiga.io/project/penpot/issue/8119)
- Removing Underline and Strikethrough Affects the Previous Text Object [Taiga #8103](https://tree.taiga.io/project/penpot/issue/8103)
- Color library loses association with shapes when exporting/importing the document [Taiga #8132](https://tree.taiga.io/project/penpot/issue/8132)
- Fix can't collapse groups when searching in the assets tab [Taiga #8125](https://tree.taiga.io/project/penpot/issue/8125)
- Fix 'Detach instance' shortcut is not working [Taiga #8102](https://tree.taiga.io/project/penpot/issue/8102)
- Fix import file message does not detect 0 as error [Taiga #6824](https://tree.taiga.io/project/penpot/issue/6824)
- Image Color Library is not persisted when exporting/importing in .zip [Taiga #8131](https://tree.taiga.io/project/penpot/issue/8131)
- Fix export files including libraries [Taiga #8266](https://tree.taiga.io/project/penpot/issue/8266)

## 2.0.3

### :bug: Bugs fixed

- Fix chrome scrollbar styling [Taiga #7852](https://tree.taiga.io/project/penpot/issue/7852)
- Fix incorrect password encoding on create-profile manage scritp [Github #3651](https://github.com/penpot/penpot/issues/3651)

## 2.0.2

### :sparkles: Enhancements

- Fix locking contention on cron subsystem (causes backend start blocking)
- Fix locking contention on file object thumbails backend RPC calls

### :bug: Bugs fixed

- Fix color palette sorting [Taiga #7458](https://tree.taiga.io/project/penpot/issue/7458)
- Fix style scoping problem with imported SVG [Taiga #7671](https://tree.taiga.io/project/penpot/issue/7671)


## 2.0.1

### :bug: Bugs fixed

- Fix different issues related to components v2 migrations including [Github #4443](https://github.com/penpot/penpot/issues/4443)


## 2.0.0 - I Just Can't Get Enough

### :rocket: Epics and highlights
- Grid CSS layout [Taiga #4915](https://tree.taiga.io/project/penpot/epic/4915)
- UI redesign [Taiga #4958](https://tree.taiga.io/project/penpot/epic/4958)
- New components System [Taiga #2662](https://tree.taiga.io/project/penpot/epic/2662)
- Swap components [Taiga #1331](https://tree.taiga.io/project/penpot/us/1331)
- Images as fill  [Taiga #2983](https://tree.taiga.io/project/penpot/us/2983)
- HTML code generation [Taiga #5277](https://tree.taiga.io/project/penpot/us/5277)
- Light and dark themes [Taiga #2287](https://tree.taiga.io/project/penpot/us/2287)

### :boom: Breaking changes & Deprecations

- New strokes default to inside border [Taiga #6847](https://tree.taiga.io/project/penpot/issue/6847)
- Change default z ordering on layers in flex layout. The previous behavior was inconsistent with how HTML works and we changed it to be more consistent. Previous layers that overlapped could be hidden, the fastest way to fix this is changing the z-index property but a better way is to change the order of your layers.


### :heart: Community contributions (Thank you!)
- New Hausa, Yoruba and Igbo translations and  update translation files (by All For Tech Empowerment Foundation) [Taiga #6950](https://tree.taiga.io/project/penpot/us/6950), [Taiga #6534](https://tree.taiga.io/project/penpot/us/6534)
- Hide bounding-box when editing shape (by @VasilevsVV) [#3930](https://github.com/penpot/penpot/pull/3930)
- CTRL + "+" to zoom into canvas instead of browser (by @audriu) [#3848](https://github.com/penpot/penpot/pull/3848)
- Add dev deps.edn in the project root (by @PEZ) [#3794](https://github.com/penpot/penpot/pull/3794)
- Allow passing overrides to frontend nginx config (by @m90) [#3602](https://github.com/penpot/penpot/pull/3602)
- Update index.njk to remove typo (by @fdvmoreira) [#155](https://github.com/penpot/penpot-docs/pull/155)
- Typo (by StephanEggermont) [#157](https://github.com/penpot/penpot-docs/pull/157)

### :sparkles: New features
- Send comments with Ctrl+Enter / Cmd + Enter [Taiga #6085](https://tree.taiga.io/project/penpot/issue/6085)
- Select through stroke only rectangle [Taiga #5484](https://tree.taiga.io/project/penpot/issue/5484)
- Stroke default position [Taiga #6847](https://tree.taiga.io/project/penpot/issue/6847)
- Override browser Ctrl+ and Ctrl- zoom with Penpot Zoom [Taiga #3200](https://tree.taiga.io/project/penpot/us/3200)
- Improve the way handlers work on flex layouts [Taiga #6598](https://tree.taiga.io/project/penpot/us/6598)
- Add menu entry for toggle between light/dark theme [Taiga #6829](https://tree.taiga.io/project/penpot/issue/6829)
- Switch themes shortcut [Taiga #6644](https://tree.taiga.io/project/penpot/us/6644)
- Constraints section at design tab new position [Taiga #6830](https://tree.taiga.io/project/penpot/issue/6830)
- [PICKER] File library colors order [Taiga #5399](https://tree.taiga.io/project/penpot/us/5399)
- Onboarding invitations improvements [Taiga #5974](https://tree.taiga.io/project/penpot/us/5974)
- [PERFORMANCE] Workspace thumbnails refactor [Taiga #5828](https://tree.taiga.io/project/penpot/us/5828)
- [PERFORMANCE] Add performance optimizations to shape rendering [Taiga #5835](https://tree.taiga.io/project/penpot/us/5835)
- [PERFORMANCE] Optimize SVG output [Taiga #4134](https://tree.taiga.io/project/penpot/us/4134)
- [PERFORMANCE] Optimize svg on importation [Taiga #5879](https://tree.taiga.io/project/penpot/us/5879)
- [PERFORMANCE] Optimization tasks related to design tab file [Taiga #5760](https://tree.taiga.io/project/penpot/us/5760)
- [INSTALL] Ability to setup features by team [Taiga #6108](https://tree.taiga.io/project/penpot/us/6108)
- [IMAGES] Keep aspect ratio option [Taiga #6933](https://tree.taiga.io/project/penpot/us/6933)
- [INSPECT] UI review [Taiga #5687](https://tree.taiga.io/project/penpot/us/5687)
- [GRID LAYOUT] Phase 1 [Taiga #4303](https://tree.taiga.io/project/penpot/us/4303)
- [GRID LAYOUT] Inspect code for Grid [Taiga #5277](https://tree.taiga.io/project/penpot/us/5277)
- [GRID LAYOUT] Phase 1 polishing [Taiga #5612](https://tree.taiga.io/project/penpot/us/5612)
- [GRID LAYOUT] Improvements & Feedback [Taiga #6047](https://tree.taiga.io/project/penpot/us/6047)
- [COMPONENTS] Naming of the main component [Taiga #5291](https://tree.taiga.io/project/penpot/us/5291)
- [COMPONENTS] Rework inside of components - Library page [Taiga #2918](https://tree.taiga.io/project/penpot/us/2918)
- [COMPONENTS] Update component when updating main instance [Taiga #3794](https://tree.taiga.io/project/penpot/us/3794)
- [COMPONENTS] Main component new behavior [Taiga #3796](https://tree.taiga.io/project/penpot/us/3796)
- [COMPONENTS] Main component look & feel [Taiga #5290](https://tree.taiga.io/project/penpot/us/5290)
- [COMPONENTS] Library view [Taiga #2880](https://tree.taiga.io/project/penpot/us/2880)
- [COMPONENTS] Positioning inside a component should relative, as in boards [Taiga #2826](https://tree.taiga.io/project/penpot/us/2826)
- [COMPONENTS] Update message should show only if affecting at components that are being used at a file [Taiga #1397](https://tree.taiga.io/project/penpot/us/1397)
- [COMPONENTS] Annotations [Taiga #4957](https://tree.taiga.io/project/penpot/us/4957)
- [COMPONENTS] Synchronization order for nested components [Taiga #5439](https://tree.taiga.io/project/penpot/us/5439)
- [COMPONENTS] Libraries modal zero case [Taiga #5294](https://tree.taiga.io/project/penpot/us/5294)
- [COMPONENTS] Contextual menu casuistics [Taiga #5292](https://tree.taiga.io/project/penpot/us/5292)
- [COMPONENTS] Libraries publishing flow review [Taiga #5293](https://tree.taiga.io/project/penpot/us/5293)
- [COMPONENTS] Add loading text to Libraries modal [Taiga #6702](https://tree.taiga.io/project/penpot/us/6702)
- [COMPONENTS] Components rename and organization in bulk [Taiga #2877](https://tree.taiga.io/project/penpot/us/2877)
- [COMPONENTS] Info overlay about components V2 [Taiga #6276](https://tree.taiga.io/project/penpot/us/6276)
- [REDESIGN] New styles basics [Taiga #4967](https://tree.taiga.io/project/penpot/us/4967)
- [REDESIGN] Layers tab redesign [Taiga #4966](https://tree.taiga.io/project/penpot/us/4966)
- [REDESIGN] Design tab phase 1 [Taiga #4982](https://tree.taiga.io/project/penpot/us/4966)
- [REDESIGN] Assets tab redesign [Taiga #4984](https://tree.taiga.io/project/penpot/us/4984)
- [REDESIGN] Palette panels (colors, typographies...) [Taiga #4983](https://tree.taiga.io/project/penpot/us/4983)
- [REDESIGN] Workspace structure [Taiga #4988](https://tree.taiga.io/project/penpot/us/4988)
- [REDESIGN] Shortcut tab [Taiga #4989](https://tree.taiga.io/project/penpot/us/4989)
- [REDESIGN] Toolbar [Taiga #5500](https://tree.taiga.io/project/penpot/us/5500)
- [REDESIGN] History tab [Taiga #5481](https://tree.taiga.io/project/penpot/us/5481)
- [REDESIGN] Path options/toolbar [Taiga #5815](https://tree.taiga.io/project/penpot/us/5815)
- [REDESIGN] Design tab phase 2 [Taiga #5814](https://tree.taiga.io/project/penpot/us/5814)
- [REDESIGN] Design tab phase 3 and dashboard details [Taiga #5920](https://tree.taiga.io/project/penpot/us/5920)
- [REDESIGN] Dashboard [Taiga #5164](https://tree.taiga.io/project/penpot/us/5164)
- [REDESIGN] New Dashboard UI [Taiga #5869](https://tree.taiga.io/project/penpot/us/5869)
- [REDESIGN] Prototype tab [Taiga #4985](https://tree.taiga.io/project/penpot/us/4985)
- [REDESIGN] Code tab [Taiga #4986](https://tree.taiga.io/project/penpot/us/4986)
- [REDESIGN] Modals and alert messages [Taiga #5915](https://tree.taiga.io/project/penpot/us/5915)
- [REDESIGN] Comments page [Taiga #5917](https://tree.taiga.io/project/penpot/us/5917)
- [REDESIGN] View Mode [Taiga #5163](https://tree.taiga.io/project/penpot/us/5163)
- [REDESIGN] Miscellaneous tasks [Taiga #6050](https://tree.taiga.io/project/penpot/us/6050)
- [REDESIGN] Swap components [Taiga #6739](https://tree.taiga.io/project/penpot/us/6739)
- [REDESIGN] Font selector [Taiga #6677](https://tree.taiga.io/project/penpot/us/6677)
- [REDESIGN] Colour system of alerts and notifications [Taiga #6746](https://tree.taiga.io/project/penpot/us/6746)
- [REDESIGN] Review text in paragraphs for accessibility [Taiga #6703](https://tree.taiga.io/project/penpot/us/6703)
- [REDESIGN] Interaction icons [Taiga #6880](https://tree.taiga.io/project/penpot/us/6880)
- [REDESIGN] Panels visual separations [Taiga #6692](https://tree.taiga.io/project/penpot/us/6692)
- [REDESIGN] Onboarding slides [Taiga #6678](https://tree.taiga.io/project/penpot/us/6678)

### :bug: Bugs fixed
- Fix pixelated thumbnails [Github #3681](https://github.com/penpot/penpot/issues/3681), [Github #3661](https://github.com/penpot/penpot/issues/3661)
- Fix problem with not applying colors to boards [Github #3941](https://github.com/penpot/penpot/issues/3941)
- Fix problem with path editor undoing changes [Github #3998](https://github.com/penpot/penpot/issues/3998)
- [View mode] Open overlay places frame in the wrong position when paired with a fixed element [Taiga #6385](https://tree.taiga.io/project/penpot/issue/6385)
- Flex Layout: Fit-content not recalculated after deleting an element [Taiga #5968](https://tree.taiga.io/project/penpot/issue/5968)
- Selecting from Color Palette does not work for board when there is no existing fill [Taiga #6464](https://tree.taiga.io/project/penpot/issue/6464)
- Color thumbnails are consistently rounded in the inspect code mode [Taiga #5886](https://tree.taiga.io/project/penpot/issue/5886)
- Adding vector path points before first point of existing open path not working [Taiga #6593](https://tree.taiga.io/project/penpot/issue/6593)
- Some image formats include the extension when importing  [Taiga #5485](https://tree.taiga.io/project/penpot/issue/5485)
- Gradient color tool doesn't work properly with flipped items [Taiga #6485](https://tree.taiga.io/project/penpot/issue/6485)
- [TEXT] Align options are not shown when several text are selected [Taiga #5948](https://tree.taiga.io/project/penpot/issue/5948)
- [VIEW MODE] Comments not working properly on multiple pages [Taiga #6281](https://tree.taiga.io/project/penpot/issue/6281)
- [PERFORMANCE] Alignments are slow [Taiga #5865](https://tree.taiga.io/project/penpot/issue/5865)
- [EXPORT] Exporting an element with a non-visible drop shadow displays the shadow either way [Taiga #6768](https://tree.taiga.io/project/penpot/issue/6768)
- [SAFARI] Color picker cursor is not pointing correctly [Taiga #6733](https://tree.taiga.io/project/penpot/issue/6733)
- [Import Files] When user has imported .penpot file with new file name of previously downloaded library file the default library file name is set for it [Taiga #5596](https://tree.taiga.io/project/penpot/issue/5596)
- Issue when resizing a duotone FA icon [Taiga #5935](https://tree.taiga.io/project/penpot/issue/5935)
- "Hide grid" keyboard shortcut broken [Taiga #5102](https://tree.taiga.io/project/penpot/issue/5102)
- Picking a gradient color in recent colors for a new color in the assets tab crashes Penpot [Taiga #5601](https://tree.taiga.io/project/penpot/issue/5601)
- Thumbnails not loading [Taiga #6012](https://tree.taiga.io/project/penpot/issue/6012)
- Don't show signup link/form when registration is disabled. [Taiga #1196](https://tree.taiga.io/project/penpot/issue/1196)
- Registration Page UI UX issue with small resolutions [Taiga #1693](https://tree.taiga.io/project/penpot/issue/1693)
- [LOGIN] "E-Mail-Adress" input field is set to type 'text' instead of 'eMail [Taiga #1921](https://tree.taiga.io/project/penpot/issue/1921)
- Handling correctly slashes "/" in emails [Taiga #4906](https://tree.taiga.io/project/penpot/issue/4906)
- Tab character in texts crashes the app [Taiga #4418](https://tree.taiga.io/project/penpot/issue/4418)
- Text does not match export [Taiga #4129](https://tree.taiga.io/project/penpot/issue/4129)
- Scrollbars cover the layers carets [Taiga #4431](https://tree.taiga.io/project/penpot/issue/4431)
- Horizontal ruler disappear when overlapping a board [Taiga #4138](https://tree.taiga.io/project/penpot/issue/4138)
- Resize shape + Alt key is not working [Taiga #3447](https://tree.taiga.io/project/penpot/issue/3447)
- Libraries images broken on premise [Taiga #4573](https://tree.taiga.io/project/penpot/issue/4573)
- [VIEWER] Cannot scroll down in code </> mode [Taiga #4655](https://tree.taiga.io/project/penpot/issue/4655)
- Strange cursor behavior after clicking viewport with text tool [Taiga #4363](https://tree.taiga.io/project/penpot/issue/4363)
- Selected color affects all of them [Taiga #5285](https://tree.taiga.io/project/penpot/issue/5285)
- Fix problem with shadow negative spread [Github #3421](https://github.com/penpot/penpot/issues/3421)
- Fix problem with linked colors to strokes [Github #3522](https://github.com/penpot/penpot/issues/3522)
- Fix problem with hand tool stuck [Github #3318](https://github.com/penpot/penpot/issues/3318)
- Fix problem with fix scrolling on nested elements [Github #3508](https://github.com/penpot/penpot/issues/3508)
- Fix problem when changing typography assets [Github #3683](https://github.com/penpot/penpot/issues/3683)
- Internal error when you copy and paste some main components between files [Taiga #7397](https://tree.taiga.io/project/penpot/issue/7397)
- Fix toolbar disappearing [Taiga #7411](https://tree.taiga.io/project/penpot/issue/7411)
- Fix long text on tab breaks UI [Taiga #7421](https://tree.taiga.io/project/penpot/issue/7421)

## 1.19.5

### :bug: New features

- Fix problem with alignment performance

## 1.19.4

### :sparkles: New features

- Improve selected colors [Taiga #5805]( https://tree.taiga.io/project/penpot/us/5805)

### :bug: Bugs fixed

- Fix problem with z-index field in non-absolute items

## 1.19.3

### :sparkles: New features

- Remember last color mode in colorpicker [Taiga #5508](https://tree.taiga.io/project/penpot/issue/5508)
- Improve layers multiselection behaviour [Github #5741](https://github.com/penpot/penpot/issues/5741)
- Remember last active team across logouts / sessions [Github #3325](https://github.com/penpot/penpot/issues/3325)

### :bug: Bugs fixed

- List view is discarded on tab change on Workspace Assets Sidebar tab [Github #3547](https://github.com/penpot/penpot/issues/3547)
- Fix message popup remains open when exiting workspace with browser back button [Taiga #5747](https://tree.taiga.io/project/penpot/issue/5747)
- When editing text if font is changed, the proportions of the rendered shape are wrong [Taiga #5786](https://tree.taiga.io/project/penpot/issue/5786)

## 1.19.2

### :sparkles: New features

- Navigate up in layer hierarchy with Shift+Enter shortcut [Taiga #5734](https://tree.taiga.io/project/penpot/us/5734)
- Click on the flow tags open viewer with the selected frame [Taiga #5044](https://tree.taiga.io/project/penpot/us/5044)
- Add Dutch language & update translation files with weblate

### :bug: Bugs fixed

- Fix unexpected output on get-page rpc method when invalid object-id is provided [Github #3546](https://github.com/penpot/penpot/issues/3546)
- Fix Invalid files amount after moving file from Project to Drafts [Taiga #5638](https://tree.taiga.io/project/penpot/us/5638)
- Fix deleted pages comments shown in right sidebar [Taiga #5648](https://tree.taiga.io/project/penpot/us/5648)
- Fix tooltip on toggle visibility and toggle lock buttons [Taiga #5141](https://tree.taiga.io/project/penpot/issue/5141)


## 1.19.1

### :bug: Bugs fixed

- Fix components not registered as updated [Taiga #5725](https://tree.taiga.io/project/penpot/issue/5725)

## 1.19.0

### :boom: Breaking changes & Deprecations

### :sparkles: New features

- Default naming of text layers [Taiga #2836](https://tree.taiga.io/project/penpot/us/2836)
- Create typography style from a selected text layer [Taiga #3041](https://tree.taiga.io/project/penpot/us/3041)
- Board as ruler origin [Taiga #4833](https://tree.taiga.io/project/penpot/us/4833)
- Access tokens support [Taiga #4460](https://tree.taiga.io/project/penpot/us/4460)
- Show interactions setting at the view mode [Taiga #1330](https://tree.taiga.io/project/penpot/issue/1330)
- Improve dashboard performance related to thumbnails; now the thumbnails are
  rendered as bitmap images.
- Add the ability to disable google fonts provider with the `disable-google-fonts-provider` flag
- Add the ability to disable dashboard templates section with the `disable-dashboard-templates-section` flag
- Add the ability to use the registration whitelist with OICD [Github #3348](https://github.com/penpot/penpot/issues/3348)
- Add support for local caching of google fonts (this avoids exposing the final user IP to
  goolge and reduces the amount of request sent to google)
- Set smooth/instant autoscroll depending on distance [GitHub #3377](https://github.com/penpot/penpot/issues/3377)
- New component icon [Taiga #5290](https://tree.taiga.io/project/penpot/us/5290)
- Show a confirmation dialog when an user tries to publish an empty library [Taiga #5294](https://tree.taiga.io/project/penpot/us/5294)

### :bug: Bugs fixed

- Fix files can be opened from multiple urls [Taiga #5310](https://tree.taiga.io/project/penpot/issue/5310)
- Fix asset color item was created from the selected layer [Taiga #5180](https://tree.taiga.io/project/penpot/issue/5180)
- Fix unpublish more than one library at the same time [Taiga #5532](https://tree.taiga.io/project/penpot/issue/5532)
- Fix drag projects on dahsboard [Taiga #5531](https://tree.taiga.io/project/penpot/issue/5531)
- Fix allow team name to be all blank [Taiga #5527](https://tree.taiga.io/project/penpot/issue/5527)
- Fix search font visualitation [Taiga #5523](https://tree.taiga.io/project/penpot/issue/5523)
- Fix create and account only with spaces [Taiga #5518](https://tree.taiga.io/project/penpot/issue/5518)
- Fix context menu outside screen [Taiga #5524](https://tree.taiga.io/project/penpot/issue/5524)
- Fix graphic item rename on assets pannel [Taiga #5556](https://tree.taiga.io/project/penpot/issue/5556)
- Fix component and media name validation on assets panel [Taiga #5555](https://tree.taiga.io/project/penpot/issue/5555)
- Fix problem with selection shortcuts [Taiga #5492](https://tree.taiga.io/project/penpot/issue/5492)
- Fix issue with paths line to curve and concurrent editing [Taiga #5191](https://tree.taiga.io/project/penpot/issue/5191)
- Fix problems with locked layers [Taiga #5139](https://tree.taiga.io/project/penpot/issue/5139)
- Fix export from shared prototype [Taiga #5565](https://tree.taiga.io/project/penpot/issue/5565)
- Fix email change: validation error displaying even after both fields are identical [Taiga #5514](https://tree.taiga.io/project/penpot/issue/5514)
- Fix scroll on viewer comment list [Taiga #5563](https://tree.taiga.io/project/penpot/issue/5563)
- Fix context menu z-index [Taiga #5561](https://tree.taiga.io/project/penpot/issue/5561)
- Fix select all checkbox on shared link config [Taiga #5566](https://tree.taiga.io/project/penpot/issue/5566)
- Fix validation on full name input on account creation [Taiga #5516](https://tree.taiga.io/project/penpot/issue/5516)
- Fix validation on team name input [Taiga #5510](https://tree.taiga.io/project/penpot/issue/5510)
- Fix incorrect uri generation issues on share-link modal [Taiga #5564](https://tree.taiga.io/project/penpot/issue/5564)
- Fix cache issues with share-links [Taiga #5559](https://tree.taiga.io/project/penpot/issue/5559)
- Makes height priority for the rows/columns grids [#2774](https://github.com/penpot/penpot/issues/2774)
- Fix problem with comments mode not staying [#3363](https://github.com/penpot/penpot/issues/3363)
- Fix problem with comments when user left the team [Taiga #5562](https://tree.taiga.io/project/penpot/issue/5562)
- Fix problem with images patterns repeating [#3372](https://github.com/penpot/penpot/issues/3372)
- Fix grid not being clipped in frames [#3365](https://github.com/penpot/penpot/issues/3365)
- Fix cut/delete text layer when while creating text [Taiga #5602](https://tree.taiga.io/project/penpot/issue/5602)
- Fix picking a gradient color in recent colors for a new color in the assets tab [Taiga #5601](https://tree.taiga.io/project/penpot/issue/5601)
- Fix problem with importation process [Taiga #5597](https://tree.taiga.io/project/penpot/issue/5597)
- Fix problem with HSV color picker [#3317](https://github.com/penpot/penpot/issues/3317)
- Fix problem with slashes in layers names for exporter [#3276](https://github.com/penpot/penpot/issues/3276)
- Fix incorrect modified data on moving files on dashboard [Taiga #5530](https://tree.taiga.io/project/penpot/issue/5530)
- Fix focus handling on comments edition [Taiga #5560](https://tree.taiga.io/project/penpot/issue/5560)
- Fix incorrect fullname use on registring user after OIDC authentication [Taiga #5517](https://tree.taiga.io/project/penpot/issue/5517)
- Fix incorrect modified-at on project after import file [Taiga #5268](https://tree.taiga.io/project/penpot/issue/5268)
- Fix incorrect message after sending invitation to already member [Taiga 5599](https://tree.taiga.io/project/penpot/issue/5599)
- Fix text decoration on button [Taiga #5301](https://tree.taiga.io/project/penpot/issue/5301)
- Fix menu order on design tab [Taiga #5195](https://tree.taiga.io/project/penpot/issue/5195)
- Fix search bar width on layer tab [Taiga #5445](https://tree.taiga.io/project/penpot/issue/5445)
- Fix border radius values with decimals [Taiga #5283](https://tree.taiga.io/project/penpot/issue/5283)
- Fix shortcuts translations not homogenized [Taiga #5141](https://tree.taiga.io/project/penpot/issue/5141)
- Fix overlay manual position in nested boards [Taiga #5135](https://tree.taiga.io/project/penpot/issue/5135)
- Fix close overlay from a nested board [Taiga #5587](https://tree.taiga.io/project/penpot/issue/5587)
- Fix overlay position when it has shadow or blur [Taiga #4752](https://tree.taiga.io/project/penpot/issue/4752)
- Fix overlay position when there are elements fixed when scrolling [Taiga #4383](https://tree.taiga.io/project/penpot/issue/4383)
- Fix problem when sliding color picker in selected-colors [#3150](https://github.com/penpot/penpot/issues/3150)
- Fix error screen on upload image error [Taiga #5608](https://tree.taiga.io/project/penpot/issue/5608)
- Fix bad frame-id for certain componentes [#3205](https://github.com/penpot/penpot/issues/3205)
- Fix paste elements at bottom of frame [Taig #5253](https://tree.taiga.io/project/penpot/issue/5253)
- Fix new-file button on project not redirecting to the new file [Taiga #5610](https://tree.taiga.io/project/penpot/issue/5610)
- Fix retrieve user comments in dashboard [Taiga #5607](https://tree.taiga.io/project/penpot/issue/5607)
- Locks shapes when moved inside a locked parent [Taiga #5252](https://tree.taiga.io/project/penpot/issue/5252)
- Fix rotate several elements in bulk [Taiga #5165](https://tree.taiga.io/project/penpot/issue/5165)
- Fix onboarding slides height [Taiga #5373](https://tree.taiga.io/project/penpot/issue/5373)
- Fix create typography with section closed [Taiga #5574](https://tree.taiga.io/project/penpot/issue/5574)
- Fix exports menu on viewer mode [Taiga #5568](https://tree.taiga.io/project/penpot/issue/5568)
- Fix create empty comments [Taiga #5536](https://tree.taiga.io/project/penpot/issue/5536)
- Fix text changes not propagated to copy [Taiga #5364](https://tree.taiga.io/project/penpot/issue/5364)
- Fix position of text cursor is a bit too high in Invitations section [Taiga #5511](https://tree.taiga.io/project/penpot/issue/5511)
- Fix undo when updating several texts [Taiga #5197](https://tree.taiga.io/project/penpot/issue/5197)
- Fix assets right click button for multiple selection [Taiga #5545](https://tree.taiga.io/project/penpot/issue/5545)
- Fix problem with precision in resizes [Taiga #5623](https://tree.taiga.io/project/penpot/issue/5623)
- Fix absolute positioned layouts not showing flex properties [Taiga #5630](https://tree.taiga.io/project/penpot/issue/5630)
- Fix text gradient handlers [Taiga #4047](https://tree.taiga.io/project/penpot/issue/4047)
- Fix when user deletes one file during import it is impossible to finish importing of second file [Taiga #5656](https://tree.taiga.io/project/penpot/issue/5656)
- Fix export multiple images when only one of them has export settings [Taiga #5649](https://tree.taiga.io/project/penpot/issue/5649)
- Fix error when a user different than the thread creator edits a comment [Taiga #5647](https://tree.taiga.io/project/penpot/issue/5647)
- Fix unnecessary button [Taiga #3312](https://tree.taiga.io/project/penpot/issue/3312)
- Fix copy color information in several formats [Taiga #4723](https://tree.taiga.io/project/penpot/issue/4723)
- Fix dropdown width [Taiga #5541](https://tree.taiga.io/project/penpot/issue/5541)
- Fix enable comment mode and insert image keeps on comment mode [Taiga #5678](https://tree.taiga.io/project/penpot/issue/5678)
- Fix enable undo just after using pencil [Taiga #5674](https://tree.taiga.io/project/penpot/issue/5674)
- Fix 400 error when user changes password [Taiga #5643](https://tree.taiga.io/project/penpot/issue/5643)
- Fix cannot undo layer styles [Taiga #5676](https://tree.taiga.io/project/penpot/issue/5676)
- Fix unexpected exception on boolean shapes [Taiga #5685](https://tree.taiga.io/project/penpot/issue/5685)
- Fix ctrl+z on select not working [Taiga #5677](https://tree.taiga.io/project/penpot/issue/5677)
- Fix thubmnail rendering flashing [Taiga #5675](https://tree.taiga.io/project/penpot/issue/5675)

### :arrow_up: Deps updates

- Update google fonts catalog (at 2023/07/06) [Taiga #5592](https://tree.taiga.io/project/penpot/issue/5592)


### :heart: Community contributions by (Thank you!)

- Update Typography palette order (by @akshay-gupta7) [Github #3156](https://github.com/penpot/penpot/pull/3156)
- Palettes (color, typographies) empty state (by @akshay-gupta7) [Github #3160](https://github.com/penpot/penpot/pull/3160)
- Duplicate objects via drag + alt (by @akshay-gupta7) [Github #3147](https://github.com/penpot/penpot/pull/3147)
- Set line-height to auto as 1.2 (by @akshay-gupta7) [Github #3185](https://github.com/penpot/penpot/pull/3185)
- Click to select full values at the design sidebar (by @akshay-gupta7) [Github #3179](https://github.com/penpot/penpot/pull/3179)
- Fix rect filter bounds math (by @ryanbreen) [Github #3180](https://github.com/penpot/penpot/pull/3180)
- Removed sizing variables from radius (by @ondrejkonec) [Github #3184](https://github.com/penpot/penpot/pull/3184)
- Dashboard search, set focus after shortcut (by @akshay-gupta7) [Github #3196](https://github.com/penpot/penpot/pull/3196)
- Library name dropdown arrow is overlapped by library name (by @ondrejkonec) [Taiga #5200](https://tree.taiga.io/project/penpot/issue/5200)
- Reorder shadows (by @akshay-gupta7) [Github #3236](https://github.com/penpot/penpot/pull/3236)
- Open project in new tab from workspace (by @akshay-gupta7) [Github #3246](https://github.com/penpot/penpot/pull/3246)
- Distribute fix enabled when two elements were selected (by @dfelinto) [Github #3266](https://github.com/penpot/penpot/pull/3266)
- Distribute vertical spacing failing for overlapped text (by @dfelinto) [Github #3267](https://github.com/penpot/penpot/pull/3267)
- bug Change independent corner radius input tooltips #3332 (by @astudentinearth) [Github #3332](https://github.com/penpot/penpot/pull/3332)

## 1.18.6

### :bug: Bugs fixed

- Fix comments navigation from workspace [Taiga #5504](https://tree.taiga.io/project/penpot/issue/5504)

### :sparkles: Enhancements

- Add the ability to overwrite internal resolver with `PENPOT_INTERNAL_RESOLVER` environment
  variable [GH #3310](https://github.com/penpot/penpot/issues/3310)

## 1.18.5

### :bug: Bugs fixed

- Fix add flow option in contextual menu for frames
- Fix issues related with invitations
- Fix problem with undefined gaps
- Add deleted fonts auto match mechanism

## 1.18.4

### :bug: Bugs fixed

- Fix zooming while color picker breaks UI [GH #3214](https://github.com/penpot/penpot/issues/3214)
- Fix problem with layout not reflowing on shape deletion [Taiga #5289](https://tree.taiga.io/project/penpot/issue/5289)
- Fix extra long typography names on assets and palette [Taiga #5199](https://tree.taiga.io/project/penpot/issue/5199)
- Fix background-color property on inspect code [Taiga #5300](https://tree.taiga.io/project/penpot/issue/5300)
- Preview layer blend modes (by @akshay-gupta7) [Github #3235](https://github.com/penpot/penpot/pull/3235)

## 1.18.3

### :bug: Bugs fixed

- Fix problem with rulers not placing correctly [Taiga #5093](https://tree.taiga.io/project/penpot/issue/5093)
- Fix page context menu [Taiga #5145](https://tree.taiga.io/project/penpot/issue/5145)
- Fix project file count [Taiga #5148](https://tree.taiga.io/project/penpot/issue/5148)
- Fix OIDC roles checking mechanism [GH #3152](https://github.com/penpot/penpot/issues/3152)
- Import updated translation strings from weblate

### :arrow_up: Deps updates

## 1.18.2

### :bug: Bugs fixed

- Fix problem with frame title rotation
- Fix first level board "Show in view mode" is automatically unchecked [Taiga #5136](https://tree.taiga.io/project/penpot/issue/5136)

## 1.18.1

### :bug: Bugs fixed

- Fix problems with imported SVG shadows [Taiga #4922](https://tree.taiga.io/project/penpot/issue/4922)
- Fix problems with imported SVG embedded images and transforms [Taiga #4639](https://tree.taiga.io/project/penpot/issue/4639)

## 1.18.0

### :sparkles: New features

- Adds more accessibility improvements in dashboard [Taiga #4577](https://tree.taiga.io/project/penpot/us/4577)
- Adds paddings and gaps prediction on layout creation [Taiga #4838](https://tree.taiga.io/project/penpot/task/4838)
- Add visual feedback when proportionally scaling text elements with **K** [Taiga #3415](https://tree.taiga.io/project/penpot/us/3415)
- Add visualization and mouse control to paddings, margins and gaps in frames with layout [Taiga #4839](https://tree.taiga.io/project/penpot/task/4839)
- Allow for absolute positioned elements inside layout [Taiga #4834](https://tree.taiga.io/project/penpot/us/4834)
- Add z-index option for flex layout items [Taiga #2980](https://tree.taiga.io/project/penpot/us/2980)
- Scale content proportionally affects strokes, shadows, blurs and corners [Taiga #1951](https://tree.taiga.io/project/penpot/us/1951)
- Use tabulators to navigate layers [Taiga #5010](https://tree.taiga.io/project/penpot/issue/5010)

### :bug: Bugs fixed

- Fix problem with rules position on changing pages [Taiga #4847](https://tree.taiga.io/project/penpot/issue/4847)
- Fix error streen when uploading wrong SVG [#2995](https://github.com/penpot/penpot/issues/2995)
- Fix selecting children from hidden parent layers [Taiga #4934](https://tree.taiga.io/project/penpot/issue/4934)
- Fix problem when undoing multiple selected colors [Taiga #4920](https://tree.taiga.io/project/penpot/issue/4920)
- Allow selection of empty board by partial rect [Taiga #4806](https://tree.taiga.io/project/penpot/issue/4806)
- Improve behavior for undo on text edition [Taiga #4693](https://tree.taiga.io/project/penpot/issue/4693)
- Improve deeps selection of nested arboards [Taiga #4913](https://tree.taiga.io/project/penpot/issue/4913)
- Fix problem on selection numeric inputs on Firefox [#2991](https://github.com/penpot/penpot/issues/2991)
- Changed the text dominant-baseline to use ideographic [Taiga #4791](https://tree.taiga.io/project/penpot/issue/4791)
- Viewer wrong translations [Github #3035](https://github.com/penpot/penpot/issues/3035)
- Fix problem with text editor in Safari
- Fix unlink library color when blur color picker input [#3026](https://github.com/penpot/penpot/issues/3026)
- Fix snap pixel when moving path points on high zoom [#2930](https://github.com/penpot/penpot/issues/2930)
- Fix shortcuts for zoom now take into account the mouse position [#2924](https://github.com/penpot/penpot/issues/2924)
- Fix close colorpicker on Firefox when mouse-up is outside the picker [#2911](https://github.com/penpot/penpot/issues/2911)
- Fix problems with touch devices and Wacom tablets [#2216](https://github.com/penpot/penpot/issues/2216)
- Fix problem with board titles misplaced [Taiga #4738](https://tree.taiga.io/project/penpot/issue/4738)
- Fix problem with alt getting stuck when alt+tab [Taiga #5013](https://tree.taiga.io/project/penpot/issue/5013)
- Fix problem with z positioning of elements [Taiga #5014](https://tree.taiga.io/project/penpot/issue/5014)
- Fix problem in Firefox with scroll jumping when changin pages [#3052](https://github.com/penpot/penpot/issues/3052)
- Fix nested frame interaction created flow in wrong frame [Taiga #5043](https://tree.taiga.io/project/penpot/issue/5043)
- Font-Kerning does not work on Artboard Export to PNG/JPG/PDF [#3029](https://github.com/penpot/penpot/issues/3029)
- Fix manipulate duplicated project (delete, duplicate, rename, pin/unpin...) [Taiga #5027](https://tree.taiga.io/project/penpot/issue/5027)
- Fix deleted files appear in search results [Taiga #5002](https://tree.taiga.io/project/penpot/issue/5002)
- Fix problem with selected colors and texts [Taiga #5051](https://tree.taiga.io/project/penpot/issue/5051)
- Fix problem when assigning color from palette or assets [Taiga #5050](https://tree.taiga.io/project/penpot/issue/5050)
- Fix shortcuts for alignment [Taiga #5030](https://tree.taiga.io/project/penpot/issue/5030)
- Fix path options not showing when editing rects or ellipses [Taiga #5053](https://tree.taiga.io/project/penpot/issue/5053)
- Fix tooltips for some alignment options are truncated on design tab [Taiga #5040](https://tree.taiga.io/project/penpot/issue/5040)
- Fix horizontal margins drag don't always start from place [Taiga #5020](https://tree.taiga.io/project/penpot/issue/5020)
- Fix multiplayer username sometimes is not displayed correctly [Taiga #4400](https://tree.taiga.io/project/penpot/issue/4400)
- Show warning when trying to invite a user that is already in members [Taiga #4147](https://tree.taiga.io/project/penpot/issue/4147)
- Fix problem with text out of borders when changing from auto-width to fixed [Taiga #4308](https://tree.taiga.io/project/penpot/issue/4308)
- Fix header not showing when exiting fullscreen mode in viewer [Taiga #4244](https://tree.taiga.io/project/penpot/issue/4244)
- Fix visual problem in select options [Taiga #5028](https://tree.taiga.io/project/penpot/issue/5028)
- Forbid empty names for assets [Taiga #5056](https://tree.taiga.io/project/penpot/issue/5056)
- Select children after ungroup action [Taiga #4917](https://tree.taiga.io/project/penpot/issue/4917)
- Fix problem with guides not showing when moving over nested frames [Taiga #4905](https://tree.taiga.io/project/penpot/issue/4905)
- Fix change email and password for users signed in via social login [Taiga #4273](https://tree.taiga.io/project/penpot/issue/4273)
- Fix drag and drop files from browser or file explorer under circumstances [Taiga #5054](https://tree.taiga.io/project/penpot/issue/5054)
- Fix problem when copy/pasting shapes [Taiga #4931](https://tree.taiga.io/project/penpot/issue/4931)
- Fix problem with color picker not able to change hue [Taiga #5065](https://tree.taiga.io/project/penpot/issue/5065)
- Fix problem with outer stroke in texts [Taiga #5078](https://tree.taiga.io/project/penpot/issue/5078)
- Fix problem with text carring over next line when changing to fixed [Taiga #5067](https://tree.taiga.io/project/penpot/issue/5067)
- Fix don't show invite user hero to users with editor role [Taiga #5086](https://tree.taiga.io/project/penpot/issue/5086)
- Fix enter emails on onboarding new user creating team [Taiga #5089](https://tree.taiga.io/project/penpot/issue/5089)
- Fix invalid files amount after moving on dashboard [Taiga #5080](https://tree.taiga.io/project/penpot/issue/5080)
- Fix dashboard left sidebar, the [x] overlaps the field [Taiga #5064](https://tree.taiga.io/project/penpot/issue/5064)
- Fix expanded typography on assets sidebar is moving [Taiga #5063](https://tree.taiga.io/project/penpot/issue/5063)
- Fix spelling mistake in confirmation after importing only 1 file [Taiga #5095](https://tree.taiga.io/project/penpot/issue/5095)
- Fix problem with selection colors and texts [Taiga #5079](https://tree.taiga.io/project/penpot/issue/5079)
- Remove "show in view mode" flag when moving frame to frame [Taiga #5091](https://tree.taiga.io/project/penpot/issue/5091)
- Fix problem creating files in project page [Taiga #5060](https://tree.taiga.io/project/penpot/issue/5060)
- Disable empty names on rename files [Taiga #5088](https://tree.taiga.io/project/penpot/issue/5088)
- Fix problem with SVG and flex layout [Taiga #](https://tree.taiga.io/project/penpot/issue/5099)
- Fix unpublish and delete shared library warning messages [Taiga #5090](https://tree.taiga.io/project/penpot/issue/5090)
- Fix last update project timer update after creating new file [Taiga #5096](https://tree.taiga.io/project/penpot/issue/5096)
- Fix dashboard scrolling using 'Page Up' and 'Page Down' [Taiga #5081](https://tree.taiga.io/project/penpot/issue/5081)
- Fix view mode header buttons overlapping in small resolutions [Taiga #5058](https://tree.taiga.io/project/penpot/issue/5058)
- Fix precision for wrap in flex [Taiga #5072](https://tree.taiga.io/project/penpot/issue/5072)
- Fix relative position overlay positioning [Taiga #5092](https://tree.taiga.io/project/penpot/issue/5092)
- Fix hide grid keyboard shortcut [Github #3071](https://github.com/penpot/penpot/pull/3071)
- Fix problem with opacity in imported SVG's [Taiga #4923](https://tree.taiga.io/project/penpot/issue/4923)

### :heart: Community contributions by (Thank you!)
- To @ondrejkonec: for contributing to the code with:
- Refactor CSS variables [Github #2948](https://github.com/penpot/penpot/pull/2948)

## 1.17.3

### :bug: Bugs fixed
- Fix copy and paste very nested inside itself [Taiga #4848](https://tree.taiga.io/project/penpot/issue/4848)
- Fix custom fonts not rendered correctly [Taiga #4874](https://tree.taiga.io/project/penpot/issue/4874)
- Fix problem with shadows and blur on multiple selection
- Fix problem with redo shortcut
- Fix Component texts not displayed in assets panel [Taiga #4907](https://tree.taiga.io/project/penpot/issue/4907)
- Fix search field has implemented shared styles for "close icon" and "search icon" [Taiga #4927](https://tree.taiga.io/project/penpot/issue/4927)
- Fix Handling correctly slashes "/" in emails [Taiga #4906](https://tree.taiga.io/project/penpot/issue/4906)
- Fix Change text color from selected colors [Taiga #4933](https://tree.taiga.io/project/penpot/issue/4933)

### :sparkles: Enhancements

- Adds environment variables for specifying the export and backend URI for the frontend docker image, thanks to @Supernova3339 for the initial PR and suggestion [Github #2984](https://github.com/penpot/penpot/issues/2984)

## 1.17.2

### :bug: Bugs fixed

- Fix invite members button text [Taiga #4794](https://tree.taiga.io/project/penpot/issue/4794)
- Fix problem with opacity in frames [Taiga #4795](https://tree.taiga.io/project/penpot/issue/4795)
- Fix correct behaviour for space-around and added space-evenly option
- Fix duplicate with alt and undo only undo one step [Taiga #4746](https://tree.taiga.io/project/penpot/issue/4746)
- Fix problem creating frames inside layout [Taiga #4844](https://tree.taiga.io/project/penpot/issue/4844)
- Fix paste board inside itself [Taiga #4775](https://tree.taiga.io/project/penpot/issue/4775)
- Fix middle button panning can drag guides [Taiga #4266](https://tree.taiga.io/project/penpot/issue/4266)

### :heart: Community contributions by (Thank you!)

- To @ondrejkonec: for some code contributions on this release.

## 1.17.1

### :bug: Bugs fixed
- Fix components groups items show the component name in list mode [Taiga #4770](https://tree.taiga.io/project/penpot/issue/4770)
- Fix typing CMD+Z on MacOS turns the cursor into a Zoom cursor [Taiga #4778](https://tree.taiga.io/project/penpot/issue/4778)
- Fix white space on small screens [Taiga #4774](https://tree.taiga.io/project/penpot/issue/4774)
- Fix button spacing on delete acount modal [Taiga #4762](https://tree.taiga.io/project/penpot/issue/4762)
- Fix invitations input on team management and onboarding modal [Taiga #4760](https://tree.taiga.io/project/penpot/issue/4760)
- Fix weird numeration creating new elements in dashboard [Taiga #4755](https://tree.taiga.io/project/penpot/issue/4755)
- Fix can move shape with lens zoom active [Taiga #4787](https://tree.taiga.io/project/penpot/issue/4787)
- Fix social links broken [Taiga #4759](https://tree.taiga.io/project/penpot/issue/4759)
- Fix tooltips on left toolbar [Taiga #4793](https://tree.taiga.io/project/penpot/issue/4793)

## 1.17.0

### :sparkles: New features

- Adds layout flex functionality for boards
- Better overlays interactions on boards inside boards [Taiga #4386](https://tree.taiga.io/project/penpot/us/4386)
- Show board miniature in manual overlay setting [Taiga #4475](https://tree.taiga.io/project/penpot/issue/4475)
- Handoff visual improvements [Taiga #3124](https://tree.taiga.io/project/penpot/us/3124)
- Dynamic alignment only in sight [Github 1971](https://github.com/penpot/penpot/issues/1971)
- Add some accessibility to shortcut panel [Taiga #4713](https://tree.taiga.io/project/penpot/issue/4713)
- Add shortcuts for text editing [Taiga #2052](https://tree.taiga.io/project/penpot/us/2052)
- Second level boards treated as groups in terms of selection [Taiga #4269](https://tree.taiga.io/project/penpot/us/4269)
- Performance improvements both for backend and frontend
- Accessibility improvements for login area [Taiga #4353](https://tree.taiga.io/project/penpot/us/4353)
- Outbound webhooks [Taiga #4577](https://tree.taiga.io/project/penpot/us/4577)
- Add copy invitation link to the invitation options [Taiga #4213](https://tree.taiga.io/project/penpot/us/4213)
- Dynamic alignment only in sight [Taiga #3537](https://tree.taiga.io/project/penpot/us/3537)
- Improve naming of layers [Taiga #4036](https://tree.taiga.io/project/penpot/us/4036)
- Add zoom lense [Taiga #4691](https://tree.taiga.io/project/penpot/us/4691)
- Detect potential problems with custom font vertical metrics [Taiga #4697](https://tree.taiga.io/project/penpot/us/4697)

### :bug: Bugs fixed

- Add title to color bullets [Taiga #4218](https://tree.taiga.io/project/penpot/task/4218)
- Fix color bullets in library color modal [Taiga #4186](https://tree.taiga.io/project/penpot/issue/4186)
- Fix shortcut texts alignment [Taiga #4275](https://tree.taiga.io/project/penpot/issue/4275)
- Fix some texts and a typo [Taiga #4215](https://tree.taiga.io/project/penpot/issue/4215)
- Fix twitter support account link [Taiga #4279](https://tree.taiga.io/project/penpot/issue/4279)
- Fix lang autodetect issue [Taiga #4277](https://tree.taiga.io/project/penpot/issue/4277)
- Fix adding an extra page on import [Taiga #4543](https://tree.taiga.io/project/penpot/task/4543)
- Fix unable to select text at assets inputs in firefox [Taiga #4572](https://tree.taiga.io/project/penpot/issue/4572)
- Fix component sync when converting to path [Taiga #3642](https://tree.taiga.io/project/penpot/issue/3642)
- Fix style for team invite in deutsch [Taiga #4614](https://tree.taiga.io/project/penpot/issue/4614)
- Fix problem with text edition in Safari [Taiga #4046](https://tree.taiga.io/project/penpot/issue/4046)
- Fix show outline with rounded corners on rects [Taiga #4053](https://tree.taiga.io/project/penpot/issue/4053)
- Fix wrong interaction between comments and panning modes [Taiga #4297](https://tree.taiga.io/project/penpot/issue/4297)
- Fix bad element positioning on interaction with fixed scroll [Github #2660](https://github.com/penpot/penpot/issues/2660)
- Fix display type of component library not persistent [Taiga #4512](https://tree.taiga.io/project/penpot/issue/4512)
- Fix problem when moving texts with keyboard [#2690](https://github.com/penpot/penpot/issues/2690)
- Fix problem when drawing boxes won't detect mouse-up [Taiga #4618](https://tree.taiga.io/project/penpot/issue/4618)
- Fix missing loading icon on shared libraries [Taiga #4148](https://tree.taiga.io/project/penpot/issue/4148)
- Fix selection stroke missing in properties of multiple texts [Taiga #4048](https://tree.taiga.io/project/penpot/issue/4048)
- Fix missing create component menu for frames [Github #2670](https://github.com/penpot/penpot/issues/2670)
- Fix "currentColor" is not converted when importing SVG [Github 2276](https://github.com/penpot/penpot/issues/2276)
- Fix incorrect color in properties of multiple bool shapes [Taiga #4355](https://tree.taiga.io/project/penpot/issue/4355)
- Fix pressing the enter key gives you an internal error [Github 2675](https://github.com/penpot/penpot/issues/2675) [Github 2577](https://github.com/penpot/penpot/issues/2577)
- Fix confirm group name with enter doesn't work in assets modal [Taiga #4506](https://tree.taiga.io/project/penpot/issue/4506)
- Fix group/ungroup shapes inside a component [Taiga #4052](https://tree.taiga.io/project/penpot/issue/4052)
- Fix wrong update of text in components [Taiga #4646](https://tree.taiga.io/project/penpot/issue/4646)
- Fix problem with SVG imports with style [#2605](https://github.com/penpot/penpot/issues/2605)
- Fix ghost shapes after sync groups in components [Taiga #4649](https://tree.taiga.io/project/penpot/issue/4649)
- Fix layer orders messed up on move, group, reparent and undo [Github #2672](https://github.com/penpot/penpot/issues/2672)
- Fix max height in library dialog [Github #2335](https://github.com/penpot/penpot/issues/2335)
- Fix undo ungroup (shift+g) scrambles positions [Taiga #4674](https://tree.taiga.io/project/penpot/issue/4674)
- Fix justified text is stretched [Github #2539](https://github.com/penpot/penpot/issues/2539)
- Fix mousewheel on viewer inspector [Taiga #4221](https://tree.taiga.io/project/penpot/issue/4221)
- Fix path edition activated on boards [Taiga #4105](https://tree.taiga.io/project/penpot/issue/4105)
- Fix hidden layers inside groups become visible after the group visibility is changed[Taiga #4710](https://tree.taiga.io/project/penpot/issue/4710)
- Fix format of HSLA color on viewer [Taiga #4393](https://tree.taiga.io/project/penpot/issue/4393)
- Fix some typos [Taiga #4724](https://tree.taiga.io/project/penpot/issue/4724)
- Fix ctrl+c for inspect code [Taiga #4739](https://tree.taiga.io/project/penpot/issue/4739)
- Fix text in custom font is not at the expected position at export [Taiga #4394](https://tree.taiga.io/project/penpot/issue/4394)
- Fix unneeded popup when updating local components [Taiga #4430](https://tree.taiga.io/project/penpot/issue/4430)
- Fix multiuser - "Shadow" element is not updating immediately [Taiga #4709](https://tree.taiga.io/project/penpot/issue/4709)
- Fix paths not flagged as modified when resized [Taiga #4742](https://tree.taiga.io/project/penpot/issue/4742)
- Fix resend invitation doesn't reset the expiration date [Taiga #4741](https://tree.taiga.io/project/penpot/issue/4741)
- Fix incorrect state after undo page creation [Taiga #4690](https://tree.taiga.io/project/penpot/issue/4690)
- Fix copy paste texts with typography assets linked [Taiga #4750](https://tree.taiga.io/project/penpot/issue/4750)

### :heart: Community contributions by (Thank you!)

- To @iprithvitharun: let's make UX Writing contributions in Open Source a trend!

## 1.16.2-beta

### :bug: Bugs fixed

- Fix strage cursor behaviour after clicking viewport with text pool [Github #2447](https://github.com/penpot/penpot/issues/2447)

## 1.16.1-beta

### :bug: Bugs fixed

- Fix unexpected exception related to default nudge value
- Fix firefox changing layer color type is not applied [Taiga #4292](https://tree.taiga.io/project/penpot/issue/4292)
- Fix justify alignes text left [Taiga #4322](https://tree.taiga.io/project/penpot/issue/4322)
- Fix text out of borders with "auto width" and center align [Taiga #4308](https://tree.taiga.io/project/penpot/issue/4308)
- Fix wrong validation text after interaction with 2 and more files [Taiga #4276](https://tree.taiga.io/project/penpot/issue/4276)
- Fix auto-width for texts can make text appear stretched [Github #2482](https://github.com/penpot/penpot/issues/2482)
- Fix boards name do not disappear in focus mode [#4272](https://tree.taiga.io/project/penpot/issue/4272)
- Fix wrong email in the info message at change email [Taiga #4274](https://tree.taiga.io/project/penpot/issue/4274)
- Fix transform to path RMB menu item is not relevant if shape is already path [Taiga #4302](https://tree.taiga.io/project/penpot/issue/4302)
- Fix join nodes icon is active when 2 already joined nodes are selected [Taiga #4370](https://tree.taiga.io/project/penpot/issue/4370)
- Fix path nodes panel. "To curve" and "To corner" icons are active if node is already curved/cornered [Taiga #4371](https://tree.taiga.io/project/penpot/issue/4371)
- Fix displaying comments settings are not applied via "Comments" menu drop-down on the top navbar on view mode [Taiga #4389](https://tree.taiga.io/project/penpot/issue/4389)
- Fix bad behaviour on hovering and click nested artboards [Taiga #4018](https://tree.taiga.io/project/penpot/issue/4018) and [Taiga #4269](https://tree.taiga.io/project/penpot/us/4269)
- Fix lang autodetect issue [Taiga #4277](https://tree.taiga.io/project/penpot/issue/4277)
- Fix colorpicker does not close upon switching to Dashboard [Taiga #4408](https://tree.taiga.io/project/penpot/issue/4408)
- Fix problem with auto-width/auto-height + lock-proportions

## 1.16.0-beta

### :boom: Breaking changes & Deprecations

- Removed the support for v2 internal file data blob format.  This
  version has never been documented nor set as default value so
  technically this is not a breaking change because we are removing
  a "private API".

### :sparkles: New features

- Improve interactions with nested boards [Taiga #4054](https://tree.taiga.io/project/penpot/us/4054)
- Add team hero in projects dashboard [Taiga #3863](https://tree.taiga.io/project/penpot/us/3863)
- Add zoom style to shared link [Taiga #3874](https://tree.taiga.io/project/penpot/us/3874)
- Add dashboard creation button as placeholder [Taiga #3861](https://tree.taiga.io/project/penpot/us/3861)
- Improve invitation flow on onboarding [Taiga #3241](https://tree.taiga.io/project/penpot/us/3241)
- Add new text to initial modals [Taiga #3458](https://tree.taiga.io/project/penpot/us/3458)
- Add new questions to onboarding [Taiga #3462](https://tree.taiga.io/project/penpot/us/3462)
- Add cosmetic changes in viewer mode [Taiga #3688](https://tree.taiga.io/project/penpot/us/3688)
- Outline highlights on layer hovering [Taiga #2645](https://tree.taiga.io/project/penpot/us/2645) by @andrewzhurov
- Add zoom to shape on double click up on its icon [Taiga #3929](https://tree.taiga.io/project/penpot/us/3929) by @andrewzhurov
- Add Libraries & Templates carousel [Taiga #3860](https://tree.taiga.io/project/penpot/us/3860)
- Ungroup frames [Taiga #4012](https://tree.taiga.io/project/penpot/us/4012)
- Newsletter Opt-in options for subscription categories [Taiga #3242](https://tree.taiga.io/project/penpot/us/3242)
- Print emails to console by default if smtp is disabled
- Add `email-verification` flag for enable/disable email verification
- Make graphics thumbnails load lazy [Taiga #4252](https://tree.taiga.io/project/penpot/issue/4252)

### :bug: Bugs fixed

- Fix unexpected removal of guides on copy&paste frames [Taiga #3887](https://tree.taiga.io/project/penpot/issue/3887) by @andrewzhurov
- Fix props preserving on copy&paste texts [Taiga #3629](https://tree.taiga.io/project/penpot/issue/3629) by @andrewzhurov
- Fix unexpected layers ungrouping on moving it [Taiga #3932](https://tree.taiga.io/project/penpot/issue/3932) by @andrewzhurov
- Fix artboards moving with comment tool selected [Taiga #3938](https://tree.taiga.io/project/penpot/issue/3938)
- Fix undo on delete page does not preserve its order [Taiga #3375](https://tree.taiga.io/project/penpot/issue/3375)
- Fix unexpected 404 on deleting library that is used by deleted files
- Fix inconsistent message on deleting library when a library is linked from deleted files
- Fix change multiple colors with SVG [Taiga #3889](https://tree.taiga.io/project/penpot/issue/3889)
- Fix ungroup does not work for typographies [Taiga #4195](https://tree.taiga.io/project/penpot/issue/4195)
- Fix inviting to non existing users can fail [Taiga #4108](https://tree.taiga.io/project/penpot/issue/4108)
- Fix components marked as touched when moved [Taiga #4061](https://tree.taiga.io/project/penpot/task/4061)
- Fix boards grouped shouldn't show the title [Taiga #4251](https://tree.taiga.io/project/penpot/issue/4251)
- Fix gradient handlers are under resize handlers[Taiga #4298](https://tree.taiga.io/project/penpot/issue/4298)
- Fix grid not syncing immediately in multiuser [Taiga #4339](https://tree.taiga.io/project/penpot/issue/4339)
- Fix custom font upload fails silently for unsupported formats [Taiga #4279](https://tree.taiga.io/project/penpot/issue/4280)

### :heart: Community contributions by (Thank you!)

- To @andrewzhurov for many code contributions on this release.
- UI improvements in Project section (by @Waishnav) [#2285](https://github.com/penpot/penpot/pull/2285)
- Fix fronted comments (by @lol768) [#2368](https://github.com/penpot/penpot/pull/2368)

## 1.15.5-beta

### :bug: Bugs fixed

- Fix artboard border radius [Taiga #4291](https://tree.taiga.io/project/penpot/issue/4291)
- Fix copied & pasted layer is not visible [Taiga #4283](https://tree.taiga.io/project/penpot/issue/4283)
- Fix notification to newsletter is shown in all cases [Taiga #4367](https://tree.taiga.io/project/penpot/issue/4367)
- Fix comments section is not scrolling by mouse wheel [Taiga #4305](https://tree.taiga.io/project/penpot/issue/4305)
- Fix justify alignes text left [Taiga #4322](https://tree.taiga.io/project/penpot/issue/4322)
- Fix text out of borders with "auto width" and center align [Taiga #4308](https://tree.taiga.io/project/penpot/issue/4308)

## 1.15.4-beta

### :bug: Bugs fixed

- Fix social buttons in register form [Taiga #4320](https://tree.taiga.io/project/penpot/issue/4320)
- Remove gitter information from feedback page [Taiga #4157](https://tree.taiga.io/project/penpot/issue/4157)
- Fix overlay remains open on frame change [Taiga #4066](https://tree.taiga.io/project/penpot/issue/4066)
- Fix toggle overlay position [Taiga #4091](https://tree.taiga.io/project/penpot/issue/4091)
- Fix overlay closed on clicked outside [Taiga #4027](https://tree.taiga.io/project/penpot/issue/4027)
- Fix animate multiple overlays [Taiga #3993](https://tree.taiga.io/project/penpot/issue/3993)
- Fix problem with snap to grids [#2221](https://github.com/penpot/penpot/issues/2221)
- Fix issue when scaling to value 0 [#2252](https://github.com/penpot/penpot/issues/2252)
- Fix problem when moving shapes inside nested frames [Taiga #4113](https://tree.taiga.io/project/penpot/issue/4113)
- Fix color type icon does not change [Taiga #4133](https://tree.taiga.io/project/penpot/issue/4133)
- Fix recent colors are not working [Taiga #4153](https://tree.taiga.io/project/penpot/issue/4153)
- Fix change opacity in colorpicker cause bugged color [Taiga #4154](https://tree.taiga.io/project/penpot/issue/4154)
- Fix gradient colors don't arrive in recent colors palette (https://tree.taiga.io/project/penpot/issue/4155)
- Fix selected colors allow gradients in shadows [Taiga #4156](https://tree.taiga.io/project/penpot/issue/4156)
- Fix import files with unexpected format or invalid content [Taiga #4136](https://tree.taiga.io/project/penpot/issue/4136)
- Fix wrong shortcut button tip of "Delete" function [Taiga #4162](https://tree.taiga.io/project/penpot/issue/4162)
- Fix error after user drags any layer in search functionality [Taiga #4161](https://tree.taiga.io/project/penpot/issue/4161)
- Fix font search works only with lowercase letters [Taiga #4140](https://tree.taiga.io/project/penpot/issue/4140)
- Fix Terms and Privacy links overlapping [Taiga #4137](https://tree.taiga.io/project/penpot/issue/4137)
- Fix Export bounding box mask [Taiga #950](https://tree.taiga.io/project/penpot/issue/950)
- Fix delete layers in bulk [Taiga #4160](https://tree.taiga.io/project/penpot/issue/4160)
- Fix Cannot take out an element from a group at layers panel by drag [Taiga #4209](https://tree.taiga.io/project/penpot/issue/4209)
- Fix Internal error when resending invitation email [Taiga #4212](https://tree.taiga.io/project/penpot/issue/4212)
- Fix PDF exportation order [Taiga #4216](https://tree.taiga.io/project/penpot/issue/4216)
- Fix some typos [Taiga #4215](https://tree.taiga.io/project/penpot/issue/4215)
- Fix "no boards" message in viewer [Taiga #4243](https://tree.taiga.io/project/penpot/issue/4243)
- Fix view mode login size [Taiga #4210](https://tree.taiga.io/project/penpot/issue/4210)

## 1.15.3-beta

### :bug: Bugs fixed

- Fix default value of grow type in texts [Taiga #4034](https://tree.taiga.io/project/penpot/issue/4034)
- Fix error when moving nested frames outside [Taiga #4017](https://tree.taiga.io/project/penpot/issue/4017)
- Fix problem when hovering over nested frames [Taiga #4018](https://tree.taiga.io/project/penpot/issue/4018)
- Fix problem editing rotated texts [Taiga #4026](https://tree.taiga.io/project/penpot/issue/4026)
- Fix problem with texts for non existing fonts [Taiga #4087](https://tree.taiga.io/project/penpot/issue/4087)
- Fix undo after moving layers will wrongly order the layers [Taiga #3344](https://tree.taiga.io/project/penpot/issue/3344)
- Fix grouping typographies by drag & drop does not work (again) [#2203](https://github.com/penpot/penpot/issues/2203)
- Fix when ungrouping, the items previously grouped should ALWAYS remain selected [Taiga #4064](https://tree.taiga.io/project/penpot/issue/4064)
- Change shortcut for "Clear undo" [#2219](https://github.com/penpot/penpot/issues/2219)


## 1.15.2-beta

### :bug: Bugs fixed

- Fix problem with multi-user text editing [Taiga #3446](https://tree.taiga.io/project/penpot/issue/3446)
- Fix path tools blocking elements underneath [#2050](https://github.com/penpot/penpot/issues/2050)
- Fix frame titles deforming when resize [#2207](https://github.com/penpot/penpot/issues/2207)
- Fix export simple line path [#3890](https://tree.taiga.io/project/penpot/issue/3890)
- Fix color-picker recent colors [Taiga #4013](https://tree.taiga.io/project/penpot/issue/4013)

## 1.15.1-beta

### :bug: Bugs fixed

- Fix shadows doesn't work on nested artboards [Taiga #3886](https://tree.taiga.io/project/penpot/issue/3886)
- Fix problems with double-click and selection [Taiga #4005](https://tree.taiga.io/project/penpot/issue/4005)
- Fix mismatch between editor and displayed text in workspace [Taiga #3975](https://tree.taiga.io/project/penpot/issue/3975)
- Fix validation error on text position [Taiga #4010](https://tree.taiga.io/project/penpot/issue/4010)
- Fix objects jitter while scrolling [Github #2167](https://github.com/penpot/penpot/issues/2167)
- Fix on color-picker, click+drag adds lots of recent colors [Taiga #4013](https://tree.taiga.io/project/penpot/issue/4013)
- Fix opening profile URL while signed out takes to "your account" section[Taiga #3976](https://tree.taiga.io/project/penpot/issue/3976)

## 1.15.0-beta

### :boom: Breaking changes & Deprecations

- The `PENPOT_LOGIN_WITH_LDAP` environment variable is finally removed (after
  many version with deprecation). It is replaced with the
  `enable-login-with-ldap` flag.
- The `PENPOT_LDAP_ATTRS_PHOTO` finally removed, it was unused for many
  versions.
- If you are using social login (google, github, gitlab or generic OIDC) you
  will need to ensure to add the following flags respectively to let them
  enabled: `enable-login-with-google`, `enable-login-with-github`,
  `enable-login-with-gitlab` and `enable-login-with-oidc`. If not, they will
  remain disabled after application start independently if you set the client-id
  and client-sectet options.
- The `PENPOT_REGISTRATION_ENABLED` is finally removed in favour of
  `<enable|disable>-registration` flag.
- The OIDC providers are now initialized synchronously, and if you are using the
  discovery mechanism of the generic OIDC integration, the start time of the
  application will depend on how fast the OIDC provider responds to the
  discovery http request.

### :sparkles: New features

- Add some cosmetic changes in viewer mode [Taiga #3688](https://tree.taiga.io/project/penpot/us/3688)
- Allow for nested and rotated boards inside other boards and groups [Taiga #2874](https://tree.taiga.io/project/penpot/us/2874?milestone=319982)
- View mode improvements to enable access and use in different conditions [Taiga #3023](https://tree.taiga.io/project/penpot/us/3023)
- Improved share link options. Now you can allow non-team members to comment and/or inspect [Taiga #3056] (https://tree.taiga.io/project/penpot/us/3056)
- Signin/Signup from shared link [Taiga #3472](https://tree.taiga.io/project/penpot/us/3472)
- Support for import/export binary format [Taiga #2991](https://tree.taiga.io/project/penpot/us/2991)
- Comments positioning [Taiga #2007](https://tree.taiga.io/project/penpot/us/2007)
- Select all inside a group select only the objects at this group level [Taiga #2382](https://tree.taiga.io/project/penpot/issue/2382)
- Make the media maximum upload size configurable

### :bug: Bugs fixed

- Fix viewer scroll problems [Taiga 3403](https://tree.taiga.io/project/penpot/issue/3403)
- Fix hide html options on handoff [Taiga 3533](https://tree.taiga.io/project/penpot/issue/3533)
- Fix share prototypes overlay and stroke [Taiga #3994](https://tree.taiga.io/project/penpot/issue/3994)
- Fix border radious on boolean operations [Taiga #3959](https://tree.taiga.io/project/penpot/issue/3959)
- Fix inconsistent representation of rectangles [Taiga #3977](https://tree.taiga.io/project/penpot/issue/3977)
- Fix recent fonts info [Taiga #3953](https://tree.taiga.io/project/penpot/issue/3953)
- Fix clipped elements affect boards and centering [Taiga #3666](https://tree.taiga.io/project/penpot/issue/3666)
- Fix intro action in multi input [Taiga #3541](https://tree.taiga.io/project/penpot/issue/3541)
- Fix team default image [Taiga #3919](https://tree.taiga.io/project/penpot/issue/3919)
- Fix problem with group coordinates [#2008](https://github.com/penpot/penpot/issues/2008)
- Fix problem with line-height and texts [Taiga #3578](https://tree.taiga.io/project/penpot/issue/3578)
- Fix moving frame-guides outside frames [Taiga #3839](https://tree.taiga.io/project/penpot/issue/3839)
- Fix problem with 180 degree rotations [#2082](https://github.com/penpot/penpot/issues/2082)
- Fix font rendering on grid thumbnails [Taiga #3473](https://tree.taiga.io/project/penpot/issue/3473)
- Fix Drag and drop font assets in groups [Taiga #3763](https://tree.taiga.io/project/penpot/issue/3763)
- Fix copy and paste layers order [Taiga #1617](https://tree.taiga.io/project/penpot/issue/1617)
- Fix unexpected removal of guides on copy&paste frames [Taiga #3887](https://tree.taiga.io/project/penpot/issue/3887) by @andrewzhurov
- Fix props preserving on copy&paste texts [Taiga #3629](https://tree.taiga.io/project/penpot/issue/3629) by @andrewzhurov
- Fix unexpected layers ungrouping on moving it [Taiga #3932](https://tree.taiga.io/project/penpot/issue/3932) by @andrewzhurov
- Fix unexpected exception and behavior on colorpicker with gradients [Taiga #3448](https://tree.taiga.io/project/penpot/issue/3448)
- Fix multiselection with shift not working inside a library group [Taiga #3532](https://tree.taiga.io/project/penpot/issue/3532)
- Fix drag and drop graphic assets in groups [Taiga #4002](https://tree.taiga.io/project/penpot/issue/4002)
- Fix bringing complete file data when launching the export dialog [Taiga #4006](https://tree.taiga.io/project/penpot/issue/4006)

### :arrow_up: Deps updates
### :heart: Community contributions by (Thank you!)

## 1.14.2-beta

### :bug: Bugs fixed

- Fix colors from unlinked libs in color selected widget [Taiga #3712](https://tree.taiga.io/project/penpot/issue/3712)
- Fix fill information not complete when paste plain text [Taiga #3680](https://tree.taiga.io/project/penpot/issue/3680)
- Fix problem when resizing groups [Taiga #3702](https://tree.taiga.io/project/penpot/issue/3702)
- Fix issues on typographies assets grouping [#2073](https://github.com/penpot/penpot/issues/2073)
- Fix text positioning inconsistencies between browsers

## 1.14.1-beta

### :bug: Bugs fixed

- Fix shortcut access in main menu [Taiga #3672](https://tree.taiga.io/project/penpot/issue/3672)
- Fix modify colors in a row in selected colors [Taiga #3653](https://tree.taiga.io/project/penpot/issue/3653)
- Fix crash when double click on viewer assets [Taiga #3625](https://tree.taiga.io/project/penpot/issue/3625)
- Fix right click on typographies assets [Taiga #3638](https://tree.taiga.io/project/penpot/issue/3638)

## 1.14.0-beta

### :sparkles: New features

- Added shortcut panel in workspace [Taiga #36](https://tree.taiga.io/project/penpot/us/36)
- Added selected colors widget in right sidebar [Taiga #2485](https://tree.taiga.io/project/penpot/us/2485)
- Added fixed elements when scrolling [Taiga #1533](https://tree.taiga.io/project/penpot/us/1533)
- Multiple team invitations on onboarding [Taiga #3084](https://tree.taiga.io/project/penpot/us/3084)
- Change text properties position at the sidebar [Taiga #3047](https://tree.taiga.io/project/penpot/us/3047)
- Group assets by drag and drop [Taiga #2831](https://tree.taiga.io/project/penpot/us/2831)
- Navigate to the original link after log in [Taiga #3624](https://tree.taiga.io/project/penpot/issue/3624)

### :bug: Bugs fixed

- Fix menu file not accessible in certain conditions [Taiga #3385](https://tree.taiga.io/project/penpot/issue/3385)
- Remove deprecated menu options [Taiga #3333](https://tree.taiga.io/project/penpot/issue/3333)
- Prototype connection should be under the rules [Taiga #3384](https://tree.taiga.io/project/penpot/issue/3384)
- Fix problem with empty text boxes events [Taiga #3627](https://tree.taiga.io/project/penpot/issue/3627)


## 1.13.5-beta

### :bug: Bugs fixed
- Fix orientation artboard preset not working with differently sized artboards [Taiga #3548](https://tree.taiga.io/project/penpot/issue/3548)
- Fix background on export arboards [Taiga #1991](https://tree.taiga.io/project/penpot/issue/1991)

## 1.13.4-beta

### :bug: Bugs fixed

- Fix undo when drawing curves [Taiga #3523](https://tree.taiga.io/project/penpot/issue/3523)
- Fix issue with text edition and certain fonts (WorkSans, Raleway, ...) and foreign objects [Taiga #3521](https://tree.taiga.io/project/penpot/issue/3521)
- Fix thumbnail generation when concurrent edition [Taiga #3522](https://tree.taiga.io/project/penpot/issue/3522)
- Fix environment import for exporter in Docker
- Fix auto scroll layers in Firefox [Taiga #3531](https://tree.taiga.io/project/penpot/issue/3531)
- Fix base background not visible for imported SVG

## 1.13.3-beta

### :bug: Bugs fixed

- Fix docker dependencies
- Sets invitations expirations to 7 days
- Add safety measure for text positions
- Fix old texts with opacity and no fill
- Remove default font on team change
- Fix github auth without name
- Fix problems with font loading in Firefox 95

## 1.13.2-beta

### :bug: Bugs fixed

- Improved performance when out of focus mode
- Improved performance for thumbnail generation
- Fix problem with out of sync thumbnails

## 1.13.1-beta

### :bug: Bugs fixed

- Fix problem with text positioning
- Fix issue with thumbnail generation before fonts loading
- Fix unable to hide artboards
- Fix problem with fonts cache causing hanging in certain pages

## 1.13.0-beta

### :boom: Breaking changes

- We've changed the behaviour of the border-radius so it works as CSS that [has some limits](https://www.w3.org/TR/css-backgrounds-3/#corner-overlap).
- Now exported text are SVG's native `text` tag instead of paths. This could break when opening the file depending on your engine. Some SVG's may require fonts to be installed at system level.

### :sparkles: New features

- Search and filter layers [Taiga #2564](https://tree.taiga.io/project/penpot/us/2564)
- Exporting big files flow [Taiga #2218](https://tree.taiga.io/project/penpot/us/2218)
- Multiexport from main menu [Taiga #520](https://tree.taiga.io/project/penpot/us/28541)
- Multiexport assets (aka bulk export) [Taiga #520](https://tree.taiga.io/project/penpot/us/520)
- Set the artboard layer fixed at the top side of the layers [Taiga #2636](https://tree.taiga.io/project/penpot/us/2636)
- Set an artboard as the file thumbnail [Taiga #1526](https://tree.taiga.io/project/penpot/us/1526)
- Social login redesign [Taiga #2974](https://tree.taiga.io/project/penpot/task/2974)
- Add border radius to artboards [Taiga #2056](https://tree.taiga.io/project/penpot/us/2056)
- Allow send multiple team invitations at once [Taiga #2798](https://tree.taiga.io/project/penpot/us/2798)
- Persist color palette and color picker across refresh [Taiga #1660](https://tree.taiga.io/project/penpot/issue/1660)
- Ability to add multiple strokes to a shape [Taiga #2778](https://tree.taiga.io/project/penpot/us/2778)
- Scroll to selected size in font size selector [Taiga #2825](https://tree.taiga.io/project/penpot/us/2825)
- Add new invitations section [Taiga #2797](https://tree.taiga.io/project/penpot/us/2797)
- Ability to add multiple fills to a shape [Taiga #1394](https://tree.taiga.io/project/penpot/us/1394)
- Team members redesign [Taiga #2283](https://tree.taiga.io/project/penpot/us/2283)
- New focus mode in workspace [Taiga #2748](https://tree.taiga.io/project/penpot/us/2748)
- Changed text shapes to be displayed as natives SVG text elements [Taiga #2759](https://tree.taiga.io/project/penpot/us/2759)
- Texts now can have strokes, multiple fills and can be used as masks
- Add the ability to specify the attribute for retrieve the email on OIDC integration [#1460](https://github.com/penpot/penpot/issues/1460)
- Allow registration with invitation token when registration is disabled
- Add the ability to disable standard, password login [Taiga #2999](https://tree.taiga.io/project/penpot/us/2999)
- Don't stop SVG import when an image cannot be imported [#1531](https://github.com/penpot/penpot/issues/1531)
- Show Penpot color in Safari tab bar [#1803](https://github.com/penpot/penpot/issues/1803)
- Added option to disable snap to pixel and improved behaviour for sub-pixel drawing [#2552](https://tree.taiga.io/project/penpot/us/2552)
- Delete guides while supr on hover [#2823](https://tree.taiga.io/project/penpot/us/2823)
- Opt-in subscription on on-premise instances [#2772](https://tree.taiga.io/project/penpot/us/2772)
- Optimizations in frame thumbnails [#3147](https://tree.taiga.io/project/penpot/us/3147)

### :bug: Bugs fixed

- Fix typo in viewer comment section [Taiga #3401](https://tree.taiga.io/project/penpot/issue/3401)
- Do not show team-up modal for users already on a team [Taiga #3311](https://tree.taiga.io/project/penpot/issue/3311)
- Constraints are not well assigned when default and multiselection [Taiga #3069](https://tree.taiga.io/project/penpot/issue/3069)
- Duplicate artboards create new flows if needed [Taiga #2221](https://tree.taiga.io/project/penpot/issue/2221)
- Round the size values on handoff to two decimals [Taiga #3227](https://tree.taiga.io/project/penpot/issue/3227)
- Fix paste shapes while editing text [Taiga #2396](https://tree.taiga.io/project/penpot/issue/2396)
- Round the size values on handoff to two decimals [Taiga #3227](https://tree.taiga.io/project/penpot/issue/3227)
- Fix blend modes ignored in component updates [Taiga #2626](https://tree.taiga.io/project/penpot/issue/2626)
- Fix internal error when hoverin over shape [Taiga #3237](https://tree.taiga.io/project/penpot/issue/3237)
- Fix mouse leave in handoff close overlay animation breaks [Taiga #3173](https://tree.taiga.io/project/penpot/issue/3173)
- Fix different behaviour during image drag [Taiga #2279](https://tree.taiga.io/project/penpot/issue/2279)
- Fix hidden file name on import [Taiga #3172](https://tree.taiga.io/project/penpot/issue/3172)
- Fix unnecessary scrollbars at the color list [Taiga #3211](https://tree.taiga.io/project/penpot/issue/3211)
- "Show in exports" is showing in multiselections [Taiga #3194](https://tree.taiga.io/project/penpot/issue/3194)
- Edit file name navigates to the file workspace [Taiga #3183](https://tree.taiga.io/project/penpot/issue/3183)
- Fix scroll into view behind fixed element [Taiga #3170](https://tree.taiga.io/project/penpot/issue/3170)
- Fix sidebar icon in viewer mode [Taiga #3184](https://tree.taiga.io/project/penpot/issue/3184)
- Fix send to back several shapes at a time [Taiga #3077](https://tree.taiga.io/project/penpot/issue/3077)
- Fix duplicate multi selected elements [Taiga #3155](https://tree.taiga.io/project/penpot/issue/3155)
- Fix add fills to artboard modify children [Taiga #3151](https://tree.taiga.io/project/penpot/issue/3151)
- Avoid numeric inputs to allow big numbers [Taiga #2858](https://tree.taiga.io/project/penpot/issue/2858)
- Fix component context menu size [Taiga #2480](https://tree.taiga.io/project/penpot/issue/2480)
- Add shadow to artboard make it lose the fill [Taiga #3139](https://tree.taiga.io/project/penpot/issue/3139)
- Avoid numeric inputs to change its value without focusing them [Taiga #3140](https://tree.taiga.io/project/penpot/issue/3140)
- Fix comments modal when changing pages [Taiga #2597](https://tree.taiga.io/project/penpot/issue/2508)
- Copy paste inside a text layer leaves pasted text transparent [Taiga #3096](https://tree.taiga.io/project/penpot/issue/3096)
- On dashboard enter on empty search refresh the page [Taiga #2597](https://tree.taiga.io/project/penpot/issue/2597)
- Pencil cursor changes when activated [Taiga #2276](https://tree.taiga.io/project/penpot/issue/2276)
- Fix icon placement in Mixed message [Taiga #3037](https://tree.taiga.io/project/penpot/issue/3037)
- Fix scroll in comment section [Taiga #3068](https://tree.taiga.io/project/penpot/issue/3068)
- Remove a decimal sets value to 0 [Taiga #3059](https://tree.taiga.io/project/penpot/issue/3054)
- Go to style library file to edit in a new tab [Taiga #2639](https://tree.taiga.io/project/penpot/issue/2639)
- Inner shadow with border not working properly [Taiga #2883](https://tree.taiga.io/project/penpot/issue/2883)
- Fix ellipsis in long page names [Taiga #2962](https://tree.taiga.io/project/penpot/issue/2962)
- Fix color palette animation [Taiga #2852](https://tree.taiga.io/project/penpot/issue/2852)
- Fix display code icon on preview hover [Taiga #2838](https://tree.taiga.io/project/penpot/us/2838)
- Fix crash on iOS when displaying viewer [#1522](https://github.com/penpot/penpot/issues/1522)
- Fix problem when importing a SVG with text [#1532](https://github.com/penpot/penpot/issues/1532)
- Fix problem when adding shadows to imported text [#Taiga 3057](https://tree.taiga.io/project/penpot/issue/3057)
- Fix problem when importing SVG's with uses with overriding properties [#Taiga 2884](https://tree.taiga.io/project/penpot/issue/2884)
- Fix inconsistency with radius in SVG an CSS [#1587](https://github.com/penpot/penpot/issues/1587)
- Fix clickable area in layers [#1680](https://github.com/penpot/penpot/issues/1680)
- Fix problems with trackpad zoom and scroll in MacOS [#1161](https://github.com/penpot/penpot/issues/1161)
- Fix problem with copy/paste in Safari [#1209](https://github.com/penpot/penpot/issues/1209)
- Fix paste ordering for frames not being respected [Taiga #3097](https://tree.taiga.io/project/penpot/issue/3097)
- Improved command support for MacOS [Taiga #2789](https://tree.taiga.io/project/penpot/issue/2789)
- Fix shift+2 shortcut in MacOS with non-english keyboards [Taiga #3038](https://tree.taiga.io/project/penpot/issue/3038)
- Some fixes to SVG imports [Taiga #3122](https://tree.taiga.io/project/penpot/issue/3122) [#1720](https://github.com/penpot/penpot/issues/1720) [Taiga #2884](https://tree.taiga.io/project/penpot/issue/2884)
- Fix drag guides to delete target area [#1679](https://github.com/penpot/penpot/issues/1679)
- Fix undo when rotating groups [Taiga #3136](https://tree.taiga.io/project/penpot/issue/3136)
- Fix component name in sidebar widget [Taiga #3144](https://tree.taiga.io/project/penpot/issue/3144)
- Fix resize rotated shape with top&down constraints [Taiga #3167](https://tree.taiga.io/project/penpot/issue/3167)
- Fix multi user not working [Taiga #3195](https://tree.taiga.io/project/penpot/issue/3195)
- Fix guides are not duplicated with the artboard [Taiga #3072](https://tree.taiga.io/project/penpot/issue/3072)
- Fix problem when changing group size with decimal values [Taiga #3203](https://tree.taiga.io/project/penpot/issue/3203)
- Fix error when drawing curves with only one point [Taiga #3282](https://tree.taiga.io/project/penpot/issue/3282)
- Fix issue with paste ordering sometimes not being respected [Taiga #3268](https://tree.taiga.io/project/penpot/issue/3268)
- Fix problem when export/importing guides attached to frame [#1838](https://github.com/penpot/penpot/issues/1838)
- Fix problem when resizing a group with texts with auto-width/height [#3171](https://tree.taiga.io/project/penpot/issue/3171)

### :arrow_up: Deps updates
### :heart: Community contributions by (Thank you!)

## 1.12.4-beta

### :bug: Bugs fixed

- Fix crash on iOS when displaying viewer [#1522](https://github.com/penpot/penpot/issues/1522)
- Fix problems with trackpad zoom and scroll in MacOS [#1161](https://github.com/penpot/penpot/issues/1161)
- Fix problem with copy/paste in Safari [#1209](https://github.com/penpot/penpot/issues/1209)
- Improved command support for MacOS [Taiga #2789](https://tree.taiga.io/project/penpot/issue/2789)
- Fix shift+2 shortcut in MacOS with non-english keyboards [Taiga #3038](https://tree.taiga.io/project/penpot/issue/3038)

## 1.12.3-beta

### :bug: Bugs fixed

- Fix issue with shift+select to deselect shapes [Taiga #3154](https://tree.taiga.io/project/penpot/issue/3154)
- Fix issue with drag-select shapes  [Taiga #3165](https://tree.taiga.io/project/penpot/issue/3165)
- Fix issue on password persistence after registration process on private instances

## 1.12.2-beta

### :bug: Bugs fixed

- Fix issue with guides over shape handlers [Taiga #3032](https://tree.taiga.io/project/penpot/issue/3032)
- Fix problem with shift+ctrl+click to select [#1671](https://github.com/penpot/penpot/issues/1671)
- Fix ellipsis in long page names [Taiga #2962](https://tree.taiga.io/project/penpot/issue/2962)

## 1.12.1-beta

### :bug: Bugs fixed

- Fix length of names in sidebar [Taiga #2962](https://tree.taiga.io/project/penpot/issue/2962)
- Fix issues on loki integration


## 1.12.0-beta

### :boom: Breaking changes

### :sparkles: New features

- Open feedback in a new window [Taiga #2901](https://tree.taiga.io/project/penpot/us/2901)
- Improve usage of file menu [Taiga #2853](https://tree.taiga.io/project/penpot/us/2853)
- Rotation to snap to 15º intervals with shift [Taiga #2437](https://tree.taiga.io/project/penpot/issue/2437)
- Support border radius and stroke properties for images [Taiga #497](https://tree.taiga.io/project/penpot/us/497)
- Disallow using same password as user email [Taiga #2454](https://tree.taiga.io/project/penpot/us/2454)
- Add configurable nudge amount [Taiga #910](https://tree.taiga.io/project/penpot/us/910)
- Add stroke properties for image shapes [Taiga #497](https://tree.taiga.io/project/penpot/us/497)
- On user settings, hide the theme selector as long as we only have one theme [Taiga #2610](https://tree.taiga.io/project/penpot/us/2610)
- Automatically open comments from dashboard notifications [Taiga #2605](https://tree.taiga.io/project/penpot/us/2605)
- Enhance the behaviour of the artboards list on view mode [Taiga #2634](https://tree.taiga.io/project/penpot/us/2634)
- Add recent used fonts in font selection widget [Taiga #1381](https://tree.taiga.io/project/penpot/us/1381)
- Allow to align items relative to groups [Taiga #2533](https://tree.taiga.io/project/penpot/us/2533)
- Scroll bars [Taiga #2550](https://tree.taiga.io/project/penpot/task/2550)
- Add select layer option to context menu [Taiga #2474](https://tree.taiga.io/project/penpot/us/2474)
- Guides [Taiga #290](https://tree.taiga.io/project/penpot/us/290)
- Improve file menu by adding semantically groups [Github #1203](https://github.com/penpot/penpot/issues/1203)
- Add update components in bulk option in context menu [Taiga #1975](https://tree.taiga.io/project/penpot/us/1975)
- Create first E2E tests [Taiga #2608](https://tree.taiga.io/project/penpot/task/2608), [Taiga #2608](https://tree.taiga.io/project/penpot/task/2608)
- Redesign of workspace toolbars [Taiga #2319](https://tree.taiga.io/project/penpot/us/2319)
- Graphic Tablet usability improvements [Taiga #1913](https://tree.taiga.io/project/penpot/us/1913)
- Improved mouse collision detection for groups and text shapes [Taiga #2452](https://tree.taiga.io/project/penpot/us/2452), [Taiga #2453](https://tree.taiga.io/project/penpot/us/2453)
- Add support for alternative S3 storage providers and all aws regions [#1267](https://github.com/penpot/penpot/issues/1267)

### :bug: Bugs fixed

- Fixed ungroup typography when editing it [Taiga #2391](https://tree.taiga.io/project/penpot/issue/2391)
- Fixed error when trying to post an empty comment [Taiga #2603](https://tree.taiga.io/project/penpot/issue/2603)
- Fixed missing translation strings [Taiga #2786](https://tree.taiga.io/project/penpot/issue/2786)
- Fixed color palette outside viewport [Taiga #2715](https://tree.taiga.io/project/penpot/issue/2715)
- Fixed missing translate string [Taiga #2780](https://tree.taiga.io/project/penpot/issue/2780)
- Fixed handoff shadow type text [Taiga #2717](https://tree.taiga.io/project/penpot/issue/2717)
- Fixed components get "dirty" marker when moved [Taiga #2764](https://tree.taiga.io/project/penpot/issue/2764)
- Fixed cannot align objects in a group that is not part of a frame [Taiga #2762](https://tree.taiga.io/project/penpot/issue/2762)
- Fix problem with double click on exit path editing [Taiga #2906](https://tree.taiga.io/project/penpot/issue/2906)
- Fixed alignment of layers with children [Taiga #2862](https://tree.taiga.io/project/penpot/issue/2862)

### :heart: Community contributions by (Thank you!)

- Cleanup unused static images (by @rhcarvalho) [#1561](https://github.com/penpot/penpot/pull/1561)
- Compress static images to save space (by @rhcarvalho) [#1562](https://github.com/penpot/penpot/pull/1562)

## 1.11.2-beta

### :bug: Bugs fixed

- Fix issue on handling empty content on boolean shapes
- Fix race condition issue on component renaming
- Handle EOF errors on writing streamed response
- Handle EOF errors on websocket send/ping methods
- Disable parallel upload of file media on import (causes too much
  contention on the rlimit subsistem that does not works as expected
  on high load).

### :sparkles: New features

- Add health check endpoint on API
- Increase default max connection pool size to 60
- Reduce resource usage of the error reporter.

## 1.11.1-beta

### :bug: Bugs fixed

- Fix issue related to default http host config value.
- Fix issue on rendering frames on firefox.

### :arrow_up: Deps updates

- Update nodejs version to 16.13.1 on docker images.

## 1.11.0-beta

### :sparkles: New features

- Add an option to hide artboards names on the viewport [Taiga #2034](https://tree.taiga.io/project/penpot/issue/2034)
- Limit pasted object position to container boundaries [Taiga #2449](https://tree.taiga.io/project/penpot/us/2449)
- Add new options for zoom widget in workspace and viewer mode [Taiga #896](https://tree.taiga.io/project/penpot/us/896)
- Allow decimals on stroke width and positions [Taiga #2035](https://tree.taiga.io/project/penpot/issue/2035)
- Ability to ignore background when exporting an artboard [Taiga #1395](https://tree.taiga.io/project/penpot/us/1395)
- Show color hex or name on hover [Taiga #2413](https://tree.taiga.io/project/penpot/us/2413)
- Add shortcut to create artboard from selected objects [Taiga #2412](https://tree.taiga.io/project/penpot/us/2412)
- Add shortcut for opacity [Taiga #2442](https://tree.taiga.io/project/penpot/us/2442)
- Setting fill automatically for new texts [Taiga #2441](https://tree.taiga.io/project/penpot/us/2441)
- Add shortcut to move action [Github #1213](https://github.com/penpot/penpot/issues/1213)
- Add alt as mod key to add stroke color from library menu [Taiga #2207](https://tree.taiga.io/project/penpot/us/2207)
- Add detach in bulk option to context menu [Taiga #2210](https://tree.taiga.io/project/penpot/us/2210)
- Add penpot look and feel to multiuser cursors [Taiga #1387](https://tree.taiga.io/project/penpot/us/1387)
- Add actions to go to main component context menu option [Taiga #2053](https://tree.taiga.io/project/penpot/us/2053)
- Add contrast between component select color and shape select color [Taiga #2121](https://tree.taiga.io/project/penpot/issue/2121)
- Add animations in interactions [Taiga #2244](https://tree.taiga.io/project/penpot/us/2244)
- Add performance improvements on .penpot file import process [Taiga #2497](https://tree.taiga.io/project/penpot/us/2497)
- On team settings set color of members count to black [Taiga #2607](https://tree.taiga.io/project/penpot/us/2607)

### :bug: Bugs fixed

- Fix remove gradient if any when applying color from library [Taiga #2299](https://tree.taiga.io/project/penpot/issue/2299)
- Fix Enter as key action to exit edit path [Taiga #2444](https://tree.taiga.io/project/penpot/issue/2444)
- Fix add fill color from palette to groups and components [Taiga #2313](https://tree.taiga.io/project/penpot/issue/2313)
- Fix default project name in all languages [Taiga #2280](https://tree.taiga.io/project/penpot/issue/2280)
- Fix line-height and letter-spacing inputs to allow negative values [Taiga #2381](https://tree.taiga.io/project/penpot/issue/2381)
- Fix typo in Handoff tooltip [Taiga #2428](https://tree.taiga.io/project/penpot/issue/2428)
- Fix crash when pressing Shift+1 on empty file [#1435](https://github.com/penpot/penpot/issues/1435)
- Fix masked group resize strange behavior [Taiga #2317](https://tree.taiga.io/project/penpot/issue/2317)
- Fix problems when exporting all artboards [Taiga #2234](https://tree.taiga.io/project/penpot/issue/2234)
- Fix problems with team management [#1353](https://github.com/penpot/penpot/issues/1353)
- Fix problem when importing in shared libraries [#1362](https://github.com/penpot/penpot/issues/1362)
- Fix problem with join nodes [#1422](https://github.com/penpot/penpot/issues/1422)
- After team onboarding importing a file will import into the team drafts [Taiga #2408](https://tree.taiga.io/project/penpot/issue/2408)
- Fix problem exporting shapes from handoff mode [Taiga #2386](https://tree.taiga.io/project/penpot/issue/2386)
- Fix lock/hide elements in context menu when multiples shapes selected [Taiga #2340](https://tree.taiga.io/project/penpot/issue/2340)
- Fix problem with booleans [Taiga #2356](https://tree.taiga.io/project/penpot/issue/2356)
- Fix line-height/letter-spacing inputs behaviour [Taiga #2331](https://tree.taiga.io/project/penpot/issue/2331)
- Fix dotted style in strokes [Taiga #2312](https://tree.taiga.io/project/penpot/issue/2312)
- Fix problem when resizing texts inside groups [Taiga #2310](https://tree.taiga.io/project/penpot/issue/2310)
- Fix problem with multiple exports [Taiga #2468](https://tree.taiga.io/project/penpot/issue/2468)
- Allow import to continue from recoverable failures [#1412](https://github.com/penpot/penpot/issues/1412)
- Improved behaviour on text options when not text is selected [Taiga #2390](https://tree.taiga.io/project/penpot/issue/2390)
- Fix decimal numbers in export viewbox [Taiga #2290](https://tree.taiga.io/project/penpot/issue/2290)
- Right click over artboard name to open its menu [Taiga #1679](https://tree.taiga.io/project/penpot/issue/1679)
- Make the default session cookue use SameSite=Lax instead of Strict (causes some issues in latest versions of Chrome)
- Fix "open in new tab" on dashboard [Taiga #2235](https://tree.taiga.io/project/penpot/issue/2355)
- Changing pages while comments activated will not close the panel [#1350](https://github.com/penpot/penpot/issues/1350)
- Fix navigate comments in right sidebar [Taiga #2163](https://tree.taiga.io/project/penpot/issue/2163)
- Fix keep name of component equal to the shape name [Taiga #2341](https://tree.taiga.io/project/penpot/issue/2341)
- Fix lossing changes when changing selection and an input was already changed [Taiga #2329](https://tree.taiga.io/project/penpot/issue/2329), [Taiga #2330](https://tree.taiga.io/project/penpot/issue/2330)
- Fix blur input field when click on viewport [Taiga #2164](https://tree.taiga.io/project/penpot/issue/2164)
- Fix default page id in workspace [Taiga #2205](https://tree.taiga.io/project/penpot/issue/2205)
- Fix problem when importing a file with grids [Taiga #2314](https://tree.taiga.io/project/penpot/issue/2314)
- Fix problem with imported svgs with filters [Taiga #2478](https://tree.taiga.io/project/penpot/issue/2478)
- Fix issues when updating selrect in paths [Taiga #2366](https://tree.taiga.io/project/penpot/issue/2366)
- Fix scroll jumps in handoff mode [Taiga #2383](https://tree.taiga.io/project/penpot/issue/2383)
- Fix handoff text with opacity [Taiga #2384](https://tree.taiga.io/project/penpot/issue/2384)
- Restored rules color [Taiga #2460](https://tree.taiga.io/project/penpot/issue/2460)
- Fix thumbnail not taking frame blending mode [Taiga #2301](https://tree.taiga.io/project/penpot/issue/2301)
- Fix import/export with SVG edge cases [Taiga #2389](https://tree.taiga.io/project/penpot/issue/2389)
- Avoid modifying component when moving into a group [Taiga #2534](https://tree.taiga.io/project/penpot/issue/2534)
- Show correctly group types label in handoff [Taiga #2482](https://tree.taiga.io/project/penpot/issue/2482)
- Display view mode buttons always centered in viewer [#Taiga 2466](https://tree.taiga.io/project/penpot/issue/2466)
- Fix default profile image generation issue [Taiga #2601](https://tree.taiga.io/project/penpot/issue/2601)
- Fix edit blur attributes for multiselection [Taiga #2625](https://tree.taiga.io/project/penpot/issue/2625)
- Fix auto hide header in viewer full screen [Taiga #2632](https://tree.taiga.io/project/penpot/issue/2632)
- Fix zoom in/out after fit or fill [Taiga #2630](https://tree.taiga.io/project/penpot/issue/2630)
- Normalize zoom levels in workspace and viewer [Taiga #2631](https://tree.taiga.io/project/penpot/issue/2631)
- Avoid empty names in projects, files and pages [Taiga #2594](https://tree.taiga.io/project/penpot/issue/2594)
- Fix "move to" menu when duplicated team or project names [Taiga #2655](https://tree.taiga.io/project/penpot/issue/2655)
- Fix ungroup a component leaves an asterisk in layers [Taiga #2694](https://tree.taiga.io/project/penpot/issue/2694)

### :arrow_up: Deps updates

- Update devenv docker image dependencies

### :heart: Community contributions by (Thank you!)

- Spelling fixes (by @jsoref) [#1340](https://github.com/penpot/penpot/pull/1340)
- Explain folders in components (by @candideu) [Penpot-docs #42](https://github.com/penpot/penpot-docs/pull/42)
- Readability improvements of user guide (by @PaulSchulz) [Penpot-docs #50](https://github.com/penpot/penpot-docs/pull/50)

## 1.10.4-beta

### :sparkles: Enhancements

- Allow parametrice file snapshoting interval

### :bug: Bugs fixed

- Fix issue on :mov-object change impl
- Minor fix on how file changes log is persisted
- Fix many issues on error reporting

## 1.10.3-beta

### :sparkles: Enhancements

- Make all logging asynchronous, this avoid some overhead on jetty threads at cost of logging latency.
- Increase default session time to 15 days.

### :bug: Bugs fixed

- Fix unexpected exception on saving pages with default grids [#2409](https://tree.taiga.io/project/penpot/issue/2409)
- Fix react warnings on setting size 1 on row and column grids.
- Fix minor issues on ZMQ logging listener (used in error reporting service)
- Remove "ALPHA" from the code.
- Fix value and nil handling on numeric-input component. This fixes many issues related to typography, components, etc. renaming.
- Fix NPE on email complains processing.
- Fix white page after leaving a team.
- Fix missing leave team button outside members page.

### :arrow_up: Deps updates

- Update log4j2 dependency.

## 1.10.2-beta

### :bug: Bugs fixed

- Fix corner case issues with media file uploads.
- Fix issue with default page grids validation.
- Fix issue related to some raceconditions on workspace navigation events.

### :arrow_up: Deps updates

- Update log4j2 dependency.

## 1.10.1-beta

### :bug: Bugs fixed

- Fix problems with team management [#1353](https://github.com/penpot/penpot/issues/1353)

## 1.10.0-beta

### :boom: Breaking changes

- The initial project / data mechanism (not documented) has been
  disabled. Is the mechanism used for creating initial project on user
  signup. With the new onboarding approach, this subsystem is no
  longer needed and is disabled.

### :sparkles: New features

- Allow ungroup groups in bulk [Taiga #2211](https://tree.taiga.io/project/penpot/us/2211)
- Enhance corner radius behavior [Taiga #2190](https://tree.taiga.io/project/penpot/issue/2190)
- Allow preserve scroll position in interactions [Taiga #2250](https://tree.taiga.io/project/penpot/us/2250)
- Add new onboarding modals.

### :bug: Bugs fixed

- Fix problem with exporting before the document is saved [Taiga #2189](https://tree.taiga.io/project/penpot/issue/2189)
- Fix undo stacking when changing color from color-picker [Taiga #2191](https://tree.taiga.io/project/penpot/issue/2191)
- Fix pages dropdown in viewer [Taiga #2087](https://tree.taiga.io/project/penpot/issue/2087)
- Fix problem when exporting texts with gradients or opacity [Taiga #2200](https://tree.taiga.io/project/penpot/issue/2200)
- Fix problem with view mode comments [Taiga #2226](https://tree.taiga.io/project/penpot/issue/2226)
- Disallow to create a component when already has one [Taiga #2237](https://tree.taiga.io/project/penpot/issue/2237)
- Add ellipsis in long labels for input fields [Taiga #2224](https://tree.taiga.io/project/penpot/issue/2224)
- Fix problem with text rendering on export [Taiga #2223](https://tree.taiga.io/project/penpot/issue/2223)
- Fix problem when flattening booleans losing styles [Taiga #2217](https://tree.taiga.io/project/penpot/issue/2217)
- Add shortcuts to boolean icons popups [Taiga #2220](https://tree.taiga.io/project/penpot/issue/2220)
- Fix a worker error when transforming a rectangle into path
- Fix max/min values for opacity fields [Taiga #2183](https://tree.taiga.io/project/penpot/issue/2183)
- Fix viewer comment position when zoom applied [Taiga #2240](https://tree.taiga.io/project/penpot/issue/2240)
- Remove change style on hover for options [Taiga #2172](https://tree.taiga.io/project/penpot/issue/2172)
- Fix problem in viewer with dropdowns when comments active [#1303](https://github.com/penpot/penpot/issues/1303)
- Add placeholder to create shareable link
- Fix project files count not refreshing correctly after import [Taiga #2216](https://tree.taiga.io/project/penpot/issue/2216)
- Remove button after import process finish [Taiga #2215](https://tree.taiga.io/project/penpot/issue/2215)
- Fix problem with styles in the viewer [Taiga #2467](https://tree.taiga.io/project/penpot/issue/2467)
- Fix default state in viewer [Taiga #2465](https://tree.taiga.io/project/penpot/issue/2465)
- Fix division by zero in bool operation [Taiga #2349](https://tree.taiga.io/project/penpot/issue/2349)

### :heart: Community contributions by (Thank you!)

- To the translation community for the hard work on making penpot
  available on so many languages.
- Guide to integrate with Azure Directory (by @skrzyneckik) [Penpot-docs #33](https://github.com/penpot/penpot-docs/pull/33)
- Improve libraries section readability (by @PaulSchulz) [Penpot-docs #39](https://github.com/penpot/penpot-docs/pull/39)

## 1.9.0-alpha

### :boom: Breaking changes

- Some stroke-caps can change behaviour.
- Text display bug fix could potentially make some texts jump a line.

### :sparkles: New features

- Add boolean shapes: intersections, unions, difference and exclusions[Taiga #748](https://tree.taiga.io/project/penpot/us/748)
- Add advanced prototyping [Taiga #244](https://tree.taiga.io/project/penpot/us/244)
- Add multiple flows [Taiga #2091](https://tree.taiga.io/project/penpot/us/2091)
- Change order of the teams menu so it's in the joined time order.

### :bug: Bugs fixed

- Enhance duplicating prototype connections behaviour [Taiga #2093](https://tree.taiga.io/project/penpot/us/2093)
- Ignore constraints in horizontal or vertical flip [Taiga #2038](https://tree.taiga.io/project/penpot/issue/2038)
- Fix color and typographies refs lost when duplicated file [Taiga #2165](https://tree.taiga.io/project/penpot/issue/2165)
- Fix problem with overflow dropdown on stroke-cap [#1216](https://github.com/penpot/penpot/issues/1216)
- Fix menu context for single element nested in components [#1186](https://github.com/penpot/penpot/issues/1186)
- Fix error screen when operations over comments fail [#1219](https://github.com/penpot/penpot/issues/1219)
- Fix undo problem when changing typography/color from library [#1230](https://github.com/penpot/penpot/issues/1230)
- Fix problem with text margin while rendering [#1231](https://github.com/penpot/penpot/issues/1231)
- Fix problem with masked texts on exporting [Taiga #2116](https://tree.taiga.io/project/penpot/issue/2116)
- Fix text editor enter behaviour with centered texts [Taiga #2126](https://tree.taiga.io/project/penpot/issue/2126)
- Fix residual stroke on imported svg [Taiga #2125](https://tree.taiga.io/project/penpot/issue/2125)
- Add links for terms of service and privacy policy in register checkbox [Taiga #2020](https://tree.taiga.io/project/penpot/issue/2020)
- Allow three character hex and web colors in color picker hex input [#1184](https://github.com/penpot/penpot/issues/1184)
- Allow lowercase search for fonts [#1180](https://github.com/penpot/penpot/issues/1180)
- Fix group renaming problem [Taiga #1969](https://tree.taiga.io/project/penpot/issue/1969)
- Fix export group with shadows on children [Taiga #2036](https://tree.taiga.io/project/penpot/issue/2036)
- Fix zoom context menu in viewer [Taiga #2041](https://tree.taiga.io/project/penpot/issue/2041)
- Fix stroke caps adjustments in relation with stroke size [Taiga #2123](https://tree.taiga.io/project/penpot/issue/2123)
- Fix problem duplicating paths [Taiga #2147](https://tree.taiga.io/project/penpot/issue/2147)
- Fix problem inheriting attributes from SVG root when importing [Taiga #2124](https://tree.taiga.io/project/penpot/issue/2124)
- Fix problem with lines and inside/outside stroke [Taiga #2146](https://tree.taiga.io/project/penpot/issue/2146)
- Add stroke width in selection calculation [Taiga #2146](https://tree.taiga.io/project/penpot/issue/2146)
- Fix shift+wheel to horizontal scrolling in MacOS [#1217](https://github.com/penpot/penpot/issues/1217)
- Fix path stroke is not working properly with high thickness [Taiga #2154](https://tree.taiga.io/project/penpot/issue/2154)
- Fix bug with transformation operations [Taiga #2155](https://tree.taiga.io/project/penpot/issue/2155)
- Fix bug in firefox when a text box is inside a mask [Taiga #2152](https://tree.taiga.io/project/penpot/issue/2152)
- Fix problem with stroke inside/outside [Taiga #2186](https://tree.taiga.io/project/penpot/issue/2186)
- Fix masks export area [Taiga #2189](https://tree.taiga.io/project/penpot/issue/2189)
- Fix paste in place in artboards [Taiga #2188](https://tree.taiga.io/project/penpot/issue/2188)
- Fix font size input stuck on selection change [Taiga #2184](https://tree.taiga.io/project/penpot/issue/2184)
- Fix stroke cut on shapes export [Taiga #2171](https://tree.taiga.io/project/penpot/issue/2171)
- Fix no color when boolean with an SVG [Taiga #2193](https://tree.taiga.io/project/penpot/issue/2193)
- Fix unlink color styles at strokes [Taiga #2206](https://tree.taiga.io/project/penpot/issue/2206)

### :arrow_up: Deps updates

### :heart: Community contributions by (Thank you!)

- To the translation community for the hard work on making penpot
  available on so many languages.

## 1.8.4-alpha

### :bug: Bugs fixed

- Fix problem importing components [Taiga #2151](https://tree.taiga.io/project/penpot/issue/2151)

## 1.8.3-alpha

### :sparkles: New features

- Adds progress report to importing process.

## 1.8.2-alpha

### :bug: Bugs fixed

- Fix problem with masking images in viewer [#1238](https://github.com/penpot/penpot/issues/1238)

## 1.8.1-alpha

### :bug: Bugs fixed

- Fix project renaming issue (and some other related to the same underlying bug)
- Fix internal exception on audit log persistence layer.
- Set proper environment variable on docker images for chrome executable.
- Fix internal metrics on websocket connections.

## 1.8.0-alpha

### :boom: Breaking changes

- This release includes a new approach for handling share links, and
  this feature is incompatible with the previous one. This means that
  all the public share links generated previously will stop working.

### :sparkles: New features

- Add tooltips to color picker tabs [Taiga #1814](https://tree.taiga.io/project/penpot/us/1814)
- Add styling to the end point of any open paths [Taiga #1107](https://tree.taiga.io/project/penpot/us/1107)
- Allow to zoom with ctrl + middle button [Taiga #1428](https://tree.taiga.io/project/penpot/us/1428)
- Auto placement of duplicated objects [Taiga #1386](https://tree.taiga.io/project/penpot/us/1386)
- Enable penpot SVG metadata only when exporting complete files [Taiga #1914](https://tree.taiga.io/project/penpot/us/1914?milestone=295883)
- Export to PDF all artboards of one page [Taiga #1895](https://tree.taiga.io/project/penpot/us/1895)
- Go to a undo step clicking on a history element of the list [Taiga #1374](https://tree.taiga.io/project/penpot/us/1374)
- Increment font size by 10 with shift+arrows [1047](https://github.com/penpot/penpot/issues/1047)
- New shortcut to detach components Ctrl+Shift+K [Taiga #1799](https://tree.taiga.io/project/penpot/us/1799)
- Set email inputs to type "email", to aid keyboard entry [Taiga #1921](https://tree.taiga.io/project/penpot/issue/1921)
- Use shift+move to move element orthogonally [#823](https://github.com/penpot/penpot/issues/823)
- Use space + mouse drag to pan, instead of only space [Taiga #1800](https://tree.taiga.io/project/penpot/us/1800)
- Allow navigate through pages on the viewer [Taiga #1550](https://tree.taiga.io/project/penpot/us/1550)
- Allow create share links with specific pages [Taiga #1844](https://tree.taiga.io/project/penpot/us/1844)

### :bug: Bugs fixed

- Prevent adding numeric suffix to layer names when not needed [Taiga #1929](https://tree.taiga.io/project/penpot/us/1929)
- Prevent deleting or moving the drafts project [Taiga #1935](https://tree.taiga.io/project/penpot/issue/1935)
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

- eduayme [#1129](https://github.com/penpot/penpot/pull/1129)

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

- Add scroll bar to Teams menu [Taiga #1894](https://tree.taiga.io/project/penpot/issue/1894)
- Fix repeated names when duplicating artboards or groups [Taiga #1892](https://tree.taiga.io/project/penpot/issue/1892)
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

- Allow nested asset groups [Taiga #1716](https://tree.taiga.io/project/penpot/us/1716)
- Allow to ungroup assets [Taiga #1719](https://tree.taiga.io/project/penpot/us/1719)
- Allow to rename assets groups [Taiga #1721](https://tree.taiga.io/project/penpot/us/1721)
- Component constraints (left, right, left and right, center, scale...) [Taiga #1125](https://tree.taiga.io/project/penpot/us/1125)
- Export elements to PDF [Taiga #519](https://tree.taiga.io/project/penpot/us/519)
- Memorize collapse state of assets in panel [Taiga #1718](https://tree.taiga.io/project/penpot/us/1718)
- Headers button sets and menus review [Taiga #1663](https://tree.taiga.io/project/penpot/us/1663)
- Preserve components if possible, when pasted into a different file [Taiga #1063](https://tree.taiga.io/project/penpot/issue/1063)
- Add the ability to offload file data to a cheaper storage when file becomes inactive.
- Import/Export Penpot files from dashboard.
- Double click won't make a shape a path until you change a node [Taiga #1796](https://tree.taiga.io/project/penpot/us/1796)
- Incremental area selection [#779](https://github.com/penpot/penpot/discussions/779)

### :bug: Bugs fixed

- Process numeric input changes only if the value actually changed.
- Remove unnecessary redirect from history when user goes to workspace from dashboard [Taiga #1820](https://tree.taiga.io/project/penpot/issue/1820)
- Detach shapes from deleted assets [Taiga #1850](https://tree.taiga.io/project/penpot/issue/1850)
- Fix tooltip position on view application [Taiga #1819](https://tree.taiga.io/project/penpot/issue/1819)
- Fix dashboard navigation on moving file to other team [Taiga #1817](https://tree.taiga.io/project/penpot/issue/1817)
- Fix workspace header presence styles and invalid link [Taiga #1813](https://tree.taiga.io/project/penpot/issue/1813)
- Fix color-input wrong behavior (on workspace page color) [Taiga #1795](https://tree.taiga.io/project/penpot/issue/1795)
- Fix file contextual menu in shared libraries at dashboard [Taiga #1865](https://tree.taiga.io/project/penpot/issue/1865)
- Fix problem with color picker and fonts [#1049](https://github.com/penpot/penpot/issues/1049)
- Fix negative values in blur [Taiga #1815](https://tree.taiga.io/project/penpot/issue/1815)
- Fix problem when editing color in group [Taiga #1816](https://tree.taiga.io/project/penpot/issue/1816)
- Fix resize/rotate with mouse buttons different than left [#1060](https://github.com/penpot/penpot/issues/1060)
- Fix header partially visible on fullscreen viewer mode [Taiga #1875](https://tree.taiga.io/project/penpot/issue/1875)
- Fix dynamic alignment enabled with hidden objects [#1063](https://github.com/penpot/penpot/issues/1063)

## 1.6.5-alpha

### :bug: Bugs fixed

- Fix problem with paths editing after flip [#1040](https://github.com/penpot/penpot/issues/1040)

## 1.6.4-alpha

### :sparkles: Minor improvements

- Decrease default bulk buffers on storage tasks.
- Reduce file_change preserve interval to 24h.

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
- Add missing cause prop on error logging.
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
- Fix custom fonts embedding issue.
- Fix dashboard ordering issue.
- Fix problem when creating a component with empty data.
- Fix problem with moving shapes into frames.
- Fix problems with mov-objects.
- Fix unexpected exception related to rounding integers.
- Fix wrong type usage on libraries changes.
- Improve editor lifecycle management.
- Make the navigation async by default.

## 1.6.0-alpha

### :sparkles: New features

- Add improved workspace font selector [Taiga US #292](https://tree.taiga.io/project/penpot/us/292)
- Add option to interactively scale text [Taiga #1527](https://tree.taiga.io/project/penpot/us/1527)
- Add performance improvements on dashboard data loading.
- Add performance improvements to indexes handling on workspace.
- Add the ability to upload/use custom fonts (and automatically generate all needed webfonts) [Taiga US #292](https://tree.taiga.io/project/penpot/us/292)
- Transform shapes to path on double click
- Translate automatic names of new files and projects.
- Use shift instead of ctrl/cmd to keep aspect ratio [Taiga 1697](https://tree.taiga.io/project/penpot/issue/1697)
- New translations: Portuguese (Brazil) and Romanias.

### :bug: Bugs fixed

- Remove interactions when the destination artboard is deleted [Taiga #1656](https://tree.taiga.io/project/penpot/issue/1656)
- Fix problem with fonts that ends with numbers [#940](https://github.com/penpot/penpot/issues/940)
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
- Add native support for text-direction (RTL, LTR & auto)
- Add several enhancements in shape selection [Taiga #1195](https://tree.taiga.io/project/penpot/us/1195)
- Add thumbnail in memory caching mechanism.
- Add turkish translation strings [#759](https://github.com/penpot/penpot/pull/759), [#794](https://github.com/penpot/penpot/pull/794)
- Duplicate and move files and projects [Taiga #267](https://tree.taiga.io/project/penpot/us/267)
- Hide viewer navbar on fullscreen [Taiga 1375](https://tree.taiga.io/project/penpot/us/1375)
- Import SVG will create Penpot's shapes [Taiga #1006](https://tree.taiga.io/project/penpot/us/1066)
- Improve french translations [#731](https://github.com/penpot/penpot/pull/731)
- Reimplement workspace presence (remove database state)
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
  unnecessary conflict with bash interpolation.

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
- Add more chinese translations [#687](https://github.com/penpot/penpot/pull/687)
- Add more presets for artboard [#654](https://github.com/penpot/penpot/pull/654)
- Add optional loki integration [#645](https://github.com/penpot/penpot/pull/645)
- Add proper http session lifecycle handling.
- Allow to set border radius of each rect corner individually
- Bounce & Complaint handling [#635](https://github.com/penpot/penpot/pull/635)
- Disable groups interactions when holding "Ctrl" key (deep selection)
- New action in context menu to "edit" some shapes (bound to key "Enter")

### :bug: Bugs fixed

- Add more improvements to french translation strings [#591](https://github.com/penpot/penpot/pull/591)
- Add some missing database indexes (mainly improves performance on large databases on file-update rpc method, and some background tasks)
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
- Properly mark profile auth backend (on first register/ auth with 3rd party auth provider)
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
