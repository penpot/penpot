;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.render-viewer-wasm
  "WASM offscreen rendering for the shared viewer (snapshot + fixed-scroll)."
  (:require
   [app.common.data.macros :as dm]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.wasm :as wasm]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [goog.events :as events]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; The WASM module is a single global instance; serialize offscreen work.
(defonce ^:private wasm-render-queue (atom (p/resolved nil)))

(defn- enqueue-wasm-render!
  [task]
  (let [next-p (-> @wasm-render-queue
                   (p/handle (fn [_ _] (task))))]
    (reset! wasm-render-queue (p/handle next-p (fn [_ _] nil)))
    next-p))

(defonce ^:private viewer-snapshot
  (atom {:os-canvas nil
         :page-key  nil
         :canvas-w  0
         :canvas-h  0}))

(defn- reset-viewer-snapshot! []
  (reset! viewer-snapshot
          {:os-canvas nil
           :page-key nil
           :canvas-w 0
           :canvas-h 0}))

(defn- draw-bitmap!
  [canvas os-canvas object-id vis-w vis-h finish]
  (ts/raf
   (fn []
     (let [ctx2d (.getContext canvas "2d")]
       (.clearRect ctx2d 0 0 vis-w vis-h)
       ;; Draw directly from OffscreenCanvas so it can be reused across passes.
       (.drawImage ctx2d os-canvas 0 0 vis-w vis-h)
       (dom/set-attribute! canvas "id" (str "screenshot-" object-id))
       (finish)))))

(defn- viewer-disable-wasm-ui-overlay!
  "Workspace WASM UI (rulers + rounded viewport frame) is composited in
  `present_frame`; the viewer must not show that chrome."
  []
  (wasm.api/set-rulers-frame-visible! false)
  (wasm.api/set-rulers-visible! false))

(defn- viewer-apply-layer-mask!
  [include-ids clear-fills-ids]
  (wasm.api/clear-render-include-filter!)
  (when (seq include-ids)
    (wasm.api/set-render-include-filter! include-ids))
  (doseq [id clear-fills-ids]
    (wasm.api/use-shape id)
    (wasm.api/clear-shape-fills!)))

(defn- viewer-restore-layer-mask!
  [page-objects clear-fills-ids]
  (wasm.api/clear-render-include-filter!)
  (doseq [id clear-fills-ids]
    (wasm.api/use-shape id)
    (wasm.api/set-shape-fills id (get-in page-objects [id :fills] []) false)))

(defn- viewer-do-render!
  [page-objects canvas os-canvas object-id vis-w vis-h scale size
   include-ids clear-fills-ids finish]
  (viewer-disable-wasm-ui-overlay!)
  (viewer-apply-layer-mask! include-ids clear-fills-ids)
  (wasm.api/set-viewer-viewport! scale size)
  (wasm.api/render-sync-shape object-id)
  (viewer-restore-layer-mask! page-objects clear-fills-ids)
  (draw-bitmap! canvas os-canvas object-id vis-w vis-h finish))

(defn- render-to-canvas*
  [objects canvas bounds scale object-id on-render]
  (p/create
   (fn [resolve _reject]
     (let [width        (.-width canvas)
           height       (.-height canvas)
           prev-disable @wasm/disable-request-render?
           finish       (fn []
                          (reset! wasm/disable-request-render? prev-disable)
                          (when (fn? on-render) (on-render))
                          (resolve nil))]
       (try
         (reset! wasm/disable-request-render? true)
         (let [os-canvas (js/OffscreenCanvas. width height)]
           (if (wasm.api/init-canvas-context os-canvas)
             (wasm.api/initialize-viewport
              objects scale bounds
              :background-opacity 0
              :force-sync true
              :on-render
              (fn []
                (viewer-disable-wasm-ui-overlay!)
                (wasm.api/render-sync-shape object-id)
                (draw-bitmap! canvas os-canvas object-id width height
                              (fn []
                                (wasm.api/clear-canvas {:lose-browser-context? false})
                                (reset-viewer-snapshot!)
                                (finish)))))
             (finish)))
         (catch :default e
           (js/console.error "Error initializing canvas context:" e)
           (finish)))))))

(defn render-to-canvas
  "One-shot WASM render into `canvas` (exports, thumbnails). Serialized globally."
  [objects canvas bounds scale object-id on-render]
  (enqueue-wasm-render!
   (fn []
     (render-to-canvas* objects canvas bounds scale object-id on-render))))

