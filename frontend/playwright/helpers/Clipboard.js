export class Clipboard {
  static Permission = {
    ONLY_READ: ['clipboard-read'],
    ONLY_WRITE: ['clipboard-write'],
    ALL: ['clipboard-read', 'clipboard-write']
  }

  static enable(context, permissions) {
    return context.grantPermissions(permissions)
  }

  static writeText(page, text) {
    return page.evaluate((text) => navigator.clipboard.writeText(text), text);
  }

  static readText(page) {
    return page.evaluate(() => navigator.clipboard.readText());
  }

  constructor(page, context) {
    this.page = page
    this.context = context
  }

  enable(permissions) {
    return Clipboard.enable(this.context, permissions);
  }

  writeText(text) {
    return Clipboard.writeText(this.page, text);
  }

  readText() {
    return Clipboard.readText(this.page);
  }
}
