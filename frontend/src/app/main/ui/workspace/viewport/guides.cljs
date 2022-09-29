;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.guides
  (:require
   [app.common.colors :as colors]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.cursors :as cur]
   [app.main.ui.formats :as fmt]
   [app.main.ui.workspace.viewport.rules :as rules]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def guide-width 1)
(def guide-opacity 0.7)
(def guide-opacity-hover 1)
(def guide-color colors/primary)
(def guide-pill-width 34)
(def guide-pill-height 20)
(def guide-pill-corner-radius 4)
(def guide-active-area 16)

(def guide-creation-margin-left 8)
(def guide-creation-margin-top 28)
(def guide-creation-width 16)
(def guide-creation-height 24)

(defn use-guide
  "Hooks to support drag/drop for existing guides and new guides"
  [on-guide-change get-hover-frame zoom {:keys [id position axis frame-id]}]
  (let [dragging-ref (mf/use-ref false)
        start-ref (mf/use-ref nil)
        start-pos-ref (mf/use-ref nil)
        state (mf/use-state {:hover false
                             :new-position nil
                             :new-frame-id frame-id})

        frame-id (:new-frame-id @state)

        frame-ref (mf/use-memo (mf/deps frame-id) #(refs/object-by-id frame-id))
        frame (mf/deref frame-ref)

        snap-pixel? (mf/deref refs/snap-pixel?)

        on-pointer-enter
        (mf/use-callback
         (fn []
           (st/emit! (dw/set-hover-guide id true))
           (swap! state assoc :hover true)))

        on-pointer-leave
        (mf/use-callback
         (fn []
           (st/emit! (dw/set-hover-guide id false))
           (swap! state assoc :hover false)))
        
        on-pointer-down
        (mf/use-callback
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))
           (mf/set-ref-val! start-pos-ref (get @ms/mouse-position axis))))

        on-pointer-up
        (mf/use-callback
         (mf/deps (select-keys @state [:new-position :new-frame-id]) on-guide-change)
         (fn []
           (when (some? on-guide-change)
             (when (some? (:new-position @state))
               (on-guide-change {:position (:new-position @state)
                                 :frame-id (:new-frame-id @state)})))))

        on-lost-pointer-capture
        (mf/use-callback
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (mf/set-ref-val! start-pos-ref nil)
           (swap! state assoc :new-position nil)))

        on-mouse-move
        (mf/use-callback
         (mf/deps position zoom snap-pixel?)
         (fn [event]
           
           (when-let [_ (mf/ref-val dragging-ref)]
             (let [start-pt (mf/ref-val start-ref)
                   start-pos (mf/ref-val start-pos-ref)
                   current-pt (dom/get-client-position event)
                   delta (/ (- (get current-pt axis) (get start-pt axis)) zoom)
                   new-position (if (some? position)
                                  (+ position delta)
                                  (+ start-pos delta))

                   new-position (if snap-pixel?
                                  (mth/round new-position)
                                  new-position)

                   new-frame-id (:id (get-hover-frame))]
               (swap! state assoc
                      :new-position new-position
                      :new-frame-id new-frame-id)))))]
    {:on-pointer-enter on-pointer-enter
     :on-pointer-leave on-pointer-leave
     :on-pointer-down on-pointer-down
     :on-pointer-up on-pointer-up
     :on-lost-pointer-capture on-lost-pointer-capture
     :on-mouse-move on-mouse-move
     :state state
     :frame frame}))

;; This functions are auxiliary to get the coords of components depending on the axis
;; we're handling

(defn guide-area-axis
  [pos vbox zoom frame axis]
  (let [rules-pos (/ rules/rules-pos zoom)
        guide-active-area (/ guide-active-area zoom)]
    (cond
      (and (some? frame) (= axis :x))
      {:x (- pos (/ guide-active-area 2))
       :y (:y frame)
       :width guide-active-area
       :height (:height frame)}

      (some? frame)
      {:x (:x frame)
       :y (- pos (/ guide-active-area 2))
       :width (:width frame)
       :height guide-active-area}
      
      (= axis :x)
      {:x (- pos (/ guide-active-area 2))
       :y (+ (:y vbox) rules-pos)
       :width guide-active-area
       :height (:height vbox)}

      :else
      {:x (+ (:x vbox) rules-pos)
       :y (- pos (/ guide-active-area 2))
       :width (:width vbox)
       :height guide-active-area})))

