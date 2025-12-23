;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.sse
  (:require
   ["eventsource-parser/stream" :as sse]
   [beicon.v2.core :as rx]))

(defn create-stream
  [^js/ReadableStream stream]
  (.. stream
      (pipeThrough (js/TextDecoderStream.))
      (pipeThrough (sse/EventSourceParserStream.))))

(defn read-stream
  [^js/ReadableStream stream decode-fn]
  (letfn [(read-items [^js reader]
            (->> (rx/from (.read reader))
                 (rx/mapcat (fn [result]
                              (if (.-done result)
                                (rx/empty)
                                (rx/concat
                                 (rx/of (.-value result))
                                 (read-items reader)))))))]
    (->> (read-items (.getReader stream))
         (rx/mapcat (fn [^js event]
                      (let [type (.-event event)
                            data (.-data event)
                            data (decode-fn data)]
                        (if (= "error" type)
                          (rx/throw (ex-info "stream exception" data))
                          (rx/of #js {:type type :data data}))))))))

(defn get-type
  [event]
  (unchecked-get event "type"))

(defn get-payload
  [event]
  (unchecked-get event "data"))

(defn end-of-stream?
  [event]
  (= "end" (get-type event)))

(defn progress?
  [event]
  (= "progress" (get-type event)))

(defn event?
  [event]
  (= "event" (get-type event)))



