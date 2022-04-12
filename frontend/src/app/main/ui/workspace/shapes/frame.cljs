;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.util.globals :as globals]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn check-frame-props
  "Checks for changes in the props of a frame"
  [new-props old-props]
  (let [new-shape (unchecked-get new-props "shape")
        old-shape (unchecked-get old-props "shape")

        new-thumbnail? (unchecked-get new-props "thumbnail?")
        old-thumbnail? (unchecked-get old-props "thumbnail?")

        new-objects (unchecked-get new-props "objects")
        old-objects (unchecked-get old-props "objects")

        new-children (->> new-shape :shapes (mapv #(get new-objects %)))
        old-children (->> old-shape :shapes (mapv #(get old-objects %)))]
    (and (= new-shape old-shape)
         (= new-thumbnail? old-thumbnail?)
         (= new-children old-children))))

(mf/defc frame-placeholder
  {::mf/wrap-props false}
  [props]
  (let [{:keys [x y width height fill-color] :as shape} (obj/get props "shape")]
    (if (some? (:thumbnail shape))
      [:& frame/frame-thumbnail {:shape shape}]
      [:rect.frame-thumbnail {:x x :y y :width width :height height :style {:fill (or fill-color cc/white)}}])))

(defn custom-deferred
  [component]
  (mf/fnc deferred
    {::mf/wrap-props false}
    [props]
    (let [shape (-> (obj/get props "shape")
                    (select-keys [:x :y :width :height])
                    (hooks/use-equal-memo))

          tmp (mf/useState false)
          ^boolean render? (aget tmp 0)
          ^js set-render (aget tmp 1)
          prev-shape-ref (mf/use-ref shape)]

      (mf/use-effect
       (mf/deps shape)
       (fn []
         (mf/set-ref-val! prev-shape-ref shape)
         (set-render false)))

      (mf/use-effect
       (mf/deps render? shape)
       (fn []
         (when-not render?
           (let [sem (ts/schedule-on-idle #(set-render true))]
             #(rx/dispose! sem)))))

      (if (and render? (= shape (mf/ref-val prev-shape-ref)))
        (mf/jsx component props mf/undefined)
        (mf/jsx frame-placeholder props mf/undefined)))))

(defn use-node-store
  [thumbnail? node-ref rendered?]

  (let [;; when `true` the node is in memory
        in-memory? (mf/use-var nil)

        ;; State just for re-rendering
        re-render  (mf/use-state 0)

        parent-ref (mf/use-var nil)

        on-frame-load
        (mf/use-callback
         (fn [node]
           (when (and (some? node) (nil? @node-ref))
             (let [content (.createElementNS globals/document "http://www.w3.org/2000/svg" "g")]
               (.appendChild node content)
               (reset! node-ref content)
               (reset! parent-ref node)
               (swap! re-render inc)))))]

    (mf/use-effect
     (mf/deps thumbnail?)
     (fn []
       (when (and (some? @parent-ref) (some? @node-ref) @rendered? thumbnail?)
         (.removeChild @parent-ref @node-ref)
         (reset! in-memory? true))

       (when (and (some? @node-ref) @in-memory? (not thumbnail?))
         (.appendChild @parent-ref @node-ref)
         (reset! in-memory? false))))

    on-frame-load))

(defn use-render-thumbnail
  [{:keys [x y width height] :as shape} node-ref rendered? thumbnail? thumbnail-data]

  (let [frame-canvas-ref (mf/use-ref nil)
        frame-image-ref (mf/use-ref nil)

        fixed-width (mth/clamp (:width shape) 250 2000)
        fixed-height (/ (* (:height shape) fixed-width) (:width shape))

        image-url (mf/use-state nil)
        observer-ref (mf/use-var nil)

        shape-ref (hooks/use-update-var shape)

        on-image-load
        (mf/use-callback
         (fn []
           (let [canvas-node    (mf/ref-val frame-canvas-ref)
                 img-node       (mf/ref-val frame-image-ref)

                 canvas-context (.getContext canvas-node "2d")
                 canvas-width   (.-width canvas-node)
                 canvas-height  (.-height canvas-node)]
             (.clearRect canvas-context 0 0 canvas-width canvas-height)
             (.rect canvas-context 0 0 canvas-width canvas-height)
             (set! (.-fillStyle canvas-context) "#FFFFFF")
             (.fill canvas-context)
             (.drawImage canvas-context img-node 0 0 canvas-width canvas-height)

             (let [data (.toDataURL canvas-node "image/jpg" 1)]
               (reset! thumbnail-data data))
             (reset! image-url nil))))

        on-change
        (mf/use-callback
         (fn []
           (when (some? @node-ref)
             (let [node @node-ref]
               (ts/schedule-on-idle
                #(let [frame-html (->  (js/XMLSerializer.)
                                       (.serializeToString node))

                       {:keys [x y width height]} @shape-ref
                       svg-node (.createElementNS js/document "http://www.w3.org/2000/svg" "svg")
                       _ (.setAttribute svg-node "version" "1.1")
                       _ (.setAttribute svg-node "viewBox" (dm/str x " " y " " width " " height))
                       _ (.setAttribute svg-node "width" width)
                       _ (.setAttribute svg-node "height" height)
                       _ (unchecked-set svg-node "innerHTML" frame-html)

                       xml  (-> (js/XMLSerializer.)
                                (.serializeToString svg-node)
                                js/encodeURIComponent
                                js/unescape
                                js/btoa)

                       img-src (str "data:image/svg+xml;base64," xml)]
                   (reset! image-url img-src)))))))

        on-load-frame-dom
        (mf/use-callback
         (fn [node]
           (when (and (some? node) (nil? @observer-ref))
             (let [observer (js/MutationObserver. on-change)]
               (.observe observer node #js {:childList true :attributes true :characterData true :subtree true})
               (reset! observer-ref observer)))

           ;; First time rendered if the thumbnail is not present we create it
           (when (not thumbnail?) (on-change []))))]

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
         [:foreignObject {:opacity 0 :x x :y y :width width :height height}
          [:canvas {:ref frame-canvas-ref
                    :width fixed-width
                    :height fixed-height}]]

         [:image {:opacity 0
                  :ref frame-image-ref
                  :x (:x shape)
                  :y (:y shape)
                  :xlinkHref @image-url
                  :width (:width shape)
                  :height (:height shape)
                  :on-load on-image-load}]]))]))

(defn use-dynamic-modifiers
  [shape objects node-ref]

  (let [frame-modifiers-ref
        (mf/use-memo
         (mf/deps (:id shape))
         #(refs/workspace-modifiers-by-frame-id (:id shape)))

        modifiers (mf/deref frame-modifiers-ref)

        transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           (when (some? modifiers)
             (d/mapm (fn [id {modifiers :modifiers}]
                       (let [center (gsh/center-shape (get objects id))]
                         (gsh/modifiers->transform center modifiers)))
                     modifiers))))

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           (->> (keys transforms)
                (mapv (d/getf objects)))))

        prev-shapes (mf/use-var nil)
        prev-modifiers (mf/use-var nil)
        prev-transforms (mf/use-var nil)]

    (mf/use-layout-effect
     (mf/deps transforms)
     (fn []
       (when (and (nil? @prev-transforms)
                  (some? transforms))
         (utils/start-transform! @node-ref shapes))

       (when (some? modifiers)
         (utils/update-transform! @node-ref shapes transforms modifiers))

       (when (and (some? @prev-modifiers)
                  (empty? modifiers))
         (utils/remove-transform! @node-ref @prev-shapes))

       (reset! prev-modifiers modifiers)
       (reset! prev-transforms transforms)
       (reset! prev-shapes shapes)))))

(defn frame-shape-factory-roots
  [shape-wrapper]

  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc inner-frame-shape
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "childs" "fonts" "thumbnail?"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape        (unchecked-get props "shape")
            childs       (unchecked-get props "childs")
            thumbnail?   (unchecked-get props "thumbnail?")
            fonts        (unchecked-get props "fonts")
            objects      (unchecked-get props "objects")

            thumbnail-data (mf/use-state nil)

            thumbnail? (and thumbnail?
                            (or (some? (:thumbnail shape))
                                (some? @thumbnail-data)))


            ;; References to the current rendered node and the its parentn
            node-ref   (mf/use-var nil)

            ;; when `true` we've called the mount for the frame
            rendered?  (mf/use-var false)

            [on-load-frame-dom thumb-renderer]
            (use-render-thumbnail shape node-ref rendered? thumbnail? thumbnail-data)

            on-frame-load
            (use-node-store thumbnail? node-ref rendered?)]

        (use-dynamic-modifiers shape objects node-ref)

        (when (and (some? @node-ref) (or @rendered? (not thumbnail?)))
          (mf/mount
           (mf/html
            [:& (mf/provider embed/context) {:value true}
             [:> shape-container #js {:shape shape :ref on-load-frame-dom}
              [:& ff/fontfaces-style {:fonts fonts}]
              [:> frame-shape {:shape shape
                               :childs childs} ]]])
           @node-ref)
          (when (not @rendered?) (reset! rendered? true)))

        [:*
         (when thumbnail?
           [:> frame/frame-thumbnail {:shape (cond-> shape
                                               (some? @thumbnail-data)
                                               (assoc :thumbnail @thumbnail-data))}])

         [:g.frame-container {:key "frame-container"
                              :ref on-frame-load}]
         thumb-renderer]))))

(defn frame-wrapper-factory
  [shape-wrapper]
  (let [frame-shape (frame-shape-factory-roots shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-frame-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape      (unchecked-get props "shape")
            objects    (unchecked-get props "objects")
            thumbnail? (unchecked-get props "thumbnail?")

            children
            (-> (mapv (d/getf objects) (:shapes shape))
                (hooks/use-equal-memo))

            fonts
            (-> (ff/frame->fonts shape objects)
                (hooks/use-equal-memo))]

        [:g.frame-wrapper {:display (when (:hidden shape) "none")}
         [:& frame-shape
          {:key (str (:id shape))
           :shape shape
           :fonts fonts
           :childs children
           :objects objects
           :thumbnail? thumbnail?}]]))))
