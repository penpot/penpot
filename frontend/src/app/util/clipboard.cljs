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

;; FIXME: rename to `write-text`
(defn to-clipboard
  [data]
  (assert (string? data) "`data` should be string")
  (let [clipboard (unchecked-get js/navigator "clipboard")]
    (.writeText ^js clipboard data)))

(defn- create-clipboard-item
  [mimetype promise]
  (js/ClipboardItem.
   (js-obj mimetype promise)))

;; FIXME: this API is very confuse
(defn to-clipboard-promise
  [mimetype promise]
  (let [clipboard (unchecked-get js/navigator "clipboard")
        data (create-clipboard-item mimetype promise)]
    (.write ^js clipboard #js [data])))