(defn guide-line-axis
  ([pos vbox axis]
   (if (= axis :x)
     {:x1 pos
      :y1 (:y vbox)
      :x2 pos
      :y2 (+ (:y vbox) (:height vbox))}

     {:x1 (:x vbox)
      :y1 pos
      :x2 (+ (:x vbox) (:width vbox))
      :y2 pos}))

  ([pos vbox frame axis]
   (if (= axis :x)
     {:l1-x1 pos
      :l1-y1 (:y vbox)
      :l1-x2 pos
      :l1-y2 (:y frame)
      :l2-x1 pos
      :l2-y1 (:y frame)
      :l2-x2 pos
      :l2-y2 (+ (:y frame) (:height frame))
      :l3-x1 pos
      :l3-y1 (+ (:y frame) (:height frame))
      :l3-x2 pos
      :l3-y2 (+ (:y vbox) (:height vbox))}
     {:l1-x1 (:x vbox)
      :l1-y1 pos
      :l1-x2 (:x frame)
      :l1-y2 pos
      :l2-x1 (:x frame)
      :l2-y1 pos
      :l2-x2 (+ (:x frame) (:width frame))
      :l2-y2 pos
      :l3-x1 (+ (:x frame) (:width frame))
      :l3-y1 pos
      :l3-x2 (+ (:x vbox) (:width vbox))
      :l3-y2 pos})))

(defn guide-pill-axis
  [pos vbox zoom axis]
  (let [rules-pos (/ rules/rules-pos zoom)
        guide-pill-width (/ guide-pill-width zoom)
        guide-pill-height (/ guide-pill-height zoom)]

    (if (= axis :x)
      {:rect-x      (- pos (/ guide-pill-width 2))
       :rect-y      (+ (:y vbox) rules-pos (- (/ guide-pill-width 2)) (/ 3 zoom))
       :rect-width  guide-pill-width
       :rect-height guide-pill-height
       :text-x      pos
       :text-y      (+ (:y vbox) rules-pos (- (/ 3 zoom)))}

      {:rect-x      (+ (:x vbox) rules-pos (- (/ guide-pill-height 2)) (- (/ 4 zoom)))
       :rect-y      (- pos (/ guide-pill-width 2))
       :rect-width  guide-pill-height
       :rect-height guide-pill-width
       :text-x      (+ (:x vbox) rules-pos (- (/ 3 zoom)))
       :text-y      pos})))

(defn guide-inside-vbox?
  ([zoom vbox]
   (partial guide-inside-vbox? zoom vbox))

  ([zoom {:keys [x y width height]} {:keys [axis position]}]
   (let [rule-area-size (/ rules/rule-area-size zoom)
         x1 x
         x2 (+ x width)
         y1 y
         y2 (+ y height)]
     (if (= axis :x)
       (and (>= position (+ x1 rule-area-size))
            (<= position x2))
       (and (>= position (+ y1 rule-area-size))
            (<= position y2))))))

(defn guide-creation-area
  [vbox zoom axis]
  (if (= axis :x)
    {:x (+ (:x vbox) (/ guide-creation-margin-left zoom))
     :y (:y vbox)
     :width (/ guide-creation-width zoom)
     :height (:height vbox)}

    {:x (+ (:x vbox) (/ guide-creation-margin-top zoom))
     :y (:y vbox)
     :width (:width vbox)
     :height (/ guide-creation-height zoom)}))

(defn is-guide-inside-frame?
  [guide frame]

  (if (= :x (:axis guide))
    (and (>= (:position guide) (:x frame) )
         (<= (:position guide) (+ (:x frame) (:width frame)) ))

    (and (>= (:position guide) (:y frame) )
         (<= (:position guide) (+ (:y frame) (:height frame)) ))))

