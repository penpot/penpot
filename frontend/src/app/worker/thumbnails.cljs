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
   [beicon.core :as rx]
   [debug :refer [debug?]]
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

(defn- not-found?
  [{:keys [type]}]
  (= :not-found type))

(defn- body-too-large?
  [{:keys [type code]}]
  (and (= :validation type)
       (= :request-body-too-large code)))

(defn- request-data-for-thumbnail
  [file-id revn features]
  (let [path    "api/rpc/command/get-file-data-for-thumbnail"
        params   {:file-id file-id
                  :revn revn
                  :strip-frames-with-thumbnails true
                  :features features}
        request  {:method :get
                  :uri (u/join @cf/public-uri path)
                  :credentials "include"
                  :query params}]
    (->> (http/send! request)
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defn- request-thumbnail
  [file-id revn]
  (let [path    "api/rpc/command/get-file-thumbnail"
        params  {:file-id file-id
                 :revn revn}
        request {:method :get
                 :uri (u/join @cf/public-uri path)
                 :credentials "include"
                 :query params}]
    (->> (http/send! request)
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response))))

(defn- render-thumbnail
  [{:keys [page file-id revn] :as params}]
  (let [objects (:objects page)
        frame   (some->> page :thumbnail-frame-id (get objects))
        element (if frame
                  (mf/element render/frame-svg #js {:objects objects :frame frame :show-thumbnails? true})
                  (mf/element render/page-svg #js {:data page :thumbnails? true}))
        data    (rds/renderToStaticMarkup element)]
    {:data data
     :fonts (into @fonts/loaded (map first) @fonts/loading)
     :file-id file-id
     :revn revn}))

(defn- persist-thumbnail
  [{:keys [file-id data revn fonts]}]
  (let [path    "api/rpc/command/upsert-file-thumbnail"
        params  {:file-id file-id
                 :revn revn
                 :props {:fonts fonts}
                 :data data}
        request {:method :post
                 :uri (u/join @cf/public-uri path)
                 :credentials "include"
                 :body (http/transit-data params)}]

    (->> (http/send! request)
         (rx/map http/conditional-decode-transit)
         (rx/mapcat handle-response)
         (rx/catch body-too-large? (constantly (rx/of nil)))
         (rx/map (constantly params)))))

(defmethod impl/handler :thumbnails/generate-for-file
  [{:keys [file-id revn features] :as message} _]
  (letfn [(on-result [{:keys [data props]}]
            {:data data
             :fonts (:fonts props)})

          (on-cache-miss [_]
            (log/debug :hint "request-thumbnail" :file-id file-id :revn revn :cache "miss")
            (->> (request-data-for-thumbnail file-id revn features)
                 (rx/map render-thumbnail)
                 (rx/mapcat persist-thumbnail)))]

    (if (debug? :disable-thumbnail-cache)
      (->> (request-data-for-thumbnail file-id revn features)
           (rx/map render-thumbnail))
      (->> (request-thumbnail file-id revn)
           (rx/tap (fn [_]
                     (log/debug :hint "request-thumbnail" :file-id file-id :revn revn :cache "hit")))
           (rx/catch not-found? on-cache-miss)
           (rx/map on-result)))))

(defmethod impl/handler :thumbnails/render-offscreen-canvas
  [_ ibpm]
  (let [canvas (js/OffscreenCanvas. (.-width ^js ibpm) (.-height ^js ibpm))
        ctx    (.getContext ^js canvas "bitmaprenderer")]

    (.transferFromImageBitmap ^js ctx ibpm)

    (->> (.convertToBlob ^js canvas #js {:type "image/png"})
         (p/fmap (fn [blob]
                   (js/console.log "[worker]: generated thumbnail")
                   {:result (.createObjectURL js/URL blob)}))
         (p/fnly (fn [_]
                   (.close ^js ibpm))))))

