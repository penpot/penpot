export class MockWebSocket extends EventTarget {
  static #mocks = new Map();

  static async init(page) {
    await page.exposeFunction('MockWebSocket$$constructor', (url, protocols) => {
      console.log('MockWebSocket$$constructor', MockWebSocket, url, protocols)
      const webSocket = new MockWebSocket(page, url, protocols);
      this.#mocks.set(url, webSocket);
    });
    await page.exposeFunction('MockWebSocket$$spyMessage', (url, data) => {
      console.log('MockWebSocket$$spyMessage', url, data)
      this.#mocks.get(url).dispatchEvent(new MessageEvent('message', { data }))
    });
    await page.exposeFunction('MockWebSocket$$spyClose', (url, code, reason) => {
      console.log('MockWebSocket$$spyClose', url, code, reason)
      this.#mocks.get(url).dispatchEvent(new CloseEvent('close', { code, reason }))
    });
    await page.addInitScript({ path: "playwright/scripts/MockWebSocket.js" });
  }

  static waitForURL(url) {
    return new Promise((resolve) => {
      let intervalID = setInterval(() => {
        for (const [wsURL, ws] of this.#mocks) {
          console.log(wsURL)
          if (wsURL.includes(url)) {
            clearInterval(intervalID);
            return resolve(ws);
          }
        }
      }, 30)
    })
  }

  #page = null
  #url
  #protocols

  // spies.
  #spyClose = null
  #spyMessage = null

  constructor(page, url, protocols) {
    super()
    this.#page = page
    this.#url = url
    this.#protocols = protocols
  }

  mockOpen(options) {
    return this.#page.evaluate((options) => {
      WebSocket.getByURL(url).mockOpen(options)
    }, options)
  }

  mockMessage(data) {
    return this.#page.evaluate((data) => {
      WebSocket.getByURL(url).mockMessage(data)
    }, data)
  }

  mockClose() {
    return this.#page.evaluate(() => {
      WebSocket.getByURL(url).mockClose()
    })
  }

  spyClose(fn) {
    if (typeof fn !== 'function') {
      throw new TypeError('Invalid callback')
    }
    this.#spyClose = fn
  }

  spyMessage(fn) {
    if (typeof fn !== 'function') {
      throw new TypeError('Invalid callback')
    }
    this.#spyMessage = fn
  }
}
