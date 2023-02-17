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
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.formats :as fmt]
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
(def padding-pill-width 40)
(def padding-pill-height 20)

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
        selected-selrect      (gsh/selection-rect selected-shapes)
        hover-selrect         (-> hover-shape :points gsh/points->selrect)
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
           (let [frame-bb (-> (:points frame) (gsh/points->selrect))]
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



(mf/defc padding-display-pill [{:keys [x y width height font-size value]}]
  [:g.distance-pill
   [:rect {:x x
           :y y
           :width width
           :height height
           :style {:fill distance-color}}]

   [:text {:x (+ x (/ width 2))
           :y (+ y (/ height 2))
           :text-anchor "middle"
           :text-align "center"
           :dominant-baseline "central"
           :style {:fill distance-text-color
                   :font-size font-size}}
    (fmt/format-number value)]])


(mf/defc padding-display [{:keys [frame-id zoom hover-all? hover-v? hover-h? padding-num padding on-mouse-enter on-mouse-leave
                                  rect-data pill-data hover? selected?]}]
  (let [resizing?            (mf/use-var false)
        start                (mf/use-var nil)
        original-value       (mf/use-var 0)
        negate?              (:resize-negate? rect-data)
        axis                 (:resize-axis rect-data)

        on-pointer-down
        (mf/use-callback
         (mf/deps frame-id padding-num padding)
         (fn [event]
           (dom/capture-pointer event)
           (reset! resizing? true)
           (reset! start (dom/get-client-position event))
           (reset! original-value (:initial-value rect-data))))

        on-lost-pointer-capture
        (mf/use-callback
         (mf/deps frame-id padding-num padding)
         (fn [event]
           (let [padding-type (if (and (= (:p1 padding) (:p3 padding)) (= (:p2 padding) (:p4 padding))) :simple :multiple)]
             (dom/release-pointer event)
             (reset! resizing? false)
             (reset! start nil)
             (reset! original-value 0)
             (st/emit! (dwm/apply-modifiers)
                       (dwsl/update-layout [frame-id] {:layout-padding-type padding-type})))))

        on-mouse-move
        (mf/use-callback
         (mf/deps frame-id padding-num padding)
         (fn [event]
           (when @resizing?
             (let [pos            (dom/get-client-position event)
                   delta          (-> (gpt/to-vec @start pos)
                                      (cond-> negate? gpt/negate)
                                      (get axis))
                   val            (int (max (+ @original-value (/ delta zoom)) 0))
                   layout-padding (cond
                                    hover-all? (assoc padding :p1 val :p2 val :p3 val :p4 val)
                                    hover-v?   (assoc padding :p1 val :p3 val)
                                    hover-h?   (assoc padding :p2 val :p4 val)
                                    :else      (assoc padding padding-num val))
                   modifiers      (dwm/create-modif-tree [frame-id] (ctm/change-property (ctm/empty) :layout-padding layout-padding))]
               (st/emit! (dwm/set-modifiers modifiers))))))]


    [:*
     [:rect.padding-rect {:x (:x rect-data)
                          :y (:y rect-data)
                          :width (:width rect-data)
                          :height (:height rect-data)
                          :on-mouse-enter on-mouse-enter
                          :on-mouse-leave on-mouse-leave
                          :on-pointer-down on-pointer-down
                          :on-lost-pointer-capture on-lost-pointer-capture
                          :on-mouse-move on-mouse-move
                          :style {:fill (if (or hover? selected?) distance-color "none")
                                  :cursor (when (or hover? selected?)
                                            (if (= (:resize-axis rect-data) :x) (cur/resize-ew 0) (cur/resize-ew 90)))
                                  :opacity (if selected? 0.5 0.25)}}]
     (when (or hover? selected?)
       [:& padding-display-pill pill-data])]))

(mf/defc padding-rects [{:keys [frame zoom alt? shift?]}]
  (let [frame-id                   (:id frame)
        paddings-selected          (mf/deref refs/workspace-paddings-selected)
        hover                      (mf/use-var nil)
        hover-all?                 (and (not (nil? @hover)) alt?)
        hover-v?                   (and (or (= @hover :p1) (= @hover :p3)) shift?)
        hover-h?                   (and (or (= @hover :p2) (= @hover :p4)) shift?)
        padding                    (:layout-padding frame)
        [width height x1 x2 y1 y2] ((juxt :width :height :x1 :x2 :y1 :y2) (:selrect frame))

        pill-width                 (/ padding-pill-width zoom)
        pill-height                (/ padding-pill-height zoom)
        pill-separation            (/ pill-width 2)
        pill-data                  {:height pill-height
                                    :width pill-width
                                    :font-size (/ font-size zoom)}
        on-mouse-enter             #(reset! hover %)
        on-mouse-leave             #(reset! hover nil)
        negate                     {:p1 (if (:flip-y frame) true false)
                                    :p2 (if (:flip-x frame) true false)
                                    :p3 (if (:flip-y frame) true false)
                                    :p4 (if (:flip-x frame) true false)}
        negate                     (cond-> negate
                                     (= :fix (:layout-item-h-sizing frame)) (assoc :p2 (not (:p2 negate)))
                                     (= :fix (:layout-item-v-sizing frame)) (assoc :p3 (not (:p3 negate))))

        padding-display-data       [{:frame-id frame-id
                                     :zoom zoom
                                     :hover-all? hover-all?
                                     :hover-v? hover-v?
                                     :hover-h? hover-h?
                                     :padding-num :p1
                                     :padding padding
                                     :on-mouse-enter (partial on-mouse-enter :p1)
                                     :on-mouse-leave on-mouse-leave
                                     :rect-data {:x x1
                                                 :y (if (:flip-y frame) (- y2 (:p1 padding)) y1)
                                                 :width width
                                                 :height (:p1 padding)
                                                 :initial-value (:p1 padding)
                                                 :resize-type (if (:flip-y frame) :bottom :top)
                                                 :resize-axis :y
                                                 :resize-negate? (:p1 negate)}
                                     :pill-data (assoc pill-data
                                                       :x (- x1 (* pill-width 1.5))
                                                       :y (if (:flip-y frame)
                                                            (+ (- y2 (:p1 padding)) (/ (- (:p1 padding) pill-height) 2))
                                                            (+ y1 (/ (- (:p1 padding) pill-height) 2)))
                                                       :value (:p1 padding))
                                     :hover?  (or hover-all? hover-v? (= @hover :p1))
                                     :selected? (:p1 paddings-selected)}

                                    {:frame-id frame-id
                                     :zoom zoom
                                     :hover-all? hover-all?
                                     :hover-v? hover-v?
                                     :hover-h? hover-h?
                                     :padding-num :p2
                                     :padding padding
                                     :on-mouse-enter (partial on-mouse-enter :p2)
                                     :on-mouse-leave on-mouse-leave
                                     :rect-data {:x (if (:flip-x frame) x1 (- x2 (:p2 padding)))
                                                 :y y1
                                                 :width (:p2 padding)
                                                 :height height
                                                 :initial-value (:p2 padding)
                                                 :resize-type :left
                                                 :resize-axis :x
                                                 :resize-negate? (:p2 negate)}
                                     :pill-data (assoc pill-data
                                                       :x (if (:flip-x frame)
                                                            (+ x1 (/ (- (:p2 padding) pill-width) 2))
                                                            (+ (- x2 (:p2 padding)) (/ (- (:p2 padding) pill-width) 2)))
                                                       :y (- y1 (+ pill-height pill-separation))
                                                       :value (:p2 padding))
                                     :hover?  (or hover-all? hover-h? (= @hover :p2))
                                     :selected? (:p2 paddings-selected)}

                                    {:frame-id frame-id
                                     :zoom zoom
                                     :hover-all? hover-all?
                                     :hover-v? hover-v?
                                     :hover-h? hover-h?
                                     :padding-num :p3
                                     :padding padding
                                     :on-mouse-enter (partial on-mouse-enter :p3)
                                     :on-mouse-leave on-mouse-leave
                                     :rect-data {:x x1
                                                 :y (if (:flip-y frame) y1 (- y2 (:p3 padding)))
                                                 :width width
                                                 :height (:p3 padding)
                                                 :initial-value (:p3 padding)
                                                 :resize-type :bottom
                                                 :resize-axis :y
                                                 :resize-negate? (:p3 negate)}
                                     :pill-data (assoc pill-data
                                                       :x (- x1 (+ pill-width pill-separation))
                                                       :y (if (:flip-y frame)
                                                            (+ y1 (/ (- (:p3 padding) pill-height) 2))
                                                            (+ (- y2 (:p3 padding)) (/ (- (:p3 padding) pill-height) 2)))
                                                       :value (:p3 padding))
                                     :hover?  (or hover-all? hover-v? (= @hover :p3))
                                     :selected? (:p3 paddings-selected)}

                                    {:frame-id frame-id
                                     :zoom zoom
                                     :hover-all? hover-all?
                                     :hover-v? hover-v?
                                     :hover-h? hover-h?
                                     :padding-num :p4
                                     :padding padding
                                     :on-mouse-enter (partial on-mouse-enter :p4)
                                     :on-mouse-leave on-mouse-leave
                                     :rect-data {:x (if (:flip-x frame) (- x2 (:p4 padding)) x1)
                                                 :y y1
                                                 :width (:p4 padding)
                                                 :height height
                                                 :initial-value (:p4 padding)
                                                 :resize-type (if (:flip-x frame) :right :left)
                                                 :resize-axis :x
                                                 :resize-negate? (:p4 negate)}
                                     :pill-data (assoc pill-data
                                                       :x (if (:flip-x frame)
                                                            (+ (- x2 (:p4 padding)) (/ (- (:p4 padding) pill-width) 2))
                                                            (+ x1 (/ (- (:p4 padding) pill-width) 2)))
                                                       :y (- y1 (+ pill-height pill-separation))
                                                       :value (:p4 padding))
                                     :hover?  (or hover-all? hover-h? (= @hover :p4))
                                     :selected? (:p4 paddings-selected)}]]

    [:g.paddings {:pointer-events "visible"}
     (for [data padding-display-data]
       [:& padding-display data])]))


(mf/defc padding
  [{:keys [frame zoom paddings-selected alt? shift?]}]
  (when frame
      [:g.measurement-gaps {:pointer-events "none"}
       [:g.hover-shapes
        [:& padding-rects {:frame frame :zoom zoom :alt? alt? :shift? shift? :paddings-selected paddings-selected}]]]))


