;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.thumbnails
  (:require
   ["react-dom/server" :as rds]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.render :as render]
   [app.util.http :as http]
   [app.util.time :as ts]
   [app.util.webapi :as wapi]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [okulary.core :as l]
   [promesa.core :as p]
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
  (binding [fonts/loaded-hints (l/atom #{})]
    (let [objects  (:objects page)
          frame    (some->> page :thumbnail-frame-id (get objects))
          element  (if frame
                     (mf/element render/frame-svg #js {:objects objects :frame frame :show-thumbnails? true})
                     (mf/element render/page-svg #js {:data page :thumbnails? true :render-embed? true}))
          data     (rds/renderToStaticMarkup element)]

      {:data data
       :fonts @fonts/loaded-hints
       :file-id file-id
       :revn revn})))

(defmethod impl/handler :thumbnails/generate-for-file
  [{:keys [file-id revn features] :as message} _]
  (->> (request-data-for-thumbnail file-id revn features)
       (rx/map render-thumbnail)))

(defmethod impl/handler :thumbnails/render-offscreen-canvas
  [_ ibpm]
  (let [canvas (js/OffscreenCanvas. (.-width ^js ibpm) (.-height ^js ibpm))
        ctx    (.getContext ^js canvas "bitmaprenderer")
        tp     (ts/tpoint-ms)]

    (.transferFromImageBitmap ^js ctx ibpm)

    (->> (.convertToBlob ^js canvas #js {:type "image/png"})
         (p/fmap (fn [blob]
                   {:result (wapi/create-uri blob)}))
         (p/fnly (fn [_]
                   (log/debug :hint "generated thumbnail" :elapsed (dm/str (tp) "ms"))
                   (.close ^js ibpm))))))

