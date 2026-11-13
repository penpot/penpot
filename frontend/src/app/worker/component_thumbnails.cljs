;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.component-thumbnails
  "Renders component thumbnails on the worker WASM module from the shapes
  of their mains, which the main thread sends. Sends each render back to
  be shown, and uploads the ones asked for."
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.thumbnails :as thc]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.render-wasm.api :as wasm.api]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [app.worker.impl :as impl]
   [app.worker.wasm :as wwasm]
   [beicon.v2.core :as rx]))

;; Bounds for a thumbnail's longest side, in pixels. Full-size renders of big
;; components hold the worker for seconds, delaying its other work.
(def ^:private ^:const min-target-size 200)
(def ^:private ^:const max-target-size 512)

(def ^:private ^:const canvas-size 100)

(defn- thumbnail-scale
  [longest-side]
  (if (pos? longest-side)
    (-> 1
        (mth/max (/ min-target-size longest-side))
        (mth/min (/ max-target-size longest-side)))
    1))

(defn- render-component-root
  [objects root-id]
  (let [{ext-w :width ext-h :height} (wasm.api/get-shape-extrect root-id)
        {sel-w :width sel-h :height} (dm/get-in objects [root-id :selrect])
        longest-side (mth/max (or ext-w sel-w) (or ext-h sel-h))
        scale        (thumbnail-scale longest-side)
        bytes        (wasm.api/render-shape-pixels root-id scale :png)]
    (when (and (some? bytes) (pos? (.-length ^js bytes)))
      (wapi/create-blob bytes "image/png"))))

(defn- render-batch
  "Renders each of `requests` from the shapes of its main, in `:objects`,
  to a PNG blob in one job on the worker WASM module. Emits each request
  that rendered, with its `:blob`. Never fails: an error would end the
  stream of requests, and with it every later render, so it is logged and
  the batch ends."
  [requests]
  (let [objects (into {} (mapcat :objects) requests)]
    (->> (rx/from @wwasm/module)
         (rx/mapcat
          (fn [_]
            (rx/create
             (fn [subs]
               (let [canvas (js/OffscreenCanvas. canvas-size canvas-size)
                     vbox   {:x 0 :y 0 :width canvas-size :height canvas-size}]
                 (if (wasm.api/init-canvas-context canvas)
                   (try
                     (reset! wasm/disable-request-render? true)
                     ;; `:on-render` runs once the images and fonts are in.
                     (wasm.api/initialize-viewport
                      objects 1 vbox
                      :force-sync true
                      :on-render
                      (fn []
                        (try
                          (doseq [{:keys [root-id] :as request} requests]
                            (when-let [blob (render-component-root objects root-id)]
                              (rx/push! subs (assoc request :blob blob))))
                          (rx/end! subs)
                          (catch :default cause
                            (rx/error! subs cause))
                          (finally
                            (wasm.api/clear-canvas)))))
                     (catch :default cause
                       (wasm.api/clear-canvas)
                       (rx/error! subs cause)))
                   (rx/error! subs (ex-info "unable to init wasm canvas" {:roots (count requests)})))
                 nil)))))
         (rx/catch (fn [cause]
                     (log/error :hint "error rendering component thumbnails"
                                :roots (count requests)
                                :cause cause)
                     (rx/empty))))))

(defn- upload-thumbnail
  [file-id object-id blob]
  (let [params {:file-id file-id :object-id object-id :tag "component"}]
    (->> (http/send! {:method :post
                      :uri (u/join cf/public-uri "api/main/methods/create-file-object-thumbnail")
                      :credentials "include"
                      :query params
                      :body (http/form-data (assoc params :media blob))})
         (rx/subs! (fn [response]
                     (when-not (http/success? response)
                       (log/wrn :hint "unable to upload component thumbnail"
                                :object-id object-id :status (:status response))))
                   (fn [cause]
                     (log/wrn :hint "unable to upload component thumbnail"
                              :object-id object-id :cause cause))))))

;; The stream the main thread reads the renders from; a new
;; `:thumbnails/watch-components` ends the previous one.
(defonce ^:private output* (atom nil))

(defn- add-render
  "Sends `render` to the main thread to be shown, and uploads it when it
  asks for it."
  [{:keys [file-id page-id root-id blob persist?]}]
  (let [object-id (thc/fmt-object-id file-id page-id root-id "component")]
    (some-> @output* (rx/push! {:object-id object-id :blob blob}))
    (when persist?
      (upload-thumbnail file-id object-id blob))))

;; The requests from the main thread, rendered in batches, one at a time.
;; A batch takes the requests up to a 300 ms pause.
(defonce ^:private requests
  (let [subject (rx/subject)]
    (->> subject
         (rx/buffer-until (rx/debounce 300 subject))
         ;; TODO: keep one request per main, so a main asked for twice in a
         ;; batch renders and uploads once.
         (rx/mapcat #(wwasm/submit (partial render-batch %)))
         (rx/subs! add-render))
    subject))

(defmethod impl/handler :thumbnails/render-component
  [message _]
  (rx/push! requests (select-keys message [:file-id :page-id :root-id :objects :persist?]))
  nil)

(defmethod impl/handler :thumbnails/watch-components
  [_ _]
  (let [output (rx/subject)]
    (some-> @output* rx/end!)
    (reset! output* output)
    output))
