;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.clipboard
  (:require
   ["./clipboard.js" :as impl]
   [app.common.transit :as t]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]))

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

(defn to-clipboard-multi
  "Write multiple MIME representations as a single ClipboardItem.
   `items` is a map of mime-type (string) -> string payload.

   Falls back to `writeText` with the `text/plain` payload (or the
   first available payload) when the asynchronous `clipboard.write`
   API is unavailable. If neither path is reachable (e.g. insecure
   origin), returns a rejected Promise mirroring `to-clipboard`'s
   contract instead of throwing synchronously."
  [items]
  (let [clipboard (get-clipboard)]
    (cond
      (and clipboard (unchecked-get clipboard "write"))
      (let [obj  (reduce-kv
                  (fn [acc mime payload]
                    (let [blob (js/Blob. #js [payload] #js {:type mime})]
                      (unchecked-set acc mime (js/Promise.resolve blob))
                      acc))
                  #js {} items)
            item (js/ClipboardItem. obj)]
        (.write ^js clipboard #js [item]))

      (and clipboard (unchecked-get clipboard "writeText"))
      (when-let [text (or (get items "text/plain")
                          (first (vals items)))]
        (.writeText ^js clipboard text))

      :else
      (unavailable-error))))
