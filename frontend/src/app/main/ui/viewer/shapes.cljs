;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.interactions :as ctsi]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.router :as rt]
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
   [app.util.timers :as tm]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def base-frame-ctx (mf/create-context nil))
(def frame-offset-ctx (mf/create-context nil))

(def ^:private ref:viewer-show-interactions
  (l/derived :show-interactions refs/viewer-local))

(defn- find-relative-to-base-frame
  [shape objects overlays-ids base-frame]
  (cond
    (cfh/frame-shape? shape) shape
    (or (empty? overlays-ids) (nil? shape) (cfh/root? shape)) base-frame
    :else (find-relative-to-base-frame (cfh/get-parent objects (:id shape)) objects overlays-ids base-frame)))

(defn- ignore-frame-shape
  [shape objects manual?]
  (let [shape (cond-> shape ;; When the the interaction is not manual and its origin is a frame,
                            ;; we need to ignore it on all the find-frame calculations
                (and (:frame-id shape) (not manual?))
                (assoc :type :rect))
        objects (assoc objects (:id shape) shape)]
    [shape objects]))

(defn- activate-interaction
  [interaction shape base-frame frame-offset objects overlays]
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
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if manual?
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects relative-to-id)
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)
                                   fixed-base?))))

    :toggle-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)

          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     (:animation interaction)
                                     fixed-base?))))

    :close-overlay
    (let [dest-frame-id (or (:destination interaction)
                            (if (and (= (:type shape) :frame)
                                     (some #(= (:id %) (:id shape)) overlays))
                              (:id shape)
                              (:frame-id shape)))]
      (st/emit! (dv/close-overlay dest-frame-id (:animation interaction))))

    :prev-screen
    (st/emit! (rt/nav-back-local))

    :open-url
    (st/emit! (dom/open-new-window (:url interaction)))

    nil))

;; Perform the opposite action of an interaction, if possible
(defn- deactivate-interaction
  [interaction shape base-frame frame-offset objects overlays]
  (case (:action-type interaction)
    :open-overlay
    (let [frame-id (or (:destination interaction)
                       (if (= (:type shape) :frame)
                         (:id shape)
                         (:frame-id shape)))]
      (st/emit! (dv/close-overlay frame-id)))

    :toggle-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)

          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     (:animation interaction)
                                     fixed-base?))))


    :close-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)
                                   fixed-base?))))
    nil))

(defn- on-pointer-down
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :click)
                                       (= (:event-type %) :mouse-press))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-up
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :mouse-press)))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (deactivate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-enter
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :mouse-enter)
                                       (= (:event-type %) :mouse-over))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-leave
  [event shape base-frame frame-offset objects overlays]
  (let [interactions     (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-leave)))
        interactions-inv (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-over)))]
    (when (or (seq interactions) (seq interactions-inv))
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays))
      (doseq [interaction interactions-inv]
        (deactivate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-load
  [shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :after-delay)))]
    (loop [interactions (seq interactions)
           sems []]
      (if-let [interaction (first interactions)]
        (let [sem (tm/schedule (:delay interaction)
                               #(activate-interaction interaction shape base-frame frame-offset objects overlays))]
          (recur (next interactions)
                 (conj sems sem)))
        sems))))