(mf/defc guide
  {::mf/wrap [mf/memo]}
  [{:keys [guide hover? on-guide-change get-hover-frame vbox zoom hover-frame disabled-guides? frame-modifier]}]

  (let [axis (:axis guide)

        handle-change-position
        (mf/use-callback
         (mf/deps on-guide-change)
         (fn [changes]
           (when on-guide-change
             (on-guide-change (merge guide changes)))))

        {:keys [on-pointer-enter
                on-pointer-leave
                on-pointer-down
                on-pointer-up
                on-lost-pointer-capture
                on-mouse-move
                state
                frame]} (use-guide handle-change-position get-hover-frame zoom guide)

        base-frame (or frame hover-frame)
        frame (gsh/transform-shape base-frame frame-modifier)

        move-vec (gpt/to-vec (gpt/point (:x base-frame) (:y base-frame))
                             (gpt/point (:x frame) (:y frame)))

        pos (+ (or (:new-position @state) (:position guide)) (get move-vec axis))
        guide-width (/ guide-width zoom)
        guide-pill-corner-radius (/ guide-pill-corner-radius zoom)

        frame-guide-outside?
        (and (some? frame)
             (not (is-guide-inside-frame? (assoc guide :position pos) frame)))]

    (when (or (nil? frame)
              (and (cph/root-frame? frame)
                   (not (ctst/rotated-frame? frame))))
      [:g.guide-area {:opacity (when frame-guide-outside? 0)}
       (when-not disabled-guides?
         (let [{:keys [x y width height]} (guide-area-axis pos vbox zoom frame axis)]
           [:rect {:x x
                   :y y
                   :width width
                   :height height
                   :style {:fill "none"
                           :pointer-events (if frame-guide-outside? "none" "fill")
                           :cursor (if (= axis :x) (cur/resize-ew 0) (cur/resize-ns 0))}
                   :on-pointer-enter on-pointer-enter
                   :on-pointer-leave on-pointer-leave
                   :on-pointer-down on-pointer-down
                   :on-pointer-up on-pointer-up
                   :on-lost-pointer-capture on-lost-pointer-capture
                   :on-mouse-move on-mouse-move}]))

       (if (some? frame)
         (let [{:keys [l1-x1 l1-y1 l1-x2 l1-y2
                       l2-x1 l2-y1 l2-x2 l2-y2
                       l3-x1 l3-y1 l3-x2 l3-y2]}
               (guide-line-axis pos vbox frame axis)]
           [:g
            (when (or hover? (:hover @state))
              [:line {:x1 l1-x1
                      :y1 l1-y1
                      :x2 l1-x2
                      :y2 l1-y2
                      :style {:stroke guide-color
                              :stroke-opacity guide-opacity-hover
                              :stroke-dasharray (str "0, " (/ 6 zoom))
                              :stroke-linecap "round"
                              :stroke-width guide-width}}])
            [:line {:x1 l2-x1
                    :y1 l2-y1
                    :x2 l2-x2
                    :y2 l2-y2
                    :style {:stroke guide-color
                            :stroke-width guide-width
                            :stroke-opacity (if (or hover? (:hover @state))
                                              guide-opacity-hover
                                              guide-opacity)}}]
            (when (or hover? (:hover @state))
              [:line {:x1 l3-x1
                      :y1 l3-y1
                      :x2 l3-x2
                      :y2 l3-y2
                      :style {:stroke guide-color
                              :stroke-opacity guide-opacity-hover
                              :stroke-width guide-width
                              :stroke-dasharray (str "0, " (/ 6 zoom))
                              :stroke-linecap "round"}}])])

         (let [{:keys [x1 y1 x2 y2]} (guide-line-axis pos vbox axis)]
           [:line {:x1 x1
                   :y1 y1
                   :x2 x2
                   :y2 y2
                   :style {:stroke guide-color
                           :stroke-width guide-width
                           :stroke-opacity (if (or hover? (:hover @state))
                                             guide-opacity-hover
                                             guide-opacity)}}]))

       (when (or hover? (:hover @state))
         (let [{:keys [rect-x rect-y rect-width rect-height text-x text-y]}
               (guide-pill-axis pos vbox zoom axis)]
           [:g.guide-pill
            [:rect {:x rect-x
                    :y rect-y
                    :width rect-width
                    :height rect-height
                    :rx guide-pill-corner-radius
                    :ry guide-pill-corner-radius
                    :style {:fill guide-color}}]

            [:text {:x text-x
                    :y text-y
                    :text-anchor "middle"
                    :dominant-baseline "middle"
                    :transform (when (= axis :y) (str "rotate(-90 " text-x "," text-y ")"))
                    :style {:font-size (/ rules/font-size zoom)
                            :font-family rules/font-family
                            :fill colors/black}}
             (fmt/format-number pos)]]))])))

