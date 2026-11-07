# Non-JS dependencies in the production images

Node and pnpm pins: `mem:workflow/updating-node` and `mem:workflow/updating-pnpm`. Only the parts the Dockerfiles cannot show:

- JDK: the image tracks a non-LTS line, so there is no minor to pick — the last build of the line is the ceiling; moving line is a major and needs a real build to validate the `jlink` module list.
- ImageMagick: publish `penpotapp/imagemagick:<ver>` **before** bumping the `COPY --from` refs in the backend, exporter and devenv Dockerfiles; otherwise both builds break on the missing tag.
