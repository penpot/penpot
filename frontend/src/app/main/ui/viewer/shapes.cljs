;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages :as cp]
   [app.common.types.interactions :as cti]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [rumext.alpha :as mf]))

(defn activate-interaction
  [interaction shape]
  (case (:action-type interaction)
    :navigate
    (when-let [frame-id (:destination interaction)]
      (st/emit! (dv/go-to-frame frame-id)))

    :open-overlay
    (let [frame-id            (:destination interaction)
          position            (:overlay-position interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)]
      (when frame-id
        (st/emit! (dv/open-overlay frame-id
                                   position
                                   close-click-outside
                                   background-overlay))))

    :toggle-overlay
    (let [frame-id            (:destination interaction)
          position            (:overlay-position interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)]
      (when frame-id
        (st/emit! (dv/toggle-overlay frame-id
                                     position
                                     close-click-outside
                                     background-overlay))))

    :close-overlay
    (let [frame-id (or (:destination interaction)
                       (if (= (:type shape) :frame)
                         (:id shape)
                         (:frame-id shape)))]
      (st/emit! (dv/close-overlay frame-id)))

    :prev-screen
    (st/emit! (rt/nav-back-local))

    :open-url
    (st/emit! (dom/open-new-window (:url interaction)))

    nil))

;; Perform the opposite action of an interaction, if possible
(defn deactivate-interaction
  [interaction shape]
  (case (:action-type interaction)
    :open-overlay
    (let [frame-id (or (:destination interaction)
                       (if (= (:type shape) :frame)
                         (:id shape)
                         (:frame-id shape)))]
      (st/emit! (dv/close-overlay frame-id)))

    :toggle-overlay
    (let [frame-id            (:destination interaction)
          position            (:overlay-position interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)]
      (when frame-id
        (st/emit! (dv/toggle-overlay frame-id
                                     position
                                     close-click-outside
                                     background-overlay))))

    :close-overlay
    (let [frame-id            (:destination interaction)
          position            (:overlay-position interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)]
      (when frame-id
        (st/emit! (dv/open-overlay frame-id
                                   position
                                   close-click-outside
                                   background-overlay))))
    nil))

(defn on-mouse-down
  [event shape]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :click)
                                       (= (:event-type %) :mouse-press))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape)))))

(defn on-mouse-up
  [event shape]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :mouse-press)))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (deactivate-interaction interaction shape)))))

(defn on-mouse-enter
  [event shape]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :mouse-enter)
                                       (= (:event-type %) :mouse-over))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape)))))

(defn on-mouse-leave
  [event shape]
  (let [interactions     (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-leave)))
        interactions-inv (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-over)))]
    (when (or (seq interactions) (seq interactions-inv))
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape))
      (doseq [interaction interactions-inv]
        (deactivate-interaction interaction shape)))))

(defn on-load
  [shape]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :after-delay)))]
    (loop [interactions (seq interactions)
           sems []]
      (if-let [interaction (first interactions)]
        (let [sem (tm/schedule (:delay interaction)
                               #(activate-interaction interaction shape))]
          (recur (next interactions)
                 (conj sems sem)))
        sems))))

(mf/defc interaction
  [{:keys [shape interactions show-interactions?]}]
  (let [{:keys [x y width height]} (:selrect shape)
        frame? (= :frame (:type shape))]
    (when-not (empty? interactions)
      [:rect {:x (- x 1)
              :y (- y 1)
              :width (+ width 2)
              :height (+ height 2)
              :fill "#31EFB8"
              :stroke "#31EFB8"
              :stroke-width (if show-interactions? 1 0)
              :fill-opacity (if show-interactions? 0.2 0)
              :style {:pointer-events (when frame? "none")}
              :transform (geom/transform-matrix shape)}])))

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component show-interactions?]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape   (unchecked-get props "shape")
          childs  (unchecked-get props "childs")
          frame   (unchecked-get props "frame")

          interactions (:interactions shape)

          svg-element? (and (= :svg-raw (:type shape))
                            (not= :svg (get-in shape [:content :tag])))]

      (mf/use-effect
        (fn []
          (let [sems (on-load shape)]
            #(run! tm/dispose! sems))))

      (if-not svg-element?
        [:> shape-container {:shape shape
                             :cursor (when (cti/actionable? interactions) "pointer")
                             :on-mouse-down #(on-mouse-down % shape)
                             :on-mouse-up #(on-mouse-up % shape)
                             :on-mouse-enter #(on-mouse-enter % shape)
                             :on-mouse-leave #(on-mouse-leave % shape)}

         [:& component {:shape shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true}]

         [:& interaction {:shape shape
                          :interactions interactions
                          :show-interactions? show-interactions?}]]

        ;; Don't wrap svg elements inside a <g> otherwise some can break
        [:& component {:shape shape
                       :frame frame
                       :childs childs}]))))

(defn frame-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (frame/frame-shape shape-container) show-interactions?))

