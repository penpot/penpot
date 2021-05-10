;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.thumbnail-renderer
  (:require
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(mf/defc frame-thumbnail
  "Renders the canvas and image for a frame thumbnail and stores its value into the shape"
  [{:keys [shape on-thumbnail-data on-frame-not-found]}]

  (let [thumbnail-img (mf/use-ref nil)
        thumbnail-canvas (mf/use-ref nil)

        on-dom-rendered
        (mf/use-callback
         (mf/deps (:id shape))
         (fn [node]
           (when node
             (let [img-node (mf/ref-val thumbnail-img)]
               (timers/schedule-on-idle
                #(if-let [frame-node (dom/get-element (str "shape-" (:id shape)))]
                   (let [xml  (-> (js/XMLSerializer.)
                                  (.serializeToString frame-node)
                                  js/encodeURIComponent
                                  js/unescape
                                  js/btoa)
                         img-src (str "data:image/svg+xml;base64," xml)]
                     (obj/set! img-node "src" img-src))

                   (on-frame-not-found (:id shape))))))))

        on-image-load
        (mf/use-callback
         (mf/deps on-thumbnail-data)
         (fn []
           (let [canvas-node (mf/ref-val thumbnail-canvas)
                 img-node (mf/ref-val thumbnail-img)
                 canvas-context (.getContext canvas-node "2d")
                 _ (.drawImage canvas-context img-node 0 0)
                 data (.toDataURL canvas-node "image/jpeg" 0.8)]
             (on-thumbnail-data data))))]

    [:div.frame-renderer {:ref on-dom-rendered
                          :style {:display "none"}}
     [:img.thumbnail-img
      {:ref thumbnail-img
       :width (:width shape)
       :height (:height shape)
       :on-load on-image-load}]

     [:canvas.thumbnail-canvas
      {:ref thumbnail-canvas
       :width (:width shape)
       :height (:height shape)}]]))

(mf/defc frame-renderer
  "Component in charge of creating thumbnails and storing them"
  {::mf/wrap-props false}
  [props]
  (let [objects (obj/get props "objects")

        ;; Id of the current frame being rendered
        shape-id (mf/use-state nil)

        ;; This subject will emit a value every time there is a free "slot" to render
        ;; a thumbnail
        next (mf/use-memo #(rx/behavior-subject :next))

        render-frame
        (mf/use-callback
         (fn [frame-id]
           (reset! shape-id frame-id)))

        updates-stream
        (mf/use-memo
         (fn []
           (let [update-events
                 (->> st/stream
                      (rx/filter dwp/update-frame-thumbnail?))]
             (->> (rx/zip update-events next)
                  (rx/map first)))))

        on-thumbnail-data
        (mf/use-callback
         (mf/deps @shape-id)
         (fn [data]
           (reset! shape-id nil)
           (timers/schedule
            (fn []
              (st/emit! (dwc/update-shapes [@shape-id]
                                           #(assoc % :thumbnail data)))
              (rx/push! next :next)))))

        on-frame-not-found
        (mf/use-callback
         (fn [frame-id]
           ;; If we couldn't find the frame maybe is still rendering. We push the event again
           ;; after a time
           (timers/schedule-on-idle #(dwp/update-frame-thumbnail frame-id))
           (rx/push! next :next)))]

    (mf/use-effect
     (mf/deps render-frame)
     (fn []
       (let [sub (->> updates-stream
                      (rx/subs #(render-frame (-> (deref %) :frame-id))))]

         #(rx/dispose! sub))))

    (mf/use-layout-effect
     (fn []
       (timers/schedule-on-idle
        #(st/emit! (dwp/watch-state-changes)))))

    (when (and (some? @shape-id) (contains? objects @shape-id))
      [:& frame-thumbnail {:key (str "thumbnail-" @shape-id)
                           :shape (get objects @shape-id)
                           :on-thumbnail-data on-thumbnail-data
                           :on-frame-not-found on-frame-not-found}])))
