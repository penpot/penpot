;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.clipboard
  (:require
   ["./clipboard.js" :as impl]
   [app.common.transit :as t]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def image-types
  ["image/webp"
   "image/png"
   "image/jpeg"
   "image/svg+xml"])

(def ^:private default-options
  #js {:decodeTransit t/decode-str
       :allowHTMLPaste false})

(defn- from-data-transfer
  "Get clipboard stream from DataTransfer instance"
  ([data-transfer]
   (from-data-transfer data-transfer default-options))
  ([data-transfer options]
   (->> (rx/from (impl/fromDataTransfer data-transfer options))
        (rx/mapcat #(rx/from %)))))

(defn from-navigator
  ([]
   (from-navigator default-options))
  ([options]
   (->> (rx/from (impl/fromNavigator options))
        (rx/mapcat #(rx/from %)))))

(defn from-clipboard-event
  "Get clipboard stream from clipboard event"
  ([event]
   (from-clipboard-event event default-options))
  ([event options]
   (let [cdata (.-clipboardData ^js event)]
     (from-data-transfer cdata options))))

(defn from-synthetic-clipboard-event
  "Get clipboard stream from synthetic clipboard event"
  ([event options]
   (let [target
         (dom/get-target event)

         content-editable?
         (dom/is-content-editable? target)

         is-input?
         (= (dom/get-tag-name target) "INPUT")]

     ;; ignore when pasting into an editable control
     (if-not (or content-editable? is-input?)
       (-> event
           (dom/event->browser-event)
           (from-clipboard-event options))
       (rx/empty)))))

(defn from-drop-event
  "Get clipboard stream from drop event"
  ([event]
   (from-drop-event event default-options))
  ([event options]
   (from-data-transfer (.-dataTransfer ^js event) options)))

(def ^:private unavailable-error-message
  "Clipboard API is unavailable. This usually happens when the page is served over plain HTTP; serve Penpot over HTTPS to enable copy-to-clipboard.")

(defn- get-clipboard
  "Return the active `navigator.clipboard` object, or nil when the
   asynchronous Clipboard API is not exposed (e.g. on insecure origins
   per the W3C spec, which is what triggered #4478)."
  []
  (unchecked-get js/navigator "clipboard"))

(defn- unavailable-error
  "Build the error wrapped in a rejected Promise so callers can chain
   `rx/from`/`.catch` and surface a meaningful message instead of the
   opaque `TypeError: Cannot read properties of undefined (reading
   'writeText')` that leaked out of the previous implementation."
  []
  (js/Promise.reject (js/Error. unavailable-error-message)))

(defn to-clipboard
  "Write a plain-text string to the system clipboard.

   Always returns a Promise. Resolves on success; rejects with a clear
   `Error` when `navigator.clipboard` is unavailable (insecure origin)
   so callers can chain error handling instead of crashing the UI on a
   synchronous `TypeError` like #4478."
  [data]
  (assert (string? data) "`data` should be string")
  (let [clipboard (get-clipboard)]
    (if (and clipboard (unchecked-get clipboard "writeText"))
      (.writeText ^js clipboard data)
      (unavailable-error))))

(defn- create-clipboard-item
  [mimetype promise]
  (js/ClipboardItem.
   (js-obj mimetype promise)))

(defn to-clipboard-promise
  "Write a single asynchronous payload to the clipboard under the given
   MIME type. The `promise` is resolved by the browser when the
   ClipboardItem is committed.

   Returns the Promise produced by `clipboard.write`, or a rejected
   Promise carrying an `Error` when the asynchronous Clipboard API is
   not available (insecure origin / unsupported browser). Mirrors
   `to-clipboard`'s defensive contract."
  [mimetype promise]
  (let [clipboard (get-clipboard)]
    (if (and clipboard (unchecked-get clipboard "write"))
      (let [data (create-clipboard-item mimetype promise)]
        (.write ^js clipboard #js [data]))
      (unavailable-error))))

(defn- get-clipboard-item-ctor
  "Return the `ClipboardItem` constructor, or nil on the browsers that
   expose `clipboard.write` without it (Chrome < 116)."
  []
  (unchecked-get js/globalThis "ClipboardItem"))

(defn- writable-mime?
  "Browsers keep an allowlist of MIME types accepted by
   `clipboard.write`; `image/svg+xml` is outside Firefox's (#10596).
   `ClipboardItem.supports` exposes it when available; assume writable
   otherwise, since `write` is still guarded by a rejection fallback."
  [mime]
  (let [ctor     (get-clipboard-item-ctor)
        supports (some-> ctor (unchecked-get "supports"))]
    (if (fn? supports)
      (boolean (.call ^js supports ctor mime))
      true)))

(defn- unsupported-mime-error?
  "True when `cause` is the `DOMException` a browser raises for a MIME
   type outside its clipboard allowlist (#10596): \"Type 'image/svg+xml'
   not supported for write\". Any other rejection (document not focused,
   permission denied, insecure origin) must reach the caller instead of
   being degraded to a text-only write."
  [cause]
  (let [message (str (when (some? cause) (unchecked-get cause "message")))]
    (str/includes? message "not supported")))

(defn- create-multi-clipboard-item
  [items]
  (js/ClipboardItem.
   (reduce-kv
    (fn [acc mime payload]
      (let [blob (js/Blob. #js [payload] #js {:type mime})]
        (unchecked-set acc mime (js/Promise.resolve blob))
        acc))
    #js {} items)))

(defn- to-clipboard-text
  [clipboard items]
  (if-let [text (or (get items "text/plain")
                    (first (vals items)))]
    (if (unchecked-get clipboard "writeText")
      (.writeText ^js clipboard text)
      (unavailable-error))
    (js/Promise.resolve)))

(defn to-clipboard-multi
  "Write multiple MIME representations as a single ClipboardItem.
   `items` is a map of mime-type (string) -> string payload.

   MIME types the browser refuses to write (per `ClipboardItem.supports`)
   are dropped, and a `clipboard.write` rejected for an unsupported MIME
   type falls back to `writeText` with the `text/plain` payload (or the
   first available payload), which is also the path taken when
   `clipboard.write` or `ClipboardItem` are missing altogether. Any other
   rejection is propagated. If no path is reachable (e.g. insecure origin),
   returns a rejected Promise mirroring `to-clipboard`'s contract instead
   of throwing synchronously."
  [items]
  (let [clipboard (get-clipboard)
        writable  (when clipboard
                    (into {} (filter (comp writable-mime? key)) items))]
    (cond
      (and clipboard (seq writable)
           (unchecked-get clipboard "write")
           (some? (get-clipboard-item-ctor)))
      (-> (.write ^js clipboard #js [(create-multi-clipboard-item writable)])
          (.catch (fn [cause]
                    (if (unsupported-mime-error? cause)
                      (to-clipboard-text clipboard items)
                      (js/Promise.reject cause)))))

      (some? clipboard)
      (to-clipboard-text clipboard items)

      :else
      (unavailable-error))))
