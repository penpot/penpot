---
layout: layouts/plugins-no-sidebar.njk
title: Beta changelog
desc: See the Penpot plugin API changelog for version 1.0! Find breaking changes, deprecations, new features, and updated documentation. Try Penpot for free.
---

# Beta changelog

### <g-emoji class="g-emoji" alias="boom" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f680.png"><img class="emoji" alt="boom" height="20" width="20" src="https://github.githubassets.com/images/icons/emoji/unicode/1f680.png"></g-emoji> Epics and highlights</code>
- This marks the release of version 1.0, and from this point forward, we’ll do our best to avoid making any more breaking changes (or make deprecations backward compatible).
- We’ve redone the documentation. You can check the API here:
[https://penpot-plugins-api-doc.pages.dev/](https://penpot-plugins-api-doc.pages.dev/)
- New samples repository with lots of samples to use the API:
[https://github.com/penpot/penpot-plugins-samples](https://github.com/penpot/penpot-plugins-samples)

### <g-emoji class="g-emoji" alias="boom" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f4a5.png"><img class="emoji" alt="boom" height="20" width="20" src="https://github.githubassets.com/images/icons/emoji/unicode/1f4a5.png"></g-emoji> Breaking changes & Deprecations

- Changed types names to remove the Penpot prefix. So for example: <code class="language-js">PenpotShape</code> becomes <code class="language-js">Shape</code>; <code class="language-js">PenpotFile</code> becomes <code class="language-js">File</code>, and so on. Check the [API documentation](https://penpot-plugins-api-doc.pages.dev/) for more details.
- Changes on the <code class="language-js">penpot.on</code> and <code class="language-js">penpot.off</code> methods.
Previously you had to send the original callback to the off method in order to remove an event listener. Now, <code class="language-js">penpot.on</code> will return an *id* that you can pass to the <code class="language-js">penpot.off</code> method in order to remove the listener.

Previously:
```js
penpot.on(‘pagechange’, myListener); // Register an event listener
penpot.off(‘pagechange’, myListener); // Remove previously registered listener
```

Now:
```js
const id = penpot.on(‘pagechange’, myListener);
penpot.off(id);
```

We’ve deprecated the old behavior in favor of the new one, this means that the behavior will work in the next version, but will be removed further down the line.

- Change some names to better align with the names in Penpot's UI.
  - type <code class="language-js">frame</code> is now <code class="language-js">board</code>:
    - <code class="language-js">PenpotFrame</code> is now <code class="language-js">Board</code>
    - <code class="language-js">penpot.createFrame</code> changed to <code class="language-js">penpot.createBoard</code>
    - <code class="language-js">shape.frameX</code> / <code class="language-js">shape.frameY</code> changed to<code class="language-js">shape.boardX</code> / <code class="language-js">shape.boardY</code>
    - <code class="language-js">PenpotFrameGuideX</code> now <code class="language-js">GuideX</code>
  - type<code class="language-js">rect</code> is <code class="language-js">rectangle</code>
    - <code class="language-js">PenpotRectangle</code> is now <code class="language-js">Rectangle</code>
  - type<code class="language-js">circle</code> is<code class="language-js">ellipse</code>
    - <code class="language-js">PenpotCircle</code> is now <code class="language-js">Ellipse</code>
    - <code class="language-js">penpot.createCircle</code> changed to <code class="language-js">penpot.createEllipse</code>
  - type<code class="language-js">bool</code> is<code class="language-js">boolean</code>
    - <code class="language-js">PenpotBool</code> is now <code class="language-js">Boolean</code>
- Removed the following methods
  - <code class="language-js">getPage</code>, you can use now the property <code class="language-js">currentPage</code>
  - <code class="language-js">getFile</code>, you can use now the property <code class="language-js">currentFile</code>
  - <code class="language-js">getTheme</code>, you can use now the property <code class="language-js">theme</code>
  - <code class="language-js">getSelected</code>, you can use the property <code class="language-js">selection</code>
  - <code class="language-js">getSelectedShapes</code>, you can use the property <code class="language-js">selection</code>

### <g-emoji class="g-emoji" alias="sparkles" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2728.png"><img class="emoji" alt="sparkles" height="20" width="20" src="https://github.githubassets.com/images/icons/emoji/unicode/2728.png"></g-emoji> New features

- Support for comments
- Support for export files
- Support for undo blocks
- Support for ruler guides
- Support for prototype functionality access
- New geometry utils:
  - shape.bounds
  - shape.center
- New events
  - contentsave
  - shapechange
- Adds property file.pages
- Adds parent reference to shape
- Add root shape reference to page
- Add detach shape to component method
- Adds method to createPage and openPage
- Adds shape.visible property
- Adds method penpot.viewport.zoomToShapes to change the viewport to the shapes.
