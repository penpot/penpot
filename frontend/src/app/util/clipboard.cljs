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
  "Get clipboard stream from syntetic clipboard event"
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

(defn clipboard-write-text-supported?
  "True when `clipboard` is a non-nil object exposing the async writeText API.
  In non-secure browsing contexts (e.g. self-hosted Penpot served over plain
  HTTP from a LAN IP) `navigator.clipboard` is `undefined`, in which case this
  returns false and callers should fall back to `legacy-write-text!`."
  [clipboard]
  (boolean (and (some? clipboard)
                (some? (unchecked-get clipboard "writeText")))))

(defn clipboard-write-supported?
  "True when `clipboard` is a non-nil object exposing the rich `write` API
  used for `ClipboardItem` payloads. Browsers gate this behind a secure
  context; there is no document.execCommand fallback for arbitrary blobs."
  [clipboard]
  (boolean (and (some? clipboard)
                (some? (unchecked-get clipboard "write")))))

(defn- legacy-write-text!
  "Insecure-context fallback that copies `text` via document.execCommand.
  Returns true on success, false when the browser refused the copy command
  (which can happen in iframes without `allow=\"clipboard-write\"`).
  Mounts the textarea off-screen via fixed-position with zero opacity so the
  page does not scroll while the textarea is briefly focused."
  [text]
  (let [doc      js/document
        textarea (.createElement doc "textarea")]
    (set! (.-value textarea) text)
    (set! (.-readOnly textarea) true)
    (set! (.. textarea -style -position) "fixed")
    (set! (.. textarea -style -top) "0")
    (set! (.. textarea -style -left) "0")
    (set! (.. textarea -style -opacity) "0")
    (set! (.. textarea -style -pointerEvents) "none")
    (.appendChild (.-body doc) textarea)
    (try
      (.focus textarea)
      (.select textarea)
      (boolean (.execCommand doc "copy"))
      (catch :default _ false)
      (finally
        (.removeChild (.-body doc) textarea)))))

;; FIXME: rename to `write-text`
(defn to-clipboard
  "Write `data` (string) to the system clipboard. Uses the async Clipboard
  API in secure contexts; falls back to `document.execCommand('copy')` when
  `navigator.clipboard` is unavailable (e.g. self-hosted Penpot served over
  plain HTTP from a LAN IP, which is not a secure context).
  Always returns a Promise so callers can handle write failures uniformly."
  [data]
  (assert (string? data) "`data` should be string")
  (let [clipboard (unchecked-get js/navigator "clipboard")]
    (cond
      (clipboard-write-text-supported? clipboard)
      (.writeText ^js clipboard data)

      (legacy-write-text! data)
      (js/Promise.resolve)

      :else
      (js/Promise.reject
       (js/Error.
        (str "Clipboard write failed: navigator.clipboard.writeText is "
             "unavailable and document.execCommand('copy') was rejected by "
             "the browser. This typically requires a secure context (HTTPS "
             "or localhost)."))))))

(defn- create-clipboard-item
  [mimetype promise]
  (js/ClipboardItem.
   (js-obj mimetype promise)))

;; FIXME: this API is very confuse
(defn to-clipboard-promise
  "Write a single ClipboardItem holding a Promise that resolves to the
  payload for `mimetype`. Returns a Promise that rejects with a clear
  error when the rich Clipboard API is unavailable (no legacy fallback
  exists for arbitrary blob payloads)."
  [mimetype promise]
  (let [clipboard (unchecked-get js/navigator "clipboard")]
    (if (clipboard-write-supported? clipboard)
      (let [data (create-clipboard-item mimetype promise)]
        (.write ^js clipboard #js [data]))
      (js/Promise.reject
       (js/Error.
        (str "Clipboard rich-write is unavailable: navigator.clipboard.write "
             "requires a secure context (HTTPS or localhost)."))))))

(defn to-clipboard-multi
  "Write multiple MIME representations as a single ClipboardItem.
  `items` is a map of mime-type (string) -> string payload.
  Falls back to writing the `text/plain` payload (or, if absent, the first
  value) via `to-clipboard` when the async Clipboard API is unavailable."
  [items]
  (let [clipboard (unchecked-get js/navigator "clipboard")]
    (if (clipboard-write-supported? clipboard)
      (let [obj (reduce-kv
                 (fn [acc mime payload]
                   (let [blob (js/Blob. #js [payload] #js {:type mime})]
                     (unchecked-set acc mime (js/Promise.resolve blob))
                     acc))
                 #js {} items)
            item (js/ClipboardItem. obj)]
        (.write ^js clipboard #js [item]))
      (when-let [text (or (get items "text/plain")
                          (first (vals items)))]
        (to-clipboard text)))))
