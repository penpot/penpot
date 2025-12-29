# Create API

Add your API in `libs/plugins-runtime/src/lib/api/index.ts`.

Try to use `zod` to validate the input an output, for example:

```ts
{
  sum: z.function()
    .args(z.number(), z.number())
    .returns(z.number())
    .implement((callback, time) => {
      setTimeout(callback, time);
    });
}
```

Update `/libs/plugins-runtime/src/lib/api/index.d.ts`.
