;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.thumbnails
  (:require
   ["react-dom/server" :as rds]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.logging :as log]
   [app.common.types.color :as cc]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.fonts :as fonts]
   [app.main.render :as render]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.wasm :as wasm]
   [app.util.http :as http]
   [app.util.modules :as mod]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
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
  [file-id revn strip-frames-with-thumbnails]
  (let [path    "api/main/methods/get-file-data-for-thumbnail"
        params   {:file-id file-id
                  :revn revn
                  :strip-frames-with-thumbnails strip-frames-with-thumbnails}
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
  [{:keys [file-id revn] :as message} _]
  (->> (request-data-for-thumbnail file-id revn true)
       (rx/map render-thumbnail)))

(def init-wasm
  (delay
    (let [uri (cf/resolve-static-asset "js/render_wasm.js")]
      (-> (mod/import (str uri))
          (p/then #(wasm.api/init-wasm-module %))
          (p/then #(set! wasm/internal-module %))))))

(mf/defc svg-wrapper
  [{:keys [data-uri background width height]}]
  [:svg {:version "1.1"
         :xmlns "http://www.w3.org/2000/svg"
         :xmlnsXlink "http://www.w3.org/1999/xlink"

         :style {:width "100%"
                 :height "100%"
                 :background background}
         :fill "none"
         :viewBox (dm/str "0 0 " width " " height)}
   [:image {:xlinkHref data-uri
            :width width
            :height height}]])

(defn blob->uri
  [blob]
  (.readAsDataURL (js/FileReaderSync.) blob))

(def thumbnail-aspect-ratio (/ 2 3))

(defn render-canvas-blob
  [canvas width height background-color]
  (-> (.convertToBlob canvas)
      (p/then
       (fn [blob]
         (rds/renderToStaticMarkup
          (mf/element
           svg-wrapper
           #js {:data-uri (blob->uri blob)
                :width width
                :height height
                :background background-color}))))))

(defn process-wasm-thumbnail
  [{:keys [id file-id revn width] :as message}]
  (->> (rx/from @init-wasm)
       (rx/mapcat #(request-data-for-thumbnail file-id revn false))
       (rx/mapcat
        (fn [{:keys [page] :as file}]
          (rx/create
           (fn [subs]
             (let [background-color (or (:background page) cc/canvas)
                   height (* width thumbnail-aspect-ratio)
                   canvas (js/OffscreenCanvas. width height)
                   init? (wasm.api/init-canvas-context canvas)]
               (if init?
                 (let [objects (:objects page)
                       frame (some->> page :thumbnail-frame-id (get objects))
                       vbox (if frame
                              (-> (gsb/get-object-bounds objects frame)
                                  (grc/fix-aspect-ratio thumbnail-aspect-ratio))
                              (render/calculate-dimensions objects thumbnail-aspect-ratio))
                       zoom (/ width (:width vbox))]

                   (wasm.api/initialize-viewport
                    objects zoom vbox background-color
                    (fn []
                      (if frame
                        (wasm.api/render-sync-shape (:id frame))
                        (wasm.api/render-sync))

                      (-> (render-canvas-blob canvas width height background-color)
                          (p/then #(rx/push! subs {:id id :data % :file-id file-id :revn revn}))
                          (p/catch #(rx/error! subs %))
                          (p/finally #(rx/end! subs))))))

                 (rx/end! subs))

               nil)))))))

(defonce thumbs-subject (rx/subject))

(defonce thumbs-stream
  (->> thumbs-subject
       (rx/mapcat process-wasm-thumbnail)
       (rx/share)))

(defmethod impl/handler :thumbnails/generate-for-file-wasm
  [message _]
  (rx/create
   (fn [subs]
     (let [id (uuid/next)
           sid
           (->> thumbs-stream
                (rx/filter #(= id (:id %)))
                (rx/subs!
                 #(do
                    (rx/push! subs %)
                    (rx/end! subs))))]
       (rx/push! thumbs-subject (assoc message :id id))

       #(rx/dispose! sid)))))
