console.log("MockWebSocket mock loaded");
window.WebSocket = class MockWebSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  static #mocks = new Map();

  static getAll() {
    return this.#mocks.values();
  }

  static getByURL(url) {
    return this.#mocks.get(url);
  }

  #url;
  #protocols;
  #protocol = "";
  #binaryType = "blob";
  #bufferedAmount = 0;
  #extensions = "";
  #readyState = MockWebSocket.CONNECTING;

  #onopen = null;
  #onerror = null;
  #onmessage = null;
  #onclose = null;

  #spyMessage = null;
  #spyClose = null;

  constructor(url, protocols) {
    super();

    console.log("MockWebSocket", url, protocols);

    this.#url = url;
    this.#protocols = protocols || [];

    MockWebSocket.#mocks.set(this.#url, this);

    if (typeof window["MockWebSocket$$constructor"] === "function") {
      MockWebSocket$$constructor(this.#url, this.#protocols);
    }
    if (typeof window["MockWebSocket$$spyMessage"] === "function") {
      this.#spyMessage = MockWebSocket$$spyMessage;
    }
    if (typeof window["MockWebSocket$$spyClose"] === "function") {
      this.#spyClose = MockWebSocket$$spyClose;
    }
  }

  set binaryType(binaryType) {
    if (!["blob", "arraybuffer"].includes(binaryType)) {
      return;
    }
    this.#binaryType = binaryType;
  }

  get binaryType() {
    return this.#binaryType;
  }

  get bufferedAmount() {
    return this.#bufferedAmount;
  }

  get extensions() {
    return this.#extensions;
  }

  get readyState() {
    return this.#readyState;
  }

  get protocol() {
    return this.#protocol;
  }

  get url() {
    return this.#url;
  }

  set onopen(callback) {
    if (callback === null) {
      this.removeEventListener("open", this.#onopen);
    } else if (typeof callback === "function") {
      if (this.#onopen) this.removeEventListener("open", this.#onopen);
      this.addEventListener("open", callback);
    }
    this.#onopen = callback;
  }

  get onopen() {
    return this.#onopen;
  }

  set onerror(callback) {
    if (callback === null) {
      this.removeEventListener("error", this.#onerror);
    } else if (typeof callback === "function") {
      if (this.#onerror) this.removeEventListener("error", this.#onerror);
      this.addEventListener("error", callback);
    }
    this.#onerror = callback;
  }

  get onerror() {
    return this.#onerror;
  }

  set onmessage(callback) {
    if (callback === null) {
      this.removeEventListener("message", this.#onmessage);
    } else if (typeof callback === "function") {
      if (this.#onmessage) this.removeEventListener("message", this.#onmessage);
      this.addEventListener("message", callback);
    }
    this.#onmessage = callback;
  }

  get onmessage() {
    return this.#onmessage;
  }

  set onclose(callback) {
    if (callback === null) {
      this.removeEventListener("close", this.#onclose);
    } else if (typeof callback === "function") {
      if (this.#onclose) this.removeEventListener("close", this.#onclose);
      this.addEventListener("close", callback);
    }
    this.#onclose = callback;
  }

  get onclose() {
    return this.#onclose;
  }

  get mockProtocols() {
    return this.#protocols;
  }

  spyClose(callback) {
    if (typeof callback !== "function") {
      throw new TypeError("Invalid callback");
    }
    this.#spyClose = callback;
    return this;
  }

  spyMessage(callback) {
    if (typeof callback !== "function") {
      throw new TypeError("Invalid callback");
    }
    this.#spyMessage = callback;
    return this;
  }

  mockOpen(options) {
    this.#protocol = options?.protocol || "";
    this.#extensions = options?.extensions || "";
    this.#readyState = MockWebSocket.OPEN;
    this.dispatchEvent(new Event("open"));
    return this;
  }

  mockError(error) {
    this.#readyState = MockWebSocket.CLOSED;
    this.dispatchEvent(new ErrorEvent("error", { error }));
    return this;
  }

  mockMessage(data) {
    if (this.#readyState !== MockWebSocket.OPEN) {
      throw new Error("MockWebSocket is not connected");
    }
    this.dispatchEvent(new MessageEvent("message", { data }));
    return this;
  }

  mockClose(code, reason) {
    this.#readyState = MockWebSocket.CLOSED;
    this.dispatchEvent(new CloseEvent("close", { code: code || 1000, reason: reason || "" }));
    return this;
  }

  send(data) {
    console.log(data);
    if (this.#readyState === MockWebSocket.CONNECTING) {
      throw new DOMException("InvalidStateError", "MockWebSocket is not connected");
    }
    console.log(`MockWebSocket send: ${data}`);
    this.#spyMessage && this.#spyMessage(this.url, data);
  }

  close(code, reason) {
    console.log(code, reason);
    if (code && !Number.isInteger(code) && code !== 1000 && (code < 3000 || code > 4999)) {
      throw new DOMException("InvalidAccessError", "Invalid code");
    }

    if (reason && typeof reason === "string") {
      const reasonBytes = new TextEncoder().encode(reason);
      if (reasonBytes.length > 123) {
        throw new DOMException("SyntaxError", "Reason is too long");
      }
    }

    if ([MockWebSocket.CLOSED, MockWebSocket.CLOSING].includes(this.#readyState)) {
      return;
    }

    this.#readyState = MockWebSocket.CLOSING;
    console.log("MockWebSocket close");
    this.#spyClose && this.#spyClose(this.url, code, reason);
  }
}
