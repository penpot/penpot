;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.worker.thumbnails
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [promesa.core :as p]
   [app.main.fonts :as fonts]
   [app.main.exports :as exports]
   [app.worker.impl :as impl]
   [app.util.http-api :as http]
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
            (rx/mapcat handle-response)
            (rx/subs (fn [body]
                       (resolve body))
                     (fn [error]
                       (reject error))))))))

(defmethod impl/handler :thumbnails/generate
  [{:keys [file-id page-id] :as message}]
  (p/then
   (request-page file-id page-id)
   (fn [data]
     (let [elem (mf/element exports/page-svg #js {:data data
                                                  :width "290"
                                                  :height "150"})]
       {:svg (rds/renderToStaticMarkup elem)
        :fonts @fonts/loaded}))))
