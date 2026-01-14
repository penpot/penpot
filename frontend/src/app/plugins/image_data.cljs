;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.image-data
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(defn create-image-data
  [{:keys [name width height mtype id keep-aspect-ratio] :as entry}]
  (obj/reify {:name "ImageData"}
    :name            {:get (constantly name)}
    :width           {:get (constantly width)}
    :height          {:get (constantly height)}
    :mtype           {:get (constantly mtype)}
    :id              {:get #(when id (dm/str id))}
    :keepAspectRatio {:get (constantly keep-aspect-ratio)}

    :data
    (fn []
      (let [url (cf/resolve-file-media entry)]
        (js/Promise.
         (fn [resolve reject]
           (->> (http/send!
                 {:method :get
                  :uri url
                  :response-type :blob})
                (rx/map :body)
                (rx/mapcat #(.arrayBuffer %))
                (rx/map #(js/Uint8Array. %))
                (rx/subs! resolve reject))))))))
