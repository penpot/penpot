;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.thumbnails
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [promesa.core :as p]
   [app.main.fonts :as fonts]
   [app.main.exports :as exports]
   [app.worker.impl :as impl]
   [app.util.http :as http]
   ["react-dom/server" :as rds]))

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

(defn- request-page
  [file-id page-id]
  (let [uri "/api/rpc/query/page"]
    (p/create
     (fn [resolve reject]
       (->> (http/send! {:uri uri
                         :query {:file-id file-id :id page-id}
                         :method :get})
            (rx/map http/conditional-decode-transit)
            (rx/mapcat handle-response)
            (rx/subs (fn [body]
                       (resolve body))
                     (fn [error]
                       (reject error))))))))

(defonce cache (atom {}))

(defn render-page
  [data ckey]
  (let [prev (get @cache ckey)]
    (if (= (:data prev) data)
      (:result prev)
      (let [elem   (mf/element exports/page-svg #js {:data data :width "290" :height "150"})
            result (rds/renderToStaticMarkup elem)]
        (swap! cache assoc ckey {:data data :result result})
        result))))

(defmethod impl/handler :thumbnails/generate
  [{:keys [file-id page-id] :as message}]
  (p/then
   (request-page file-id page-id)
   (fn [data]
     {:svg (render-page data #{file-id page-id})
      :fonts @fonts/loaded})))
