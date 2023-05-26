;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.measurements
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.formats :as fmt]
   [app.main.ui.workspace.viewport.viewport-ref :refer [point->viewport]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

(def font-size 11)
(def selection-rect-width 1)

(def select-color "var(--color-select)")
(def select-guide-width 1)
(def select-guide-dasharray 5)

(def hover-color "var(--color-distance)")
(def hover-color-text "var(--color-white)")
(def hover-guide-width 1)

(def size-display-color "var(--color-white)")
(def size-display-opacity 0.7)
(def size-display-text-color "var(--color-black)")
(def size-display-width-min 50)
(def size-display-width-max 75)
(def size-display-height 16)

(def distance-color "var(--color-distance)")
(def distance-text-color "var(--color-white)")
(def distance-border-radius 2)
(def distance-pill-width 50)
(def distance-pill-height 16)
(def distance-line-stroke 1)
(def warning-color "var(--color-warning)")
(def flex-display-pill-width 40)
(def flex-display-pill-height 20)
(def flex-display-pill-border-radius 4)

;; ------------------------------------------------
;; HELPERS
;; ------------------------------------------------

(defn bound->selrect [bounds]
  {:x (:x bounds)
   :y (:y bounds)
   :x1 (:x bounds)
   :y1 (:y bounds)
   :x2 (+ (:x bounds) (:width bounds))
   :y2 (+ (:y bounds) (:height bounds))
   :width (:width bounds)
   :height (:height bounds)})

(defn calculate-guides
  "Calculates coordinates for the selection guides"
  [bounds selrect]
  (let [{bounds-width :width bounds-height :height} bounds
        {:keys [x y width height]} selrect]
    [[(:x bounds) y (+ (:x bounds) bounds-width) y]
     [(:x bounds) (+ y height) (+ (:x bounds) bounds-width) (+ y height)]
     [x (:y bounds) x (+ (:y bounds) bounds-height)]
     [(+ x width) (:y bounds) (+ x width) (+ (:y bounds) bounds-height)]]))

(defn calculate-distance-lines
  "Given a start/end from two shapes gives the distance lines"
  [from-s from-e to-s to-e]
  (let [ss (- to-s from-s)
        se (- to-e from-s)
        es (- to-s from-e)
        ee (- to-e from-e)]
    (cond-> []
      (or (and (neg? ss) (pos? se))
          (and (pos? ss) (neg? ee))
          (and (neg? ss) (> ss se)))
      (conj [ from-s (+ from-s ss) ])

      (and (neg? se) (<= ss se))
      (conj [ from-s (+ from-s se) ])

      (and (pos? es) (<= es ee))
      (conj [ from-e (+ from-e es) ])

      (or (and (pos? ee) (neg? es))
          (and (neg? ee) (pos? ss))
          (and (pos? ee) (< ee es)))
      (conj [ from-e (+ from-e ee) ]))))

;; ------------------------------------------------
;; COMPONENTS
;; ------------------------------------------------

(mf/defc size-display [{:keys [selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        size-label (dm/str (fmt/format-number width) " x " (fmt/format-number height))

        rect-height (/ size-display-height zoom)
        rect-width (/ (if (<= (count size-label) 9)
                        size-display-width-min
                        size-display-width-max)
                      zoom)
        text-padding (/ 4 zoom)]
    [:g.size-display
     [:rect {:x (+ x (/ width 2) (- (/ rect-width 2)))
             :y (- (+ y height) rect-height)
             :width rect-width
             :height rect-height
             :style {:fill size-display-color
                     :fill-opacity size-display-opacity}}]

     [:text {:x (+ (+ x (/ width 2) (- (/ rect-width 2))) (/ rect-width 2))
             :y (- (+ y height (+ text-padding (/ rect-height 2))) rect-height)
             :width rect-width
             :height rect-height
             :text-anchor "middle"
             :style {:fill size-display-text-color
                     :font-size (/ font-size zoom)}}
      size-label]]))

(mf/defc distance-display-pill [{:keys [x y zoom distance bounds]}]
  (let [distance-pill-width (/ distance-pill-width zoom)
        distance-pill-height (/ distance-pill-height zoom)
        font-size (/ font-size zoom)
        text-padding (/ 3 zoom)
        distance-border-radius (/ distance-border-radius zoom)

        {bounds-width :width bounds-height :height} bounds

        rect-x (- x (/ distance-pill-width 2))
        rect-y (- y (/ distance-pill-height 2))

        text-x x
        text-y (+ y text-padding)

        offset-x (cond (< rect-x (:x bounds)) (- (:x bounds) rect-x)
                       (> (+ rect-x distance-pill-width) (+ (:x bounds) bounds-width))
                       (- (+ (:x bounds) bounds-width) (+ rect-x distance-pill-width))
                       :else 0)

        offset-y (cond (< rect-y (:y bounds)) (- (:y bounds) rect-y)
                       (> (+ rect-y distance-pill-height) (+ (:y bounds) bounds-height))
                       (- (+ (:y bounds) bounds-height) (+ rect-y distance-pill-height (/ distance-pill-height 2)))
                       :else 0)]
    [:g.distance-pill
     [:rect {:x (+ rect-x offset-x)
             :y (+ rect-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill distance-color}}]

     [:text {:x (+ text-x offset-x)
             :y (+ text-y offset-y)
             :rx distance-border-radius
             :ry distance-border-radius
             :text-anchor "middle"
             :width distance-pill-width
             :height distance-pill-height
             :style {:fill distance-text-color
                     :font-size font-size}}
      (fmt/format-pixels distance)]]))

(mf/defc selection-rect [{:keys [selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        selection-rect-width (/ selection-rect-width zoom)]
    [:g.selection-rect
     [:rect {:x x
             :y y
             :width width
             :height height
             :style {:fill "none"
                     :stroke hover-color
                     :stroke-width selection-rect-width}}]]))

(mf/defc distance-display [{:keys [from to zoom bounds]}]
  (let [fixed-x (if (gsh/fully-contained? from to)
                  (+ (:x to) (/ (:width to) 2))
                  (+ (:x from) (/ (:width from) 2)))
        fixed-y (if (gsh/fully-contained? from to)
                  (+ (:y to) (/ (:height to) 2))
                  (+ (:y from) (/ (:height from) 2)))

        v-lines (->> (calculate-distance-lines (:y1 from) (:y2 from) (:y1 to) (:y2 to))
                     (map (fn [[start end]] [fixed-x start fixed-x end])))

        h-lines (->> (calculate-distance-lines (:x1 from) (:x2 from) (:x1 to) (:x2 to))
                     (map (fn [[start end]] [start fixed-y end fixed-y])))

        lines   (d/concat-vec v-lines h-lines)

        distance-line-stroke (/ distance-line-stroke zoom)]

    (for [[x1 y1 x2 y2] lines]
      (let [center-x (+ x1 (/ (- x2 x1) 2))
            center-y (+ y1 (/ (- y2 y1) 2))
            distance (gpt/distance (gpt/point x1 y1) (gpt/point x2 y2))]
        (when-not (mth/almost-zero? distance)
          [:g.distance-line {:key (dm/str "line-" x1 "-" y1 "-" x2 "-" y2)}
           [:line
            {:x1 x1
             :y1 y1
             :x2 x2
             :y2 y2
             :style {:stroke distance-color
                     :stroke-width distance-line-stroke}}]

           [:& distance-display-pill
            {:x center-x
             :y center-y
             :zoom zoom
             :distance distance
             :bounds bounds}]])))))

(mf/defc selection-guides [{:keys [bounds selrect zoom]}]
  [:g.selection-guides
   (for [[idx [x1 y1 x2 y2]] (d/enumerate (calculate-guides bounds selrect))]
     [:line {:key (dm/str "guide-" idx)
             :x1 x1
             :y1 y1
             :x2 x2
             :y2 y2
             :style {:stroke select-color
                     :stroke-width (/ select-guide-width zoom)
                     :stroke-dasharray (/ select-guide-dasharray zoom)}}])])

(mf/defc measurement
  [{:keys [bounds frame selected-shapes hover-shape zoom]}]
  (let [selected-ids          (into #{} (map :id) selected-shapes)
        selected-selrect      (gsh/shapes->rect selected-shapes)
        hover-selrect         (-> hover-shape :points grc/points->rect)
        bounds-selrect        (bound->selrect bounds)
        hover-selected-shape? (not (contains? selected-ids (:id hover-shape)))]

    (when (seq selected-shapes)
      [:g.measurement-feedback {:pointer-events "none"}
       [:& selection-guides {:selrect selected-selrect
                             :bounds bounds
                             :zoom zoom}]
       [:& size-display {:selrect selected-selrect :zoom zoom}]

       (if (or (not hover-shape) (not hover-selected-shape?))
         (when (and frame (not= uuid/zero (:id frame)))
           (let [frame-bb (-> (:points frame) (grc/points->rect))]
             [:g.hover-shapes
              [:& selection-rect {:type :hover :selrect frame-bb :zoom zoom}]
              [:& distance-display {:from frame-bb
                                    :to selected-selrect
                                    :zoom zoom
                                    :bounds bounds-selrect}]]))

         [:g.hover-shapes
          [:& selection-rect {:type :hover :selrect hover-selrect :zoom zoom}]
          [:& size-display {:selrect hover-selrect :zoom zoom}]
          [:& distance-display {:from hover-selrect :to selected-selrect :zoom zoom :bounds bounds-selrect}]])])))



(mf/defc flex-display-pill [{:keys [x y width height font-size border-radius value color]}]
  [:g.distance-pill
   [:rect {:x x
           :y y
           :width width
           :height height
           :rx border-radius
           :ry border-radius
           :style {:fill color}}]

   [:text {:x (+ x (/ width 2))
           :y (+ y (/ height 2))
           :text-anchor "middle"
           :dominant-baseline "central"
           :style {:fill distance-text-color
                   :font-size font-size}}
    (fmt/format-number (or value 0))]])


(mf/defc padding-display [{:keys [frame-id zoom hover-all? hover-v? hover-h? padding-num padding on-pointer-enter on-pointer-leave
                                  rect-data hover? selected? mouse-pos hover-value]}]
  (let [resizing?            (mf/use-var false)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        negate?              (true? (:resize-negate? rect-data))
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-callback
         (mf/deps frame-id rect-data padding-num)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing? true)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        on-lost-pointer-capture
        (mf/use-callback
         (mf/deps frame-id padding-num padding)
         (fn [event]
           (dom/release-pointer event)
           (reset! resizing? false)
           (reset! start nil)
           (reset! original-value 0)
           (st/emit! (dwm/apply-modifiers))))

        on-pointer-move
        (mf/use-callback
         (mf/deps frame-id padding-num padding hover-all? hover-v? hover-h?)
         (fn [event]
           (let [pos (dom/get-client-position event)]
             (reset! mouse-pos (point->viewport pos))
             (when @resizing?
               (let [delta               (-> (gpt/to-vec @start pos)
                                             (cond-> negate? gpt/negate)
                                             (get axis))
                     val                 (int (max (+ @original-value (/ delta zoom)) 0))
                     layout-padding      (cond
                                           hover-all? (assoc padding :p1 val :p2 val :p3 val :p4 val)
                                           hover-v?   (assoc padding :p1 val :p3 val)
                                           hover-h?   (assoc padding :p2 val :p4 val)
                                           :else      (assoc padding padding-num val))


                     layout-padding-type (if (= (:p1 padding) (:p2 padding) (:p3 padding) (:p4 padding)) :simple :multiple)
                     modifiers           (dwm/create-modif-tree [frame-id]
                                                                (-> (ctm/empty)
                                                                    (ctm/change-property  :layout-padding layout-padding)
                                                                    (ctm/change-property  :layout-padding-type layout-padding-type)))]
                 (reset! hover-value val)
                 (st/emit! (dwm/set-modifiers modifiers)))))))]

    [:rect.padding-rect {:x (:x rect-data)
                         :y (:y rect-data)
                         :width (max 0 (:width rect-data))
                         :height (max 0 (:height rect-data))
                         :on-pointer-enter on-pointer-enter
                         :on-pointer-leave on-pointer-leave
                         :on-pointer-down on-pointer-down
                         :on-lost-pointer-capture on-lost-pointer-capture
                         :on-pointer-move on-pointer-move
                         :class (when (or hover? selected?)
                                  (if (= (:resize-axis rect-data) :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ew" 90)))
                         :style {:fill (if (or hover? selected?) distance-color "none")
                                 :opacity (if selected? 0.5 0.25)}}]))

(mf/defc padding-rects [{:keys [frame zoom alt? shift?]}]
  (let [frame-id                           (:id frame)
        paddings-selected                  (mf/deref refs/workspace-paddings-selected)
        hover-value                        (mf/use-var 0)
        mouse-pos                          (mf/use-var nil)
        hover                              (mf/use-var nil)
        hover-all?                         (and (not (nil? @hover)) alt?)
        hover-v?                           (and (or (= @hover :p1) (= @hover :p3)) shift?)
        hover-h?                           (and (or (= @hover :p2) (= @hover :p4)) shift?)
        padding                            (:layout-padding frame)
        {:keys [width height x1 x2 y1 y2]} (:selrect frame)
        on-pointer-enter                   (fn [hover-type val]
                                             (reset! hover hover-type)
                                             (reset! hover-value val))
        on-pointer-leave                   #(reset! hover nil)
        pill-width                         (/ flex-display-pill-width zoom)
        pill-height                        (/ flex-display-pill-height zoom)
        hover?                             #(or hover-all?
                                              (and (or (= % :p1) (= % :p3)) hover-v?)
                                              (and (or (= % :p2) (= % :p4)) hover-h?)
                                              (= @hover %))
        negate                             {:p1 (if (:flip-y frame) true false)
                                            :p2 (if (:flip-x frame) true false)
                                            :p3 (if (:flip-y frame) true false)
                                            :p4 (if (:flip-x frame) true false)}
        negate                             (cond-> negate
                                             (not= :auto (:layout-item-h-sizing frame)) (assoc :p2 (not (:p2 negate)))
                                             (not= :auto (:layout-item-v-sizing frame)) (assoc :p3 (not (:p3 negate))))

        padding-rect-data                  {:p1 {:key (str frame-id "-p1")
                                                 :x x1
                                                 :y (if (:flip-y frame) (- y2 (:p1 padding)) y1)
                                                 :width width
                                                 :height (:p1 padding)
                                                 :initial-value (:p1 padding)
                                                 :resize-type (if (:flip-y frame) :bottom :top)
                                                 :resize-axis :y
                                                 :resize-negate? (:p1 negate)}
                                            :p2 {:key (str frame-id "-p2")
                                                 :x (if (:flip-x frame) x1 (- x2 (:p2 padding)))
                                                 :y y1
                                                 :width (:p2 padding)
                                                 :height height
                                                 :initial-value (:p2 padding)
                                                 :resize-type :left
                                                 :resize-axis :x
                                                 :resize-negate? (:p2 negate)}
                                            :p3 {:key (str frame-id "-p3")
                                                 :x x1
                                                 :y (if (:flip-y frame) y1 (- y2 (:p3 padding)))
                                                 :width width
                                                 :height (:p3 padding)
                                                 :initial-value (:p3 padding)
                                                 :resize-type :bottom
                                                 :resize-axis :y
                                                 :resize-negate? (:p3 negate)}
                                            :p4 {:key (str frame-id "-p4")
                                                 :x (if (:flip-x frame) (- x2 (:p4 padding)) x1)
                                                 :y y1
                                                 :width (:p4 padding)
                                                 :height height
                                                 :initial-value (:p4 padding)
                                                 :resize-type (if (:flip-x frame) :right :left)
                                                 :resize-axis :x
                                                 :resize-negate? (:p4 negate)}}]

    [:g.paddings {:pointer-events "visible"}
     (for [[padding-num rect-data] padding-rect-data]
       [:& padding-display {:key (:key rect-data)
                            :frame-id frame-id
                            :zoom zoom
                            :hover-all? hover-all?
                            :hover-v? hover-v?
                            :hover-h? hover-h?
                            :padding padding
                            :mouse-pos mouse-pos
                            :hover-value hover-value
                            :padding-num padding-num
                            :on-pointer-enter (partial on-pointer-enter padding-num (get padding padding-num))
                            :on-pointer-leave on-pointer-leave
                            :hover?  (hover? padding-num)
                            :selected? (get paddings-selected padding-num)
                            :rect-data rect-data}])
     (when @hover
       [:& flex-display-pill {:height pill-height
                              :width pill-width
                              :font-size (/ font-size zoom)
                              :border-radius (/ flex-display-pill-border-radius zoom)
                              :color distance-color
                              :x (:x @mouse-pos)
                              :y (- (:y @mouse-pos) pill-width)
                              :value @hover-value}])]))

(mf/defc margin-display [{:keys [shape-id zoom hover-all? hover-v? hover-h? margin-num margin on-pointer-enter on-pointer-leave
                                  rect-data hover? selected? mouse-pos hover-value]}]
  (let [resizing?            (mf/use-var false)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        negate?              (true? (:resize-negate? rect-data))
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-callback
         (mf/deps shape-id margin-num margin)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing? true)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        on-lost-pointer-capture
        (mf/use-callback
         (mf/deps shape-id margin-num margin)
         (fn [event]
           (dom/release-pointer event)
           (reset! resizing? false)
           (reset! start nil)
           (reset! original-value 0)
           (st/emit! (dwm/apply-modifiers))))

        on-pointer-move
        (mf/use-callback
         (mf/deps shape-id margin-num margin hover-all? hover-v? hover-h?)
         (fn [event]
           (let [pos (dom/get-client-position event)]
             (reset! mouse-pos (point->viewport pos))
             (when @resizing?
               (let [delta          (-> (gpt/to-vec @start pos)
                                        (cond-> negate? gpt/negate)
                                        (get axis))
                     val            (int (max (+ @original-value (/ delta zoom)) 0))
                     layout-item-margin (cond
                                          hover-all? (assoc margin :m1 val :m2 val :m3 val :m4 val)
                                          hover-v?   (assoc margin :m1 val :m3 val)
                                          hover-h?   (assoc margin :m2 val :m4 val)
                                          :else      (assoc margin margin-num val))
                     layout-item-margin-type (if (= (:m1 margin) (:m2 margin) (:m3 margin) (:m4 margin)) :simple :multiple)
                     modifiers      (dwm/create-modif-tree [shape-id]
                                                           (-> (ctm/empty)
                                                               (ctm/change-property  :layout-item-margin layout-item-margin)
                                                               (ctm/change-property  :layout-item-margin-type layout-item-margin-type)))]
                 (reset! hover-value val)
                 (st/emit! (dwm/set-modifiers modifiers)))))))]

    [:rect.margin-rect {:x (:x rect-data)
                        :y (:y rect-data)
                        :width (:width rect-data)
                        :height (:height rect-data)
                        :on-pointer-enter on-pointer-enter
                        :on-pointer-leave on-pointer-leave
                        :on-pointer-down on-pointer-down
                        :on-lost-pointer-capture on-lost-pointer-capture
                        :on-pointer-move on-pointer-move
                        :class (when (or hover? selected?)
                                 (if (= (:resize-axis rect-data) :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ew" 90)))
                        :style {:fill (if (or hover? selected?) warning-color "none")
                                :opacity (if selected? 0.5 0.25)}}]))

(mf/defc margin-rects [{:keys [shape frame zoom alt? shift?]}]
  (let [shape-id                   (:id shape)
        pill-width                 (/ flex-display-pill-width zoom)
        pill-height                (/ flex-display-pill-height zoom)
        margins-selected           (mf/deref refs/workspace-margins-selected)
        hover-value                (mf/use-var 0)
        mouse-pos                  (mf/use-var nil)
        hover                      (mf/use-var nil)
        hover-all?                 (and (not (nil? @hover)) alt?)
        hover-v?                   (and (or (= @hover :m1) (= @hover :m3)) shift?)
        hover-h?                   (and (or (= @hover :m2) (= @hover :m4)) shift?)
        margin                    (:layout-item-margin shape)
        {:keys [width height x1 x2 y1 y2]} (:selrect shape)
        on-pointer-enter          (fn [hover-type val]
                                    (reset! hover hover-type)
                                    (reset! hover-value val))
        on-pointer-leave           #(reset! hover nil)
        hover? #(or hover-all?
                    (and (or (= % :m1) (= % :m3)) hover-v?)
                    (and (or (= % :m2) (= % :m4)) hover-h?)
                    (= @hover %))
        margin-display-data       {:m1 {:key (str shape-id "-m1")
                                        :x x1
                                        :y (if (:flip-y frame) y2 (- y1 (:m1 margin)))
                                        :width width
                                        :height (:m1 margin)
                                        :initial-value (:m1 margin)
                                        :resize-type :top
                                        :resize-axis :y
                                        :resize-negate? (:flip-y frame)}
                                   :m2 {:key (str shape-id "-m2")
                                        :x (if (:flip-x frame) (- x1 (:m2 margin)) x2)
                                        :y y1
                                        :width (:m2 margin)
                                        :height height
                                        :initial-value (:m2 margin)
                                        :resize-type :left
                                        :resize-axis :x
                                        :resize-negate? (:flip-x frame)}
                                   :m3 {:key (str shape-id "-m3")
                                        :x x1
                                        :y (if (:flip-y frame) (- y1 (:m3 margin)) y2)
                                        :width width
                                        :height (:m3 margin)
                                        :initial-value (:m3 margin)
                                        :resize-type :top
                                        :resize-axis :y
                                        :resize-negate? (:flip-y frame)}
                                   :m4 {:key (str shape-id "-m4")
                                        :x (if (:flip-x frame) x2 (- x1 (:m4 margin)))
                                        :y y1
                                        :width (:m4 margin)
                                        :height height
                                        :initial-value (:m4 margin)
                                        :resize-type :left
                                        :resize-axis :x
                                        :resize-negate? (:flip-x frame)}}]

    [:g.margins {:pointer-events "visible"}
    (for [[margin-num rect-data] margin-display-data]
       [:& margin-display
        {:key (:key rect-data)
         :shape-id shape-id
         :zoom zoom
         :hover-all? hover-all?
         :hover-v? hover-v?
         :hover-h? hover-h?
         :margin-num margin-num
         :margin margin
         :on-pointer-enter (partial on-pointer-enter margin-num (get margin margin-num))
         :on-pointer-leave on-pointer-leave
         :rect-data rect-data
         :hover?  (hover? margin-num)
         :selected? (get margins-selected margin-num)
         :mouse-pos mouse-pos
         :hover-value hover-value}])

     (when @hover
       [:& flex-display-pill {:height pill-height
                              :width pill-width
                              :font-size (/ font-size zoom)
                              :border-radius (/ flex-display-pill-border-radius zoom)
                              :color warning-color
                              :x (:x @mouse-pos)
                              :y (- (:y @mouse-pos) pill-width)
                              :value @hover-value}])]))

(mf/defc gap-display [{:keys [frame-id zoom gap-type gap on-pointer-enter on-pointer-leave
                                  rect-data hover? selected? mouse-pos hover-value]}]
  (let [resizing             (mf/use-var nil)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        negate?              (:resize-negate? rect-data)
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-callback
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing gap-type)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        on-lost-pointer-capture
        (mf/use-callback
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (dom/release-pointer event)
           (reset! resizing nil)
           (reset! start nil)
           (reset! original-value 0)
           (st/emit! (dwm/apply-modifiers))))

        on-pointer-move
        (mf/use-callback
         (mf/deps frame-id gap-type gap)
         (fn [event]
           (let [pos (dom/get-client-position event)]
             (reset! mouse-pos (point->viewport pos))
             (when (= @resizing gap-type)
               (let [delta      (-> (gpt/to-vec @start pos)
                                    (cond-> negate? gpt/negate)
                                    (get axis))
                     val            (int (max (+ @original-value (/ delta zoom)) 0))
                     layout-gap (assoc gap gap-type val)
                     modifiers  (dwm/create-modif-tree [frame-id] (ctm/change-property (ctm/empty) :layout-gap layout-gap))]

                 (reset! hover-value val)
                 (st/emit! (dwm/set-modifiers modifiers)))))))]

     [:rect.gap-rect {:x (:x rect-data)
                      :y (:y rect-data)
                      :width (:width rect-data)
                      :height (:height rect-data)
                      :on-pointer-enter on-pointer-enter
                      :on-pointer-leave on-pointer-leave
                      :on-pointer-down on-pointer-down
                      :on-lost-pointer-capture on-lost-pointer-capture
                      :on-pointer-move on-pointer-move
                      :class (when (or hover? selected?)
                               (if (= (:resize-axis rect-data) :x) (cur/get-dynamic "resize-ew" 0) (cur/get-dynamic "resize-ew" 90)))
                      :style {:fill (if (or hover? selected?) distance-color "none")
                              :opacity (if selected? 0.5 0.25)}}]))

(mf/defc gap-rects [{:keys [frame zoom]}]
  (let [frame-id                   (:id frame)
        saved-dir                  (:layout-flex-dir frame)
        is-col?                    (or (= :column saved-dir) (= :column-reverse saved-dir))
        flip-x                     (:flip-x frame)
        flip-y                     (:flip-y frame)
        pill-width                 (/ flex-display-pill-width zoom)
        pill-height                (/ flex-display-pill-height zoom)
        workspace-modifiers        (mf/deref refs/workspace-modifiers)
        gap-selected               (mf/deref refs/workspace-gap-selected)
        hover                      (mf/use-var nil)
        hover-value                (mf/use-var 0)
        mouse-pos                  (mf/use-var nil)
        padding                    (:layout-padding frame)
        gap                        (:layout-gap frame)
        {:keys [width height x1 y1]} (:selrect frame)
        on-pointer-enter           (fn [hover-type val]
                                     (reset! hover hover-type)
                                     (reset! hover-value val))

        on-pointer-leave           #(reset! hover nil)
        negate                     {:column-gap (if flip-x true false)
                                    :row-gap (if flip-y true false)}

        objects                    (wsh/lookup-page-objects @st/state)
        children              (->> (cph/get-immediate-children objects frame-id)
                                   (remove :layout-item-absolute)
                                   (remove :hidden))

        children-to-display (if (or (= :row-reverse saved-dir)
                                    (= :column-reverse saved-dir))
                              (drop-last children)
                              (rest children))
        children-to-display (->> children-to-display
                                 (map #(gsh/transform-shape % (get-in workspace-modifiers [(:id %) :modifiers]))))

        wrap-blocks
        (let [block-children (->> children
                                  (map #(vector (gpo/parent-coords-bounds (:points %) (:points frame)) %)))
              layout-data (gsl/calc-layout-data frame block-children (:points frame))
              layout-bounds (:layout-bounds layout-data)
              xv   #(gpo/start-hv layout-bounds %)
              yv   #(gpo/start-vv layout-bounds %)]
          (for [{:keys [start-p line-width line-height layout-gap-row layout-gap-col num-children]} (:layout-lines layout-data)]
            (let [line-width (if is-col? line-width (+ line-width (* (dec num-children) layout-gap-row)))
                  line-height (if is-col? (+ line-height (* (dec num-children) layout-gap-col)) line-height)
                  end-p (-> start-p (gpt/add (xv line-width)) (gpt/add (yv line-height)))]
              {:x1 (min (:x start-p) (:x end-p))
               :y1 (min (:y start-p) (:y end-p))
               :x2 (max (:x start-p) (:x end-p))
               :y2 (max (:y start-p) (:y end-p))})))

        block-contains
        (fn [x y block]
          (if is-col?
            (<= (:x1 block) x (:x2 block))
            (<= (:y1 block) y (:y2 block))))

        get-container-block
        (fn [shape]
          (let [selrect (:selrect shape)
                x (/ (+ (:x1 selrect) (:x2 selrect)) 2)
                y (/ (+ (:y1 selrect) (:y2 selrect)) 2)]
            (->> wrap-blocks
                 (filter #(block-contains x y %))
                 first)))

        create-cgdd
        (fn [shape]
          (let [block  (get-container-block shape)
                x (if flip-x
                    (- (:x1 (:selrect shape))
                       (get-in shape [:layout-item-margin :m2])
                       (:column-gap gap))
                    (+ (:x2 (:selrect shape)) (get-in shape [:layout-item-margin :m2])))
                y (:y1 block)
                h (- (:y2 block) (:y1 block))]
            {:x x
             :y y
             :height h
             :width (:column-gap gap)
             :initial-value (:column-gap gap)
             :resize-type :left
             :resize-axis :x
             :resize-negate? (:column-gap negate)
             :gap-type (if is-col? :row-gap :column-gap)}))

        create-cgdd-block
        (fn [block]
          (let [x (if flip-x
                    (- (:x1 block) (:column-gap gap))
                    (:x2 block))
                y (if flip-y
                    (+ y1 (:p3 padding))
                    (+ y1 (:p1 padding)))
                h (- height (+ (:p1 padding) (:p3 padding)))]
            {:x x
             :y y
             :width (:column-gap gap)
             :height h
             :initial-value (:column-gap gap)
             :resize-type :left
             :resize-axis :x
             :resize-negate? (:column-gap negate)
             :gap-type (if is-col? :column-gap :row-gap)}))

        create-rgdd
        (fn [shape]
          (let [block  (get-container-block shape)
                x (:x1 block)
                y (if flip-y
                    (- (:y1 (:selrect shape))
                       (get-in shape [:layout-item-margin :m3])
                       (:row-gap gap))
                    (+ (:y2 (:selrect shape)) (get-in shape [:layout-item-margin :m3])))
                w (- (:x2 block) (:x1 block))]
            {:x x
             :y y
             :width w
             :height (:row-gap gap)
             :initial-value (:row-gap gap)
             :resize-type :bottom
             :resize-axis :y
             :resize-negate? (:row-gap negate)
             :gap-type (if is-col? :row-gap :column-gap)}))

        create-rgdd-block
        (fn [block]
          (let [x (if flip-x
                    (+ x1 (:p2 padding))
                    (+ x1 (:p4 padding)))
                y (if flip-y
                    (- (:y1 block) (:row-gap gap))
                    (:y2 block))
                w (- width (+ (:p2 padding) (:p4 padding)))]
            {:x x
             :y y
             :width w
             :height (:row-gap gap)
             :initial-value (:row-gap gap)
             :resize-type :bottom
             :resize-axis :y
             :resize-negate? (:row-gap negate)
             :gap-type (if is-col? :column-gap :row-gap)}))

        display-blocks (if is-col?
                         (->> (drop-last wrap-blocks)
                              (map create-cgdd-block))
                         (->> (drop-last wrap-blocks)
                              (map create-rgdd-block)))

        display-children (if is-col?
                           (->> children-to-display
                                (map create-rgdd))
                           (->> children-to-display
                                (map create-cgdd)))]

    [:g.gaps {:pointer-events "visible"}
     [:*
      (for [[index display-item] (d/enumerate (concat display-blocks display-children))]
        (let [gap-type (:gap-type display-item)]
          [:& gap-display {:key (str frame-id index)
                           :frame-id frame-id
                           :zoom zoom
                           :gap-type gap-type
                           :gap gap
                           :on-pointer-enter (partial on-pointer-enter gap-type (get gap gap-type))
                           :on-pointer-leave on-pointer-leave
                           :rect-data display-item
                           :hover?    (= @hover gap-type)
                           :selected? (= gap-selected gap-type)
                           :mouse-pos mouse-pos
                           :hover-value hover-value}]))]

     (when @hover
       [:& flex-display-pill {:height pill-height
                              :width pill-width
                              :font-size (/ font-size zoom)
                              :border-radius (/ flex-display-pill-border-radius zoom)
                              :color distance-color
                              :x (:x @mouse-pos)
                              :y (- (:y @mouse-pos) pill-width)
                              :value @hover-value}])]))

(mf/defc padding
  [{:keys [frame zoom alt? shift?]}]
  (when frame
    [:g.measurement-gaps {:pointer-events "none"}
     [:g.hover-shapes
      [:& padding-rects {:frame frame :zoom zoom :alt? alt? :shift? shift?}]]]))

(mf/defc gap
  [{:keys [frame zoom]}]
  (when frame
    [:g.measurement-gaps {:pointer-events "none"}
     [:g.hover-shapes
      [:& gap-rects {:frame frame :zoom zoom}]]]))

(mf/defc margin
  [{:keys [shape parent zoom alt? shift?]}]
  (when shape
    [:g.measurement-gaps {:pointer-events "none"}
     [:g.hover-shapes
      [:& margin-rects {:shape shape :frame parent :zoom zoom :alt? alt? :shift? shift?}]]]))