(defn group-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (group/group-shape shape-container) show-interactions?))

(defn bool-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (bool/bool-shape shape-container) show-interactions?))

(defn svg-raw-wrapper
  [shape-container show-interactions?]
  (generic-wrapper-factory (svg-raw/svg-raw-shape shape-container) show-interactions?))

(defn rect-wrapper
  [show-interactions?]
  (generic-wrapper-factory rect/rect-shape show-interactions?))

(defn image-wrapper
  [show-interactions?]
  (generic-wrapper-factory image/image-shape show-interactions?))

(defn path-wrapper
  [show-interactions?]
  (generic-wrapper-factory path/path-shape show-interactions?))

(defn text-wrapper
  [show-interactions?]
  (generic-wrapper-factory text/text-shape show-interactions?))

(defn circle-wrapper
  [show-interactions?]
  (generic-wrapper-factory circle/circle-shape show-interactions?))

(declare shape-container-factory)

(defn frame-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        frame-wrapper   (frame-wrapper shape-container show-interactions?)]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (obj/get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            shape  (geom/transform-shape shape)
            props  (obj/merge! #js {} props
                               #js {:shape shape
                                    :childs childs
                                    :objects objects
                                    :show-interactions? show-interactions?})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        group-wrapper (group-wrapper shape-container show-interactions?)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects
                                    :show-interactions? show-interactions?})]
        [:> group-wrapper props]))))

(defn bool-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        bool-wrapper (bool-wrapper shape-container show-interactions?)]
    (mf/fnc bool-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (select-keys objects (cp/get-children (:id shape) objects))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects
                                    :show-interactions? show-interactions?})]
        [:> bool-wrapper props]))))

(defn svg-raw-container-factory
  [objects show-interactions?]
  (let [shape-container (shape-container-factory objects show-interactions?)
        svg-raw-wrapper (svg-raw-wrapper shape-container show-interactions?)]
    (mf/fnc svg-raw-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects
                                    :show-interactions? show-interactions?})]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects show-interactions?]
  (let [path-wrapper   (path-wrapper show-interactions?)
        text-wrapper   (text-wrapper show-interactions?)
        rect-wrapper   (rect-wrapper show-interactions?)
        image-wrapper  (image-wrapper show-interactions?)
        circle-wrapper (circle-wrapper show-interactions?)]
    (mf/fnc shape-container
      {::mf/wrap-props false}
      [props]
      (let [group-container
            (mf/use-memo (mf/deps objects)
                         #(group-container-factory objects show-interactions?))

            bool-container
            (mf/use-memo (mf/deps objects)
                         #(bool-container-factory objects show-interactions?))

            svg-raw-container
            (mf/use-memo (mf/deps objects)
                         #(svg-raw-container-factory objects show-interactions?))
            shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")]
        (when (and shape (not (:hidden shape)))
          (let [shape (-> (geom/transform-shape shape)
                          (geom/translate-to-frame frame))
                opts #js {:shape shape
                          :objects objects}]
            (case (:type shape)
              :frame   [:g.empty]
              :text    [:> text-wrapper opts]
              :rect    [:> rect-wrapper opts]
              :path    [:> path-wrapper opts]
              :image   [:> image-wrapper opts]
              :circle  [:> circle-wrapper opts]
              :group   [:> group-container {:shape shape :frame frame :objects objects}]
              :bool    [:> bool-container {:shape shape :frame frame :objects objects}]
              :svg-raw [:> svg-raw-container {:shape shape :frame frame :objects objects}])))))))

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom show-interactions?] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        update-fn    #(assoc-in %1 [%2 :modifiers :displacement] modifier)

        frame-id     (:id frame)
        modifier-ids (d/concat [frame-id] (cp/get-children frame-id objects))
        objects      (reduce update-fn objects modifier-ids)
        frame        (assoc-in frame [:modifiers :displacement] modifier)

        width        (* (:width frame) zoom)
        height       (* (:height frame) zoom)
        vbox         (str "0 0 " (:width frame 0)
                          " "    (:height frame 0))
        wrapper      (mf/use-memo
                      (mf/deps objects)
                      #(frame-container-factory objects show-interactions?))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape frame
                  :show-interactions? show-interactions?
                  :view-box vbox}]]))

