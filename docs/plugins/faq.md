---
layout: layouts/plugins.njk
title: 6. FAQ
desc: Find answers to common questions about plugin development, from choosing the right Node version to creating components. See Penpot plugins!
---

# FAQ

### Which Node version should I use?

Currently we are using the v22.2.0

### Should I create my plugin for dark and light themes?

It’s not obligatory but keep in mind that the containing modal will change colors automatically to match Penpot’s theme. Check this <a target="_blank" href="https://github.com/penpot/penpot-plugins-samples/tree/main/theme">example</a> on how to apply dark and light themes to your plugin.

### Should I always host my plugin?

By the time being any and all plugins must be hosted independently and outside the Penpot environment. Check the <a target="_blank" href="/plugins/deployment/">documentation</a> for a guide on how to deploy your plugin on some deployment services like Netlify or Cloudflare.

### Is there any way to export my figma plugins to penpot?

No. The feature set of figma and penpot are not the same so it’s not compatible.

### What is the recommended size for my plugin icon?

You can make it any size since it will be automatically adjusted to 56x56 px in the plugin manager modal. Just make sure to keep it square size.

### Are there any naming conventions for the plugin name?

The name of the plugin should be short and followed by the suffix ‘-plugins’, like ‘shape-remover-plugin’.

### Which framework do you recommend for creating the plugin?

Any framework you are familiar with would be a good choice. Our examples are in vue, angular and react. Check the <a target="_blank" href="/plugins/create-a-plugin/">documentation</a>

### Is it necessary to use the plugin styles library?

The plugin <a target="_blank" href="https://www.npmjs.com/package/@penpot/plugin-styles">styles library</a> is not obligatory, although we recommend its use because it'll help you with the dark and light theming and to maintain the Penpot look-and-feel.

### Is the API ready to use the prototyping features?

Absolutely! You can definitely create flows and interactions in the same elements as in the interface, like frames, shapes, and groups. Just check out the API documentation for the methods: createFlow, addInteraction, or removeInteraction. And if you need more help, you can always check out the <a target="_blank" href="https://penpot-plugins-api-doc.pages.dev/interfaces/PenpotFlow">PenpotFlow</a> or <a target="_blank" href="https://penpot-plugins-api-doc.pages.dev/interfaces/PenpotInteraction">PenpotInteraction</a> interfaces.

### Are there any security or quality criteria I should be aware of?

There are no set requirements. However, we can recommend the use of <a target="_blank" href="https://typescript-eslint.io/">eslint</a> or <a target="_blank" href="https://prettier.io/">prettier</a>, which is what we use.

### Is it necessary to create plugins with a UI?

No, it’s completely optional, in fact, we have an example of a plugin without UI. Try the plugin using this url to install it: <code class="language-js">https:\/\/create-palette-penpot-plugin.pages.dev/assets/manifest.json</code> or check the code <a target="_blank" href="https://github.com/penpot/penpot-plugins/tree/main/apps/create-palette-plugin">here</a>

### Can I create components?

Yes, it is possible to create components using:

```js
createComponent(shapes: Shape[]): LibraryComponent;
```

Take a look at the Penpot Library methods in the <a target="_blank" href="https://penpot-plugins-api-doc.pages.dev/interfaces/Library">API documentation</a> or this <a target="_blank" href="https://github.com/penpot/penpot-plugins-samples/tree/main/components-library">simple example</a>.

### Is there a place where I can share my plugin?

<a target="_blank" href="https://penpot.app/penpothub">Penpot Hub</a> is where you can share plugins, templates and libraries all made possible through open-source collaboration. To add your plugin to our catalog, simply fill out <a target="_blank" href="https://penpot.app/penpothub/plugins/create-plugin">this form</a> with your plugin's details.

### My plugin works on my local machine, but I couldn’t install it on Penpot. What could be the problem?

The url you that you need to provide in the plugin manager should look <a target="_blank" href="/plugins/create-a-plugin/#2.6.-step-6.-configure-the-manifest-file">like this</a>: <code class="language-bash">https:\/\/yourdomain.com/assets/manifest.json</code>

### Where can I get support if I find a bug or an unexpected behavior?

You can report a problem or request support at <a href="mailto:support@penpot.app">support@penpot.app</a>.
