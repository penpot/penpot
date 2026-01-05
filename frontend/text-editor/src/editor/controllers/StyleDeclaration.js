export class StyleDeclaration {
  static Property = class Property {
    static NULL = '["~#\'",null]';

    static Default = new Property("", "", "");

    name;
    value = "";
    priority = "";

    constructor(name, value = "", priority = "") {
      this.name = name;
      this.value = value ?? "";
      this.priority = priority ?? "";
    }
  };

  #items = new Map();

  get cssFloat() {
    throw new Error("Not implemented");
  }

  get cssText() {
    throw new Error("Not implemented");
  }

  get parentRule() {
    throw new Error("Not implemented");
  }

  get length() {
    return this.#items.size;
  }

  #getProperty(name) {
    return this.#items.get(name) ?? StyleDeclaration.Property.Default;
  }

  getPropertyPriority(name) {
    const { priority } = this.#getProperty(name);
    return priority ?? "";
  }

  getPropertyValue(name) {
    const { value } = this.#getProperty(name);
    return value ?? "";
  }

  item(index) {
    return Array.from(this.#items).at(index).name;
  }

  removeProperty(name) {
    const value = this.getPropertyValue(name);
    this.#items.delete(name);
    return value;
  }

  setProperty(name, value, priority) {
    this.#items.set(name, new StyleDeclaration.Property(name, value, priority));
  }

  /** Non compatible methods */
  #isQuotedValue(a, b) {
    if (a.startsWith('"') && b.startsWith('"')) {
      return a === b;
    } else if (a.startsWith('"') && !b.startsWith('"')) {
      return a.slice(1, -1) === b;
    } else if (!a.startsWith('"') && b.startsWith('"')) {
      return a === b.slice(1, -1);
    }
    return a === b;
  }

  mergeProperty(name, value) {
    const currentValue = this.getPropertyValue(name);
    if (this.#isQuotedValue(currentValue, value)) {
      return this.setProperty(name, value);
    } else if (
      currentValue === "" &&
      value === StyleDeclaration.Property.NULL
    ) {
      return this.setProperty(name, value);
    } else if (currentValue === "" && ["initial", "none"].includes(value)) {
      return this.setProperty(name, value);
    } else if (currentValue !== value && name === "--fills") {
      return this.setProperty(name, value);
    } else if (currentValue !== value) {
      return this.setProperty(name, "mixed");
    }
  }

  fromCSSStyleDeclaration(cssStyleDeclaration) {
    for (let index = 0; index < cssStyleDeclaration.length; index++) {
      const name = cssStyleDeclaration.item(index);
      const value = cssStyleDeclaration.getPropertyValue(name);
      const priority = cssStyleDeclaration.getPropertyPriority(name);
      this.setProperty(name, value, priority);
    }
  }

  toObject() {
    return Object.fromEntries(
      Array.from(this.#items.entries(), ([name, property]) => [
        name,
        property.value,
      ]),
    );
  }
}

export default StyleDeclaration;
