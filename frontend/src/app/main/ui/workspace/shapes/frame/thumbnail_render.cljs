;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame.thumbnail-render
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

;; (def thumbnail-scale-factor 2)

(defn- draw-thumbnail-canvas
  [canvas-node img-node]
  (try
    (when (and (some? canvas-node) (some? img-node))
      (let [canvas-context (.getContext canvas-node "2d")
            canvas-width   (.-width canvas-node)
            canvas-height  (.-height canvas-node)]

        ;; TODO: Expermient with different scale factors
        ;; (set! (.-width canvas-node) (* thumbnail-scale-factor canvas-width))
        ;; (set! (.-height canvas-node) (* thumbnail-scale-factor canvas-height))
        ;; (.setTransform canvas-context thumbnail-scale-factor 0 0 thumbnail-scale-factor 0 0)
        ;; (set! (.-imageSmoothingEnabled canvas-context) true)
        ;; (set! (.-imageSmoothingQuality canvas-context) "high")

        (.clearRect canvas-context 0 0 canvas-width canvas-height)
        (.drawImage canvas-context img-node 0 0 canvas-width canvas-height)
        (.toDataURL canvas-node "image/png" 1.0)))
    (catch :default err
      (.error js/console err)
      nil)))

(defn- remove-image-loading
  "Remove the changes related to change a url for its embed value. This is necessary
  so we don't have to recalculate the thumbnail when the image loads."
  [value]
  (if (.isArray js/Array value)
    (->> value
         (remove (fn [change]
                   (or
                    (= "data-loading" (.-attributeName change))
                    (and (= "attributes" (.-type change))
                         (= "href" (.-attributeName change))
                         (str/starts-with? (.-oldValue change) "http"))))))
    [value]))

(defn use-render-thumbnail
  "Hook that will create the thumbnail thata"
  [page-id {:keys [id x y width height] :as shape} node-ref rendered? thumbnail-data-ref disable?]

  (let [frame-canvas-ref (mf/use-ref nil)
        frame-image-ref (mf/use-ref nil)

        disable-ref? (mf/use-var disable?)

        regenerate-thumbnail (mf/use-var false)

        fixed-width (mth/clamp (:width shape) 250 2000)
        fixed-height (/ (* (:height shape) fixed-width) (:width shape))

        image-url    (mf/use-state nil)
        observer-ref (mf/use-var nil)

        shape-ref (hooks/use-update-var shape)

        updates-str (mf/use-memo #(rx/subject))

        on-image-load
        (mf/use-callback
         (fn []
           (ts/raf
            #(let [canvas-node (mf/ref-val frame-canvas-ref)
                   img-node    (mf/ref-val frame-image-ref)
                   thumb-data  (draw-thumbnail-canvas canvas-node img-node)]
               (when (some? thumb-data)
                 (st/emit! (dw/update-thumbnail page-id id thumb-data))
                 (reset! image-url nil))))))

        generate-thumbnail
        (mf/use-callback
         (fn []
           (let [node @node-ref
                 frame-html (dom/node->xml node)
                 {:keys [x y width height]} @shape-ref

                 style-node (dom/query (dm/str "#frame-container-" (:id shape) " style"))
                 style-str (or (-> style-node dom/node->xml) "")

                 svg-node
                 (-> (dom/make-node "http://www.w3.org/2000/svg" "svg")
                     (dom/set-property! "version" "1.1")
                     (dom/set-property! "viewBox" (dm/str x " " y " " width " " height))
                     (dom/set-property! "width" width)
                     (dom/set-property! "height" height)
                     (dom/set-property! "fill" "none")
                     (obj/set! "innerHTML" (dm/str style-str frame-html)))
                 img-src  (-> svg-node dom/node->xml dom/svg->data-uri)]
             (reset! image-url img-src))))

        on-change-frame
        (mf/use-callback
         (fn []
           (when (and (some? @node-ref) @regenerate-thumbnail)
             (let [loading-images? (some? (dom/query @node-ref "[data-loading='true']"))
                   loading-fonts? (some? (dom/query (dm/str "#frame-container-" (:id shape) " style[data-loading='true']")))]
               (when (and (not loading-images?) (not loading-fonts?))
                 (generate-thumbnail)
                 (reset! regenerate-thumbnail false))))))

        on-update-frame
        (mf/use-callback
         (fn []
           (when (not @disable-ref?)
             (reset! regenerate-thumbnail true))))

        on-load-frame-dom
        (mf/use-callback
         (fn [node]
           (when (and (some? node) (nil? @observer-ref))
             (when-not (some? @thumbnail-data-ref)
               (rx/push! updates-str :update))

             (let [observer (js/MutationObserver. (partial rx/push! updates-str))]
               (.observe observer node #js {:childList true :attributes true :attributeOldValue true :characterData true :subtree true})
               (reset! observer-ref observer)))))]

    (mf/use-effect
     (fn []
       (let [subid (->> updates-str
                        (rx/map remove-image-loading)
                        (rx/filter d/not-empty?)
                        (rx/catch (fn [err] (.error js/console err)))
                        (rx/subs on-update-frame))]
         #(rx/dispose! subid))))

    ;; on-change-frame will get every change in the frame
    (mf/use-effect
     (fn []
       (let [subid (->> updates-str
                        (rx/debounce 400)
                        (rx/observe-on :af)
                        (rx/catch (fn [err] (.error js/console err)))
                        (rx/subs on-change-frame))]
         #(rx/dispose! subid))))

    (mf/use-effect
     (mf/deps disable?)
     (fn []
       (reset! disable-ref? disable?)))

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
        [:g.thumbnail-rendering
         [:foreignObject {:x x :y y :width width :height height}
          [:canvas {:ref frame-canvas-ref
                    :width fixed-width
                    :height fixed-height}]]

         [:image {:ref frame-image-ref
                  :x (:x shape)
                  :y (:y shape)
                  :href @image-url
                  :width (:width shape)
                  :height (:height shape)
                  :on-load on-image-load}]]))]))
