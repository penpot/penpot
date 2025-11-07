;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.clipboard
  (:require
   ["./clipboard.js" :as clipboard]
   [app.common.transit :as t]
   [beicon.v2.core :as rx]))

(def image-types
  ["image/webp"
   "image/png"
   "image/jpeg"
   "image/svg+xml"])

(def clipboard-settings #js {:decodeTransit t/decode-str})

(defn from-clipboard []
  (->> (rx/from (clipboard/fromClipboard clipboard-settings))
       (rx/mapcat #(rx/from %))))

(defn from-data-transfer [data-transfer]
  (->> (rx/from (clipboard/fromDataTransfer data-transfer clipboard-settings))
       (rx/mapcat #(rx/from %))))

(defn from-clipboard-data [clipboard-data]
  (from-data-transfer clipboard-data))

(defn from-clipboard-event [event]
  (from-clipboard-data (.-clipboardData event)))

(defn from-synthetic-clipboard-event [event]
  (let [target (.-target ^js event)]
    (when (and (not (.-isContentEditable ^js target)) ;; ignore when pasting into
               (not= (.-tagName ^js target) "INPUT")) ;; an editable control
      (from-clipboard-event (. ^js event getBrowserEvent)))))

(defn from-drop-event [event]
  (from-data-transfer (.-dataTransfer event)))

(defn to-clipboard
  [data]
  (assert (string? data) "`data` should be string")
  (let [clipboard (unchecked-get js/navigator "clipboard")]
    (.writeText ^js clipboard data)))

(defn- create-clipboard-item
  [mimetype promise]
  (js/ClipboardItem.
   (js-obj mimetype promise)))

(defn to-clipboard-promise
  [mimetype promise]
  (let [clipboard (unchecked-get js/navigator "clipboard")
        data (create-clipboard-item mimetype promise)]
    (.write ^js clipboard #js [data])))
