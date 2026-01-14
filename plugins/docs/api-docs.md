### Plugins API Documentation

This document shows you how to create API documentation.

#### On your local

If you want to see what the document will look like (the HTML that's generated), you can run the following command:

```shell
pnpm run build:doc
```

Once you've done that, you'll find the result in `./dist/doc`

#### Deploy the API Documentation

Just move to the `stable` branch in this repository and rebase it with
the latest changes from the `main` branch. This will trigger the
deployment at Cloudfare if the `libs/plugin-types/index.d.ts` or the
`tools/typedoc.css` files have been updated.

Take a look at the [Penpot plugins API](https://penpot-plugins-api-doc.pages.dev/) to see what's new.

#### Styles

If you want to make some style changes you can do it in `/tools/typedoc.css`.
