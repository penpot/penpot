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
  #js {:decodeTransit t/decode-str})

(defn- from-data-transfer
  "Get clipboard stream from DataTransfer instance"
  [data-transfer]
  (->> (rx/from (impl/fromDataTransfer data-transfer default-options))
       (rx/mapcat #(rx/from %))))

(defn from-navigator
  []
  (->> (rx/from (impl/fromNavigator default-options))
       (rx/mapcat #(rx/from %))))

(defn from-clipboard-event
  "Get clipboard stream from clipboard event"
  [event]
  (let [cdata (.-clipboardData ^js event)]
    (from-data-transfer cdata)))

(defn from-synthetic-clipboard-event
  "Get clipboard stream from syntetic clipboard event"
  [event]
  (let [target
        (dom/get-target event)

        content-editable?
        (dom/is-content-editable? target)

        is-input?
        (= (dom/get-tag-name target) "INPUT")]

    ;; ignore when pasting into an editable control
    (when-not (or content-editable? is-input?)
      (-> event
          (dom/event->browser-event)
          (from-clipboard-event)))))

(defn from-drop-event
  "Get clipboard stream from drop event"
  [event]
  (from-data-transfer (.-dataTransfer ^js event)))

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
