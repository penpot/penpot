;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages.helpers :as cph]
   [app.common.spec.interactions :as cti]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
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
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def base-frame-ctx (mf/create-context nil))
(def frame-offset-ctx (mf/create-context nil))

(def viewer-interactions-show?
  (l/derived :interactions-show? refs/viewer-local))

(defn- activate-interaction
  [interaction shape base-frame frame-offset objects]
  (case (:action-type interaction)
    :navigate
    (when-let [frame-id (:destination interaction)]
      (let [viewer-section (dom/get-element "viewer-section")
            scroll (if (:preserve-scroll interaction)
                     (dom/get-scroll-pos viewer-section)
                     0)]
        (st/emit! (dv/set-nav-scroll scroll)
                  (dv/go-to-frame frame-id (:animation interaction)))))

    :open-overlay
    (let [dest-frame-id       (:destination interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)

          dest-frame (get objects dest-frame-id)
          position   (cti/calc-overlay-position interaction
                                                base-frame
                                                dest-frame
                                                frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)))))

    :toggle-overlay
    (let [frame-id            (:destination interaction)
          position            (:overlay-position interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)]
      (when frame-id
        (st/emit! (dv/toggle-overlay frame-id
                                     position
                                     close-click-outside
                                     background-overlay
                                     (:animation interaction)))))

    :close-overlay
    (let [frame-id (or (:destination interaction)
                       (if (= (:type shape) :frame)
                         (:id shape)
                         (:frame-id shape)))]
      (st/emit! (dv/close-overlay frame-id (:animation interaction))))

    :prev-screen
    (st/emit! (rt/nav-back-local))

    :open-url
    (st/emit! (dom/open-new-window (:url interaction)))

    nil))

;; Perform the opposite action of an interaction, if possible
(defn- deactivate-interaction
  [interaction shape base-frame frame-offset objects]
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
                                     background-overlay
                                     (:animation interaction)))))

    :close-overlay
    (let [dest-frame-id       (:destination interaction)
          close-click-outside (:close-click-outside interaction)
          background-overlay  (:background-overlay interaction)

          dest-frame (get objects dest-frame-id)
          position   (cti/calc-overlay-position interaction
                                                base-frame
                                                dest-frame
                                                frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)))))
    nil))

(defn- on-mouse-down
  [event shape base-frame frame-offset objects]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :click)
                                       (= (:event-type %) :mouse-press))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects)))))

(defn- on-mouse-up
  [event shape base-frame frame-offset objects]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :mouse-press)))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (deactivate-interaction interaction shape base-frame frame-offset objects)))))

(defn- on-mouse-enter
  [event shape base-frame frame-offset objects]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :mouse-enter)
                                       (= (:event-type %) :mouse-over))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects)))))

(defn- on-mouse-leave
  [event shape base-frame frame-offset objects]
  (let [interactions     (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-leave)))
        interactions-inv (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-over)))]
    (when (or (seq interactions) (seq interactions-inv))
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects))
      (doseq [interaction interactions-inv]
        (deactivate-interaction interaction shape base-frame frame-offset objects)))))

(defn- on-load
  [shape base-frame frame-offset objects]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :after-delay)))]
    (loop [interactions (seq interactions)
           sems []]
      (if-let [interaction (first interactions)]
        (let [sem (tm/schedule (:delay interaction)
                               #(activate-interaction interaction shape base-frame frame-offset objects))]
          (recur (next interactions)
                 (conj sems sem)))
        sems))))

