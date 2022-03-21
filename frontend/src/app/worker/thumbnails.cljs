;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.thumbnails
  (:require
   ["react-dom/server" :as rds]
   [app.common.uri :as u]
   [app.config :as cfg]
   [app.main.fonts :as fonts]
   [app.main.render :as render]
   [app.util.http :as http]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn- handle-response
  [response]
  (cond
    (http/success? response)
    (rx/of (:body response))

    (http/client-error? response)
    (rx/throw (:body response))

    :else
    (rx/throw {:type :unexpected
               :code (:error response)})))

(defn- request-thumbnail
  [file-id]
  (let [uri     (u/join (cfg/get-public-uri) "api/rpc/query/file-data-for-thumbnail")
        params  {:file-id file-id
                 :strip-frames-with-thumbnails true}
        request {:method :get
                 :uri uri
                 :credentials "include"
                 :query params}]
    (->> (http/send! request)
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defn render-frame
  [data]
  (let [elem (if-let [frame (:thumbnail-frame data)]
               (mf/element render/frame-svg #js {:objects (:objects data) :frame frame})
               (mf/element render/page-svg #js {:data data :width "290" :height "150" :thumbnails? true}))]
    (rds/renderToStaticMarkup elem)))

(defmethod impl/handler :thumbnails/generate
  [{:keys [file-id] :as message}]
  (->> (request-thumbnail file-id)
       (rx/map
        (fn [data]
          {:svg (render-frame data)
           :fonts @fonts/loaded}))))
