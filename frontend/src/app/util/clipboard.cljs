;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.clipboard
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(def max-parseable-size
  (* 16 1024 1024))

(def image-types
  ["image/webp"
   "image/png"
   "image/jpeg"
   "image/svg+xml"])

(def allowed-types
  (d/ordered-set
   "image/webp",
   "image/png",
   "image/jpeg",
   "image/svg+xml",
   "application/transit+json",
   "text/html",
   "text/plain"))

(def exclusive-types
  (d/ordered-set
   "application/transit+json",
   "text/html",
   "text/plain"))

(def clipboard-settings
  #js {:decodeTransit t/decode-str})

(defn- parse-pain-text
  [text]
  (or (when (ex/ignoring (t/decode-str text))
        (new js/Blob #js [text] #js {:type "application/transit+json"}))
      (when (re-seq #"^<svg[\s>]" text)
        (new js/Blob #js [text] #js {:type "image/svg+xml"}))
      (new js/Blob #js [text] #js {:type "text/plain"})))

(defn from-dom-api
  []
  (let [api (.-clipboard js/navigator)]
    (->> (rx/from (.read ^js api))
         (rx/mapcat (comp rx/from obj/into-array))
         (rx/mapcat (fn [item]
                      (let [allowed-types'
                            (->> (seq (.-types item))
                                 (filter (fn [type] (contains? allowed-types type)))
                                 (sort-by (fn [type] (d/index-of allowed-types type)))
                                 (into (d/ordered-set)))

                            main-type
                            (first allowed-types')]

                        (cond->> (rx/from (.getType ^js item main-type))
                          (and (= (count allowed-types') 1)
                               (= "text/plain" main-type))
                          (rx/mapcat (fn [blob]
                                       (if (>= max-parseable-size (.-size ^js blob))
                                         (->> (rx/from (.text ^js blob))
                                              (rx/map parse-pain-text))
                                         (rx/of blob)))))))))))

(defn- from-data-transfer
  "Get clipboard stream from DataTransfer instance"
  [data-transfer]
  (let [sorted-items
        (->> (seq (.-items ^js data-transfer))
             (filter (fn [item]
                       (contains? allowed-types (.-type ^js item))))
             (sort-by (fn [item] (d/index-of allowed-types (.-type item)))))]
    (->> (rx/from sorted-items)
         (rx/mapcat (fn [item]
                      (let [kind (.-kind ^js item)
                            type (.-type ^js item)]
                        (cond
                          (= kind "file")
                          (rx/of (.getAsFile ^js item))

                          (= kind "string")
                          (->> (rx/create (fn [subs]
                                            (.getAsString ^js item
                                                          (fn [text]
                                                            (rx/push! subs (d/vec2 type text))
                                                            (rx/end! subs)))))
                               (rx/map (fn [[type text]]
                                         (if (= type "text/plain")
                                           (parse-pain-text text)
                                           (new js/Blob #js [text] #js {:type type})))))
                          :else
                          (rx/empty)))))
         (rx/filter some?)
         (rx/reduce (fn [filtered item]
                      (js/console.log "AAA" item)
                      (let [type (.-type ^js item)]
                        (if (and (contains? exclusive-types type)
                                 (some (fn [item] (contains? exclusive-types type)) filtered))
                          filtered
                          (conj filtered item))))
                    (d/ordered-set))
         (rx/mapcat (comp rx/from seq)))))

(defn from-event
  "Get clipboard stream from event"
  [event]
  (let [cdata (.-clipboardData ^js event)]
    (from-data-transfer cdata)))

(defn from-synthetic-event
  "Get clipboard stream from syntetic event"
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
          (from-event)))))

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