(mf/defc interaction
  [{:keys [shape interactions interactions-show?]}]
  (let [{:keys [x y width height]} (:selrect shape)
        frame? (= :frame (:type shape))]
    (when-not (empty? interactions)
      [:rect {:x (- x 1)
              :y (- y 1)
              :width (+ width 2)
              :height (+ height 2)
              :fill "var(--color-primary)"
              :stroke "var(--color-primary)"
              :stroke-width (if interactions-show? 1 0)
              :fill-opacity (if interactions-show? 0.2 0)
              :style {:pointer-events (when frame? "none")}
              :transform (geom/transform-matrix shape)}])))

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape   (unchecked-get props "shape")
          childs  (unchecked-get props "childs")
          frame   (unchecked-get props "frame")
          objects (unchecked-get props "objects")

          base-frame    (mf/use-ctx base-frame-ctx)
          frame-offset (mf/use-ctx frame-offset-ctx)

          interactions-show? (mf/deref viewer-interactions-show?)

          interactions (:interactions shape)

          svg-element? (and (= :svg-raw (:type shape))
                            (not= :svg (get-in shape [:content :tag])))]

      (mf/use-effect
        (fn []
          (let [sems (on-load shape base-frame frame-offset objects)]
            #(run! tm/dispose! sems))))

      (if-not svg-element?
        [:> shape-container {:shape shape
                             :cursor (when (cti/actionable? interactions) "pointer")
                             :on-mouse-down #(on-mouse-down % shape base-frame frame-offset objects)
                             :on-mouse-up #(on-mouse-up % shape base-frame frame-offset objects)
                             :on-mouse-enter #(on-mouse-enter % shape base-frame frame-offset objects)
                             :on-mouse-leave #(on-mouse-leave % shape base-frame frame-offset objects)}

         [:& component {:shape shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true}]

         [:& interaction {:shape shape
                          :interactions interactions
                          :interactions-show? interactions-show?}]]

        ;; Don't wrap svg elements inside a <g> otherwise some can break
        [:& component {:shape shape
                       :frame frame
                       :childs childs}]))))

(defn frame-wrapper
  [shape-container]
  (generic-wrapper-factory (frame/frame-shape shape-container)))

(defn group-wrapper
  [shape-container]
  (generic-wrapper-factory (group/group-shape shape-container)))

(defn bool-wrapper
  [shape-container]
  (generic-wrapper-factory (bool/bool-shape shape-container)))

(defn svg-raw-wrapper
  [shape-container]
  (generic-wrapper-factory (svg-raw/svg-raw-shape shape-container)))

(defn rect-wrapper
  []
  (generic-wrapper-factory rect/rect-shape))

(defn image-wrapper
  []
  (generic-wrapper-factory image/image-shape))

(defn path-wrapper
  []
  (generic-wrapper-factory path/path-shape))

(defn text-wrapper
  []
  (generic-wrapper-factory text/text-shape))

(defn circle-wrapper
  []
  (generic-wrapper-factory circle/circle-shape))

(declare shape-container-factory)

(defn frame-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        frame-wrapper   (frame-wrapper shape-container)]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape     (obj/get props "shape")
            childs    (mapv #(get objects %) (:shapes shape))
            shape     (geom/transform-shape shape)
            props     (obj/merge! #js {} props
                                  #js {:shape shape
                                       :childs childs
                                       :objects objects})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        group-wrapper (group-wrapper shape-container)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> group-wrapper props]))))

(defn bool-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        bool-wrapper (bool-wrapper shape-container)]
    (mf/fnc bool-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (->> (cph/get-children-ids objects (:id shape))
                        (select-keys objects))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> bool-wrapper props]))))

(defn svg-raw-container-factory
  [objects]
  (let [shape-container (shape-container-factory objects)
        svg-raw-wrapper (svg-raw-wrapper shape-container)]
    (mf/fnc svg-raw-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (mapv #(get objects %) (:shapes shape))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects]
  (let [path-wrapper   (path-wrapper)
        text-wrapper   (text-wrapper)
        rect-wrapper   (rect-wrapper)
        image-wrapper  (image-wrapper)
        circle-wrapper (circle-wrapper)]
    (mf/fnc shape-container
      {::mf/wrap-props false}
      [props]
      (let [group-container
            (mf/use-memo (mf/deps objects)
                         #(group-container-factory objects))

            bool-container
            (mf/use-memo (mf/deps objects)
                         #(bool-container-factory objects))

            svg-raw-container
            (mf/use-memo (mf/deps objects)
                         #(svg-raw-container-factory objects))
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
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        update-fn    #(assoc-in %1 [%2 :modifiers :displacement] modifier)

        frame-id     (:id frame)
        modifier-ids (into [frame-id] (cph/get-children-ids objects frame-id))
        objects      (reduce update-fn objects modifier-ids)
        frame        (assoc-in frame [:modifiers :displacement] modifier)
        width        (* (:width frame) zoom)
        height       (* (:height frame) zoom)

        vbox         (str "0 0 " (:width frame 0)
                          " "    (:height frame 0))
        wrapper      (mf/use-memo
                      (mf/deps objects)
                      #(frame-container-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape frame
                  :view-box vbox}]]))