(mf/defc new-guide-area
  [{:keys [vbox zoom axis get-hover-frame disabled-guides?]}]

  (let [on-guide-change
        (mf/use-callback
         (mf/deps vbox)
         (fn [guide]
           (let [guide (-> guide
                           (assoc :id (uuid/next)
                                  :axis axis))]
             (when (guide-inside-vbox? zoom vbox guide)
               (st/emit! (dw/update-guides guide))))))

        {:keys [on-pointer-enter
                on-pointer-leave
                on-pointer-down
                on-pointer-up
                on-lost-pointer-capture
                on-mouse-move
                state
                frame]} (use-guide on-guide-change get-hover-frame zoom {:axis axis})]

    [:g.new-guides
     (when-not disabled-guides?
       (let [{:keys [x y width height]} (guide-creation-area vbox zoom axis)]
         [:rect {:x x
                 :y y
                 :width width
                 :height height
                 :on-pointer-enter on-pointer-enter
                 :on-pointer-leave on-pointer-leave
                 :on-pointer-down on-pointer-down
                 :on-pointer-up on-pointer-up
                 :on-lost-pointer-capture on-lost-pointer-capture
                 :on-mouse-move on-mouse-move
                 :style {:fill "none"
                         :pointer-events "fill"
                         :cursor (if (= axis :x) (cur/resize-ew 0) (cur/resize-ns 0))}}]))

     (when (:new-position @state)
       [:& guide {:guide {:axis axis
                          :position (:new-position @state)}
                  :get-hover-frame get-hover-frame
                  :vbox vbox
                  :zoom zoom
                  :hover? true
                  :hover-frame frame}])]))

(mf/defc viewport-guides
  {::mf/wrap [mf/memo]}
  [{:keys [zoom vbox hover-frame disabled-guides? modifiers]}]

  (let [page (mf/deref refs/workspace-page)

        guides (mf/use-memo
                (mf/deps page vbox)
                #(->> (get-in page [:options :guides] {})
                      (vals)
                      (filter (guide-inside-vbox? zoom vbox))))

        focus (mf/deref refs/workspace-focus-selected)

        hover-frame-ref (mf/use-ref nil)

        ;; We use the ref to not redraw every guide everytime the hovering frame change
        ;; we're only interested to get the frame in the guide we're moving
        get-hover-frame
        (mf/use-callback
         (fn []
           (mf/ref-val hover-frame-ref)))

        on-guide-change
        (mf/use-callback
         (mf/deps vbox)
         (fn [guide]
           (if (guide-inside-vbox? zoom vbox guide)
             (st/emit! (dw/update-guides guide))
             (st/emit! (dw/remove-guide guide)))))]

    (mf/use-effect
     (mf/deps hover-frame)
     (fn []
       (mf/set-ref-val! hover-frame-ref hover-frame)))

    [:g.guides {:pointer-events "none"}
     [:& new-guide-area {:vbox vbox
                         :zoom zoom
                         :axis :x
                         :get-hover-frame get-hover-frame
                         :disabled-guides? disabled-guides?}]

     [:& new-guide-area {:vbox vbox
                         :zoom zoom
                         :axis :y
                         :get-hover-frame get-hover-frame
                         :disabled-guides? disabled-guides?}]
     
     (for [current guides]
       (when (or (nil? (:frame-id current))
                 (empty? focus)
                 (contains? focus (:frame-id current)))
         [:& guide  {:key (str "guide-" (:id current))
                     :guide current
                     :vbox vbox
                     :zoom zoom
                     :frame-modifier (get modifiers (:frame-id current))
                     :get-hover-frame get-hover-frame
                     :on-guide-change on-guide-change
                     :disabled-guides? disabled-guides?}]))]))

