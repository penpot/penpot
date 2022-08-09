# CHANGELOG

## 1.15.0-beta

### :boom: Breaking changes & Deprecations

- The `PENPOT_LOGIN_WITH_LDAP` environment variable is finally removed (after
  many version with deprecation). It is replaced with the
  `enable-login-with-ldap` flag.
- The `PENPOT_LDAP_ATTRS_PHOTO` finally removed, it was unused for many
  versions.
- If you are using social login (google, github, gitlab or generic OIDC) you
  will need to ensure to add the following flags respectivelly to let them
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

- Allow for nested and rotated boards inside other boards and groups [Taiga #2874](https://tree.taiga.io/project/penpot/us/2874?milestone=319982)
- View mode improvements to enable access and use in different conditions [Taiga #3023](https://tree.taiga.io/project/penpot/us/3023)
- Improved share link options. Now you can allow non-team members to comment and/or inspect [Taiga #3056] (https://tree.taiga.io/project/penpot/us/3056)
- Signin/Signup from shared link [Taiga #3472](https://tree.taiga.io/project/penpot/us/3472)
- Support for import/export binary format [Taiga #2991](https://tree.taiga.io/project/penpot/us/2991)
- Comments positioning [Taiga #2007](https://tree.taiga.io/project/penpot/us/2007)
- Select all inside a group select only the objects at this group level [Taiga #2382](https://tree.taiga.io/project/penpot/issue/2382)
- Make the media maximum upload size configurable

### :bug: Bugs fixed

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



### :arrow_up: Deps updates
### :heart: Community contributions by (Thank you!)

## 1.14.2-beta

### :bug: Bugs fixed

- Fix colors from unlinked libs in color selected widget [Taiga #3712](https://tree.taiga.io/project/penpot/issue/3712)
- Fix fill information not complete when paste plain text [Taiga #3680](https://tree.taiga.io/project/penpot/issue/3680)
- Fix problem when resizing groups [Taiga #3702](https://tree.taiga.io/project/penpot/issue/3702)

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
- Fix environment imporot for exporter in Docker
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
- Fix unneccessary scrollbars at the color list [Taiga #3211](https://tree.taiga.io/project/penpot/issue/3211)
- "Show in exports" is showing in multiselections [Taiga #3194](https://tree.taiga.io/project/penpot/issue/3194)
- Edit file name navigates to the file workspace [Taiga #3183](https://tree.taiga.io/project/penpot/issue/3183)
- Fix scroll into view behind fixed element [Taiga #3170](https://tree.taiga.io/project/penpot/issue/3170)
- Fix sidebar icon in viewer mode [Taiga #3184](https://tree.taiga.io/project/penpot/issue/3184)
- Fix send to back several shapes at a time [Taiga #3077](https://tree.taiga.io/project/penpot/issue/3077)
- Fix duplicate multi selected elements [Taiga #3155](https://tree.taiga.io/project/penpot/issue/3155)
- Fix add fills to artboard modify children [Taiga #3151](https://tree.taiga.io/project/penpot/issue/3151)
- Avoid numeric inputs to allow big numbers [Taiga #2858](https://tree.taiga.io/project/penpot/issue/2858)
- Fix component contex menu size [Taiga #2480](https://tree.taiga.io/project/penpot/issue/2480)
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
- Handle EOF errors on writting streamed response
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

### :sparkles: Enhacements

- Allow parametrice file snapshoting interval

### :bug: Bugs fixed

- Fix issue on :mov-object change impl
- Minor fix on how file changes log is persisted
- Fix many issues on error reporting

## 1.10.3-beta

### :sparkles: Enhacements

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
