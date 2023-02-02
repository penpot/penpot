;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.thumbnail-render
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.frame :as frame]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [debug :refer [debug?]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defn- draw-thumbnail-canvas!
  [canvas-node img-node]
  (try
    (when (and (some? canvas-node) (some? img-node))
      (let [canvas-context (.getContext canvas-node "2d")
            canvas-width   (.-width canvas-node)
            canvas-height  (.-height canvas-node)]
        (.clearRect canvas-context 0 0 canvas-width canvas-height)
        (.drawImage canvas-context img-node 0 0 canvas-width canvas-height)

        ;; Set a true on the next animation frame, we make sure the drawImage is completed
        (ts/raf
         #(dom/set-data! canvas-node "ready" "true"))
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

        [fixed-width fixed-height]
        (if (> width height)
          [(mth/clamp width 250 2000)
           (/ (* height (mth/clamp width 250 2000)) width)]
          [(/ (* width (mth/clamp height 250 2000)) height)
           (mth/clamp height 250 2000)])

        image-url    (mf/use-state nil)
        observer-ref (mf/use-var nil)

        shape-bb-ref (hooks/use-update-var shape-bb)

        updates-str  (mf/use-memo #(rx/subject))

        thumbnail-data-ref (mf/use-memo (mf/deps page-id id) #(refs/thumbnail-frame-data page-id id))
        thumbnail-data     (mf/deref thumbnail-data-ref)

        ;; We only need the zoom level in Safari. For other browsers we don't want to activate this because
        ;; will render for every zoom change
        zoom (when (cf/check-browser? :safari) (mf/deref refs/selected-zoom))

        prev-thumbnail-data (hooks/use-previous thumbnail-data)

        ;; State to indicate to the parent that should render the frame
        render-frame? (mf/use-state (not thumbnail-data))

        ;; State variable to select whether we show the image thumbnail or the canvas thumbnail
        show-frame-thumbnail (mf/use-state (some? thumbnail-data))

        disable-fills? (or @show-frame-thumbnail (some? @image-url))

        on-image-load
        (mf/use-callback
         (mf/deps @show-frame-thumbnail)
         (fn []
           (let [canvas-node (mf/ref-val frame-canvas-ref)
                 img-node    (mf/ref-val frame-image-ref)]
             (when (draw-thumbnail-canvas! canvas-node img-node)
               (when-not (cf/check-browser? :safari)
                 (reset! image-url nil))

               (when @show-frame-thumbnail
                 (reset! show-frame-thumbnail false))
               ;; If we don't have the thumbnail data saved (normally the first load) we update the data
               ;; when available
               (when (not @thumbnail-data-ref)
                 (st/emit! (dwt/update-thumbnail page-id id) ))

               (reset! render-frame? false)))))

        generate-thumbnail
        (mf/use-callback
         (fn generate-thumbnail []
           (try
             ;; When starting generating the canvas we mark it as not ready so its not send to back until
             ;; we have time to update it
             (let [node @node-ref]
               (if (dom/has-children? node)
                 ;; The frame-content need to have children in order to generate the thumbnail
                 (let [style-node (dom/query (dm/str "#frame-container-" (:id shape) " style"))

                       {:keys [x y width height]} @shape-bb-ref
                       viewbox (dm/str x " " y " " width " " height)

                       ;; This is way faster than creating a node through the DOM API
                       svg-data
                       (dm/fmt "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" viewBox=\"%\" width=\"%\" height=\"%\" fill=\"none\">% %</svg>"
                               viewbox
                               width
                               height
                               (if (some? style-node) (dom/node->xml style-node) "")
                               (dom/node->xml node))

                       blob (js/Blob. #js [svg-data] #js {:type "image/svg+xml;charset=utf-8"})

                       img-src (.createObjectURL js/URL blob)]
                   (reset! image-url img-src))

                 ;; Node not yet ready, we schedule a new generation
                 (ts/schedule generate-thumbnail)))

             (catch :default e
               (.error js/console e)))))

        on-change-frame
        (mf/use-callback
         (fn []
           (when (and (some? @node-ref) @rendered? @regenerate-thumbnail)
             (let [loading-images? (some? (dom/query @node-ref "[data-loading='true']"))
                   loading-fonts? (some? (dom/query (dm/str "#frame-container-" (:id shape) " > style[data-loading='true']")))]
               (when (and (not loading-images?) (not loading-fonts?))
                 (generate-thumbnail)
                 (reset! regenerate-thumbnail false))))))

        on-update-frame
        (mf/use-callback
         (fn []
           (let [canvas-node (mf/ref-val frame-canvas-ref)]
             (when (not= "false" (dom/get-data canvas-node "ready"))
               (dom/set-data! canvas-node "ready" "false")))
           (when (not @disable-ref?)
             (reset! render-frame? true)
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
       (when (and disable? (not @disable-ref?))
         (rx/push! updates-str :update))
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

    ;; When the thumbnail-data is empty we regenerate the thumbnail
    (mf/use-effect
     (mf/deps (:selrect shape) thumbnail-data)
     (fn []
       (let [{:keys [width height]} (:selrect shape)]
         (p/then (wapi/empty-png-size width height)
                 (fn [data]
                   (when (<= (count thumbnail-data) (+ 100 (count data)))
                     (rx/push! updates-str :update)))))))

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

       [:foreignObject {:x x
                        :y y
                        :width width
                        :height height
                        :opacity (when disable-fills? 0)}
        [:canvas.thumbnail-canvas
         {:key (dm/str "thumbnail-canvas-" (:id shape))
          :ref frame-canvas-ref
          :data-object-id (dm/str page-id (:id shape))
          :width width
          :height height
          :style {;; Safari has a problem with the positioning of the canvas. All this is to fix Safari behavior
                  ;; https://bugs.webkit.org/show_bug.cgi?id=23113
                  :display (when (cf/check-browser? :safari) "none")
                  :position "fixed"
                  :transform-origin "top left"
                  :transform (when (cf/check-browser? :safari) (dm/fmt "scale(%)" zoom))
                  ;; DEBUG
                  :filter (when (debug? :thumbnails) "invert(1)")}}]]

       ;; Safari don't support filters so instead we add a rectangle around the thumbnail
       (when (and (cf/check-browser? :safari) (debug? :thumbnails))
         [:rect {:x (+ x 2)
                 :y (+ y 2)
                 :width (- width 4)
                 :height (- height 4)
                 :stroke "blue"
                 :stroke-width 2}])

       (when (some? @image-url)
         [:foreignObject {:x x
                          :y y
                          :width fixed-width
                          :height fixed-height}
          [:img {:ref frame-image-ref
                 :src @image-url
                 :width fixed-width
                 :height fixed-height
                 :on-load on-image-load}]])])]))
