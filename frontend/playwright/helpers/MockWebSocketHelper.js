export class MockWebSocketHelper extends EventTarget {
  static #mocks = new Map();

  static async init(page) {
    this.#mocks = new Map();

    await page.exposeFunction("onMockWebSocketConstructor", (url) => {
      const webSocket = new MockWebSocketHelper(page, url);
      this.#mocks.set(url, webSocket);
    });
    await page.exposeFunction("onMockWebSocketSpyMessage", (url, data) => {
      if (!this.#mocks.has(url)) {
        throw new Error(`WebSocket with URL ${url} not found`);
      }
      this.#mocks.get(url).dispatchEvent(new MessageEvent("message", { data }));
    });
    await page.exposeFunction(
      "onMockWebSocketSpyClose",
      (url, code, reason) => {
        if (!this.#mocks.has(url)) {
          throw new Error(`WebSocket with URL ${url} not found`);
        }
        this.#mocks
          .get(url)
          .dispatchEvent(new CloseEvent("close", { code, reason }));
      },
    );
    await page.addInitScript({ path: "playwright/scripts/MockWebSocket.js" });
  }

  static waitForURL(url) {
    return new Promise((resolve) => {
      const intervalID = setInterval(() => {
        for (const [wsURL, ws] of this.#mocks) {
          if (wsURL.includes(url)) {
            clearInterval(intervalID);
            return resolve(ws);
          }
        }
      }, 30);
    });
  }

  #page = null;
  #url;

  constructor(page, url, protocols) {
    super();
    this.#page = page;
    this.#url = url;
  }

  mockOpen(options) {
    return this.#page.evaluate(
      ({ url, options }) => {
        if (typeof WebSocket.getByURL !== "function") {
          throw new Error(
            "WebSocket.getByURL is not a function. Did you forget to call MockWebSocket.init(page)?",
          );
        }
        WebSocket.getByURL(url).mockOpen(options);
      },
      { url: this.#url, options },
    );
  }

  mockMessage(data) {
    return this.#page.evaluate(
      ({ url, data }) => {
        if (typeof WebSocket.getByURL !== "function") {
          throw new Error(
            "WebSocket.getByURL is not a function. Did you forget to call MockWebSocket.init(page)?",
          );
        }
        WebSocket.getByURL(url).mockMessage(data);
      },
      { url: this.#url, data },
    );
  }

  mockClose() {
    return this.#page.evaluate(
      ({ url }) => {
        if (typeof WebSocket.getByURL !== "function") {
          throw new Error(
            "WebSocket.getByURL is not a function. Did you forget to call MockWebSocket.init(page)?",
          );
        }
        WebSocket.getByURL(url).mockClose();
      },
      { url: this.#url },
    );
  }
}
