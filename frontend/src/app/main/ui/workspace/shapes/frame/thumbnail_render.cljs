;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame.thumbnail-render
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.frame :as frame]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [debug :refer [debug?]]
   [rumext.alpha :as mf]))

(defn- draw-thumbnail-canvas!
  [canvas-node img-node]
  (try
    (when (and (some? canvas-node) (some? img-node))
      (let [canvas-context (.getContext canvas-node "2d")
            canvas-width   (.-width canvas-node)
            canvas-height  (.-height canvas-node)]

        (.clearRect canvas-context 0 0 canvas-width canvas-height)
        (.drawImage canvas-context img-node 0 0 canvas-width canvas-height)
        true))
    (catch :default err
      (.error js/console err)
      false)))

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
  [page-id {:keys [id] :as shape} node-ref rendered? disable? force-render]

  (let [frame-canvas-ref (mf/use-ref nil)
        frame-image-ref (mf/use-ref nil)

        disable-ref? (mf/use-var disable?)

        regenerate-thumbnail (mf/use-var false)

        all-children-ref (mf/use-memo (mf/deps id) #(refs/all-children-objects id))
        all-children (mf/deref all-children-ref)

        {:keys [x y width height] :as shape-bb}
        (if (:show-content shape)
          (gsh/selection-rect (concat [shape] all-children))
          (-> shape :points gsh/points->selrect))

        fixed-width (mth/clamp width 250 2000)
        fixed-height (/ (* height fixed-width) width)

        image-url    (mf/use-state nil)
        observer-ref (mf/use-var nil)

        shape-bb-ref (hooks/use-update-var shape-bb)

        updates-str  (mf/use-memo #(rx/subject))

        thumbnail-data-ref (mf/use-memo (mf/deps page-id id) #(refs/thumbnail-frame-data page-id id))
        thumbnail-data     (mf/deref thumbnail-data-ref)

        prev-thumbnail-data (hooks/use-previous thumbnail-data)

        ;; State to indicate to the parent that should render the frame
        render-frame? (mf/use-state (not thumbnail-data))

        ;; State variable to select whether we show the image thumbnail or the canvas thumbnail
        show-frame-thumbnail (mf/use-state (some? thumbnail-data))

        on-image-load
        (mf/use-callback
         (fn []
           (ts/raf
            #(let [canvas-node (mf/ref-val frame-canvas-ref)
                   img-node    (mf/ref-val frame-image-ref)]
               (when (draw-thumbnail-canvas! canvas-node img-node)
                 (reset! image-url nil)

                 (when @show-frame-thumbnail
                   (reset! show-frame-thumbnail false))
                 ;; If we don't have the thumbnail data saved (normaly the first load) we update the data
                 ;; when available
                 (when (not @thumbnail-data-ref)
                   (st/emit! (dwt/update-thumbnail page-id id) ))

                 (reset! render-frame? false))))))

        generate-thumbnail
        (mf/use-callback
         (fn []
           (let [node @node-ref
                 frame-html (dom/node->xml node)

                 {:keys [x y width height]} @shape-bb-ref

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
                   loading-fonts? (some? (dom/query (dm/str "#frame-container-" (:id shape) " > style[data-loading='true']")))]
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
     (mf/deps thumbnail-data)
     (fn []
       (when (and (some? prev-thumbnail-data) (nil? thumbnail-data))
         (rx/push! updates-str :update))))

    (mf/use-effect
     (mf/deps @render-frame? thumbnail-data)
     (fn []
       (when (and (some? thumbnail-data) @render-frame?)
         (reset! render-frame? false))))

    (mf/use-effect
     (mf/deps force-render)
     (fn []
       (when force-render
         (rx/push! updates-str :update))))

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
     @render-frame?
     (mf/html
      [:& frame/frame-container {:bounds shape-bb
                                 :shape (cond-> shape
                                          (some? thumbnail-data)
                                          (assoc :thumbnail thumbnail-data))}

       (when @show-frame-thumbnail
         [:> frame/frame-thumbnail-image
          {:key (dm/str (:id shape))
           :bounds shape-bb
           :shape (cond-> shape
                    (some? thumbnail-data)
                    (assoc :thumbnail thumbnail-data))}])


       [:foreignObject {:x x :y y :width width :height height}
        [:canvas.thumbnail-canvas
         {:key (dm/str "thumbnail-canvas-" (:id shape))
          :ref frame-canvas-ref
          :data-object-id (dm/str page-id (:id shape))
          :data-empty @show-frame-thumbnail
          :width fixed-width
          :height fixed-height
          ;; DEBUG
          :style {:filter (when (debug? :thumbnails) "invert(1)")
                  :width "100%"
                  :height "100%"}}]]

       (when (some? @image-url)
         [:image {:ref frame-image-ref
                  :x x
                  :y y
                  :href @image-url
                  :width width
                  :height height
                  :on-load on-image-load}])])]))