(mf/defc interaction
  [{:keys [shape interactions show-interactions]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    (when-not (empty? interactions)
      [:rect {:x (- x 1)
              :y (- y 1)
              :width (+ width 2)
              :height (+ height 2)
              :fill "var(--color-accent-tertiary)"
              :stroke "var(--color-accent-tertiary)"
              :stroke-width (if show-interactions 1 0)
              :fill-opacity (if show-interactions 0.2 0)
              :transform (gsh/transform-str shape)}])))


;; TODO: use-memo use-fn

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape              (unchecked-get props "shape")
          childs             (unchecked-get props "childs")
          frame              (unchecked-get props "frame")
          objects            (unchecked-get props "objects")
          all-objects        (or (unchecked-get props "all-objects") objects)
          base-frame         (mf/use-ctx base-frame-ctx)
          frame-offset       (mf/use-ctx frame-offset-ctx)
          show-interactions  (mf/deref ref:viewer-show-interactions)
          overlays           (mf/deref refs/viewer-overlays)
          interactions       (:interactions shape)
          svg-element?       (and (= :svg-raw (:type shape))
                                  (not= :svg (get-in shape [:content :tag])))

          ;; The objects parameter has the shapes that we must draw. It may be a subset of
          ;; all-objects in some cases (e.g. if there are fixed elements). But for interactions
          ;; handling we need access to all objects inside the page.

          on-pointer-down
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-down % shape base-frame frame-offset all-objects overlays))

          on-pointer-up
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-up % shape base-frame frame-offset all-objects overlays))

          on-pointer-enter
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-enter % shape base-frame frame-offset all-objects overlays))

          on-pointer-leave
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-leave % shape base-frame frame-offset all-objects overlays))]

      (mf/with-effect []
        (let [sems (on-load shape base-frame frame-offset objects overlays)]
          (partial run! tm/dispose! sems)))

      (if-not svg-element?
        [:> shape-container {:shape shape
                             :cursor (when (ctsi/actionable? interactions) "pointer")
                             :on-pointer-down on-pointer-down
                             :on-pointer-up on-pointer-up
                             :on-pointer-enter on-pointer-enter
                             :on-pointer-leave on-pointer-leave}

         [:& component {:shape shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true
                        :objects objects}]

         [:& interaction {:shape shape
                          :interactions interactions
                          :show-interactions show-interactions}]]

        ;; Don't wrap svg elements inside a <g> otherwise some can break
        [:& component {:shape shape
                       :frame frame
                       :childs childs
                       :objects objects}]))))

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
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        frame-wrapper   (frame-wrapper shape-container)
        lookup-xf       (keep (d/getf objects))]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (into [] lookup-xf (:shapes shape))
            props  (obj/merge props
                              #js {:childs childs
                                   :objects objects
                                   :all-objects all-objects})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        group-wrapper (group-wrapper shape-container)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [childs   (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props    (obj/merge! #js {} props
                                 #js {:childs childs
                                      :objects objects
                                      :all-objects all-objects})]
        (when (not-empty childs)
          [:> group-wrapper props])))))

(defn bool-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        bool-wrapper (bool-wrapper shape-container)]
    (mf/fnc bool-container
      {::mf/wrap-props false}
      [props]
      (let [childs (->> (cfh/get-children-ids objects (:id (unchecked-get props "shape")))
                        (select-keys objects))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> bool-wrapper props]))))

(defn svg-raw-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        svg-raw-wrapper (svg-raw-wrapper shape-container)]
    (mf/fnc svg-raw-container
      {::mf/wrap-props false}
      [props]
      (let [childs (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects all-objects]
  (let [path-wrapper   (path-wrapper)
        text-wrapper   (text-wrapper)
        rect-wrapper   (rect-wrapper)
        image-wrapper  (image-wrapper)
        circle-wrapper (circle-wrapper)]
    (mf/fnc shape-container
      {::mf/wrap-props false
       ::mf/wrap [mf/memo]}
      [props]
      (let [shape   (unchecked-get props "shape")
            frame   (unchecked-get props "frame")

            group-container
            (mf/with-memo [objects]
              (group-container-factory objects all-objects))

            frame-container
            (mf/with-memo [objects]
              (frame-container-factory objects all-objects))

            bool-container
            (mf/with-memo [objects]
              (bool-container-factory objects all-objects))

            svg-raw-container
            (mf/with-memo [objects]
              (svg-raw-container-factory objects all-objects))]
        (when (and shape (not (:hidden shape)))
          (let [shape (if frame
                        (gsh/translate-to-frame shape frame)
                        shape)

                opts #js {:shape shape
                          :objects objects
                          :all-objects all-objects}]
            (case (:type shape)
              :frame   [:> frame-container opts]
              :text    [:> text-wrapper opts]
              :rect    [:> rect-wrapper opts]
              :path    [:> path-wrapper opts]
              :image   [:> image-wrapper opts]
              :circle  [:> circle-wrapper opts]
              :group   [:> group-container {:shape shape :frame frame :objects objects}]
              :bool    [:> bool-container {:shape shape :frame frame :objects objects}]
              :svg-raw [:> svg-raw-container {:shape shape :frame frame :objects objects}])))))))
