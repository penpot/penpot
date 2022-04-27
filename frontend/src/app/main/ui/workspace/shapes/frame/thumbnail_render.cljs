;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame.thumbnail-render
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [rumext.alpha :as mf]))

(defn- draw-thumbnail-canvas
  [canvas-node img-node]
  (let [canvas-context (.getContext canvas-node "2d")
        canvas-width   (.-width canvas-node)
        canvas-height  (.-height canvas-node)]
    (.clearRect canvas-context 0 0 canvas-width canvas-height)
    (.drawImage canvas-context img-node 0 0 canvas-width canvas-height)
    (.toDataURL canvas-node "image/jpeg" 0.8)))

(defn use-render-thumbnail
  "Hook that will create the thumbnail thata"
  [{:keys [id x y width height] :as shape} node-ref rendered? thumbnail? disable?]

  (let [frame-canvas-ref (mf/use-ref nil)
        frame-image-ref (mf/use-ref nil)

        disable-ref? (mf/use-var disable?)

        fixed-width (mth/clamp (:width shape) 250 2000)
        fixed-height (/ (* (:height shape) fixed-width) (:width shape))

        image-url    (mf/use-state nil)
        observer-ref (mf/use-var nil)

        shape-ref (hooks/use-update-var shape)

        thumbnail-ref? (mf/use-var thumbnail?)

        on-image-load
        (mf/use-callback
         (fn []
           (let [canvas-node (mf/ref-val frame-canvas-ref)
                 img-node    (mf/ref-val frame-image-ref)
                 thumb-data  (draw-thumbnail-canvas canvas-node img-node)]
             (st/emit! (dw/update-thumbnail id thumb-data))
             (reset! image-url nil))))

        on-change
        (mf/use-callback
         (fn []
           (when (and (some? @node-ref) (not @disable-ref?))
             (let [node @node-ref]
               (ts/schedule-on-idle
                #(let [frame-html (dom/node->xml node)
                       {:keys [x y width height]} @shape-ref
                       svg-node
                       (-> (dom/make-node "http://www.w3.org/2000/svg" "svg")
                           (dom/set-property! "version" "1.1")
                           (dom/set-property! "viewBox" (dm/str x " " y " " width " " height))
                           (dom/set-property! "width" width)
                           (dom/set-property! "height" height)
                           (dom/set-property! "fill" "none")
                           (obj/set! "innerHTML" frame-html))
                       img-src  (-> svg-node dom/node->xml dom/svg->data-uri)]
                   (reset! image-url img-src)))))))

        on-load-frame-dom
        (mf/use-callback
         (fn [node]
           (when (and (some? node) (nil? @observer-ref))
             (on-change [])
             (let [observer (js/MutationObserver. on-change)]
               (.observe observer node #js {:childList true :attributes true :characterData true :subtree true})
               (reset! observer-ref observer)))))]

    (mf/use-effect
     (mf/deps disable?)
     (fn []
       (reset! disable-ref? disable?)))

    (mf/use-effect
     (mf/deps thumbnail?)
     (fn []
       (reset! thumbnail-ref? thumbnail?)))

    (mf/use-effect
     (fn []
       #(when (and (some? @node-ref) @rendered?)
          (mf/unmount @node-ref)
          (reset! node-ref nil)
          (reset! rendered? false)
          (when (some? @observer-ref)
            (.disconnect @observer-ref)
            (reset! observer-ref nil)))))

    [on-load-frame-dom
     (when (some? @image-url)
       (mf/html
        [:g.thumbnail-rendering {:opacity 0}
         [:foreignObject {:x x :y y :width width :height height}
          [:canvas {:ref frame-canvas-ref
                    :width fixed-width
                    :height fixed-height}]]

         [:image {:ref frame-image-ref
                  :x (:x shape)
                  :y (:y shape)
                  :xlinkHref @image-url
                  :width (:width shape)
                  :height (:height shape)
                  :on-load on-image-load}]]))]))