(defn- render-viewer-frame*
  [page-key page-objects canvas size scale object-id on-render
   {:keys [include-ids clear-fills-ids] :or {clear-fills-ids #{}}}]
  (p/create
   (fn [resolve _reject]
     (let [prev-disable @wasm/disable-request-render?
           finish       (fn []
                          (reset! wasm/disable-request-render? prev-disable)
                          (when (fn? on-render) (on-render))
                          (resolve nil))
           vis-w        (.-width canvas)
           vis-h        (.-height canvas)
           snap         @viewer-snapshot
           same-page?   (and (some? page-key) (identical? page-key (:page-key snap)))
           same-size?   (and (= vis-w (:canvas-w snap)) (= vis-h (:canvas-h snap)))
           os           (:os-canvas snap)
           do-render!   (fn [os-canvas]
                          (viewer-do-render! page-objects canvas os-canvas object-id
                                             vis-w vis-h scale size include-ids
                                             clear-fills-ids finish))]

       (reset! wasm/disable-request-render? true)

       (try
         (if (and same-page? (wasm.api/initialized?) os)
           (do
             (when-not same-size?
               (wasm.api/resize-offscreen-canvas! os vis-w vis-h)
               (swap! viewer-snapshot assoc :canvas-w vis-w :canvas-h vis-h))
             (do-render! os))
           (let [os-canvas (js/OffscreenCanvas. vis-w vis-h)]
             (when (wasm.api/initialized?)
               (wasm.api/clear-canvas {:lose-browser-context? false}))
             (if (wasm.api/init-canvas-context os-canvas)
               (do
                 (reset! viewer-snapshot
                         {:os-canvas os-canvas
                          :page-key  page-key
                          :canvas-w  vis-w
                          :canvas-h  vis-h})
                 (wasm.api/initialize-viewport
                  page-objects scale size
                  :background-opacity 0
                  :force-sync true
                  :on-render #(do-render! os-canvas)))
               (finish))))
         (catch :default e
           (js/console.error "viewer-snapshot: render error" e)
           (finish)))))))

(defn- use-fixed-scroll-sync!
  [enabled? layer-ref]
  (mf/use-layout-effect
   (mf/deps enabled?)
   (fn []
     (when enabled?
       (let [section (dom/get-element "viewer-section")
             sync!
             (fn []
               (when-let [layer (mf/ref-val layer-ref)]
                 (dom/set-style! layer "transform"
                                 (dm/str "translate("
                                         (or (dom/get-h-scroll-pos section) 0) "px, "
                                         (or (dom/get-scroll-pos section) 0) "px)"))))]
         (when section
           (sync!)
           (let [key (events/listen section "scroll" (fn [_] (sync!)))]
             #(events/unlistenByKey key))))))))

(defn- use-viewer-wasm-layers!
  [page-id page-objects size scale frame-id not-fixed-ref fixed-ref
   not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids]
  (mf/use-layout-effect
   (mf/deps page-id page-objects size scale frame-id
            not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids)
   (fn []
     (when (get page-objects frame-id)
       (->> @wasm.api/module
            (p/fmap
             (fn [ready?]
               (when ready?
                 (let [not-fixed-canvas (mf/ref-val not-fixed-ref)
                       fixed-canvas     (mf/ref-val fixed-ref)
                       passes
                       (cond-> []
                         not-fixed-canvas
                         (conj {:canvas not-fixed-canvas
                                :opts   (cond-> {}
                                          (seq not-fixed-include-ids)
                                          (assoc :include-ids not-fixed-include-ids))})

                         (and fixed-canvas (seq fixed-include-ids))
                         (conj {:canvas fixed-canvas
                                :opts   (cond-> {:include-ids fixed-include-ids}
                                          (seq fixed-clear-fills-ids)
                                          (assoc :clear-fills-ids fixed-clear-fills-ids))}))]
                   (when (seq passes)
                     (enqueue-wasm-render!
                      (fn []
                        (reduce (fn [chain {:keys [canvas opts]}]
                                  (p/then chain
                                          #(render-viewer-frame* page-id page-objects
                                                                 canvas size scale frame-id
                                                                 nil opts)))
                                (p/resolved nil)
                                passes)))))))))))))

(defn use-viewer-wasm-viewport!
  "WASM render passes and fixed-scroll DOM sync for the viewer viewport."
  [page-id page-objects size scale frame-id
   not-fixed-ref fixed-ref fixed-scroll-layer-ref
   not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids]
  (use-fixed-scroll-sync! (some? fixed-scroll-layer-ref) fixed-scroll-layer-ref)
  (use-viewer-wasm-layers! page-id page-objects size scale frame-id
                           not-fixed-ref fixed-ref
                           not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids))
