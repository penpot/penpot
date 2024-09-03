;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.thumbnails
  (:require
   ["react-dom/server" :as rds]
   [app.common.logging :as log]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.render :as render]
   [app.util.http :as http]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(log/set-level! :trace)

(defn- handle-response
  [{:keys [body status] :as response}]
  (cond
    (http/success? response)
    (rx/of (:body response))

    (= status 413)
    (rx/throw {:type :validation
               :code :request-body-too-large
               :hint "request body too large"})

    (and (http/client-error? response)
         (map? body))
    (rx/throw body)

    :else
    (rx/throw {:type :unexpected-error
               :code :unhandled-http-response
               :http-status status
               :http-body body})))

(defn- request-data-for-thumbnail
  [file-id revn features]
  (let [path    "api/rpc/command/get-file-data-for-thumbnail"
        params   {:file-id file-id
                  :revn revn
                  :strip-frames-with-thumbnails true
                  :features features}
        request  {:method :get
                  :uri (u/join cf/public-uri path)
                  :credentials "include"
                  :query params}]
    (->> (http/send! request)
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defn- render-thumbnail
  [{:keys [page file-id revn] :as params}]
  (try
    (binding [fonts/loaded-hints (l/atom #{})]
      (let [objects  (:objects page)
            frame    (some->> page :thumbnail-frame-id (get objects))
            background-color (:background page)
            element  (if frame
                       (mf/element render/frame-svg #js
                                                     {:objects objects
                                                      :frame frame
                                                      :use-thumbnails true
                                                      :background-color background-color
                                                      :aspect-ratio (/ 2 3)})

                       (mf/element render/page-svg #js
                                                    {:data page
                                                     :use-thumbnails true
                                                     :embed true
                                                     :aspect-ratio (/ 2 3)}))
            data     (rds/renderToStaticMarkup element)]
        {:data data
         :fonts @fonts/loaded-hints
         :file-id file-id
         :revn revn}))
    (catch :default cause
      (js/console.error "unexpected error on rendering thumbnail" cause)
      nil)))

(defmethod impl/handler :thumbnails/generate-for-file
  [{:keys [file-id revn features] :as message} _]
  (->> (request-data-for-thumbnail file-id revn features)
       (rx/map render-thumbnail)))
