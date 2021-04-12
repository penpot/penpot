;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.exports
  "The main logic for SVG export functionality."
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.uuid :as uuid]
   [app.common.pages :as cp]
   [app.common.math :as mth]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.util.timers :as ts]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.shape :refer [shape-container]]))

(def ^:private default-color "#E8E9EA") ;; $color-canvas

(mf/defc background
  [{:keys [vbox color]}]
  [:rect
   {:x (:x vbox)
    :y (:y vbox)
    :width (:width vbox)
    :height (:height vbox)
    :fill color}])

(defn- calculate-dimensions
  [{:keys [objects] :as data} vport]
  (let [shapes (cp/select-toplevel-shapes objects {:include-frames? true})
        to-finite (fn [val fallback] (if (not (mth/finite? val)) fallback val))
        rect (->> (gsh/selection-rect shapes)
                  (gal/adjust-to-viewport vport))]
    (-> rect
        (update :x to-finite 0)
        (update :y to-finite 0)
        (update :width to-finite 100000)
        (update :height to-finite 100000))))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))
            shape  (gsh/transform-shape shape)]
        [:> shape-container {:shape shape}
         [:& frame-shape {:shape shape :childs childs}]]))))

(defn group-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        group-shape   (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      [{:keys [shape frame] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        [:& group-shape {:frame frame
                         :shape shape
                         :is-child-selected? true
                         :childs childs}]))))

(defn svg-raw-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        svg-raw-shape   (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      [{:keys [shape frame] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        [:& svg-raw-shape {:frame frame
                         :shape shape
                         :childs childs}]))))

(defn shape-wrapper-factory
  [objects]
  (mf/fnc shape-wrapper
    [{:keys [frame shape] :as props}]
    (let [group-wrapper (mf/use-memo (mf/deps objects) #(group-wrapper-factory objects))
          svg-raw-wrapper (mf/use-memo (mf/deps objects) #(svg-raw-wrapper-factory objects))
          frame-wrapper (mf/use-memo (mf/deps objects) #(frame-wrapper-factory objects))]
      (when (and shape (not (:hidden shape)))
        (let [shape (-> (gsh/transform-shape shape)
                        (gsh/translate-to-frame frame))
              opts #js {:shape shape}
              svg-element? (and (= :svg-raw (:type shape))
                                (not= :svg (get-in shape [:content :tag])))]
          (if-not svg-element?
            [:> shape-container {:shape shape}
             (case (:type shape)
               :text    [:> text/text-shape opts]
               :rect    [:> rect/rect-shape opts]
               :path    [:> path/path-shape opts]
               :image   [:> image/image-shape opts]
               :circle  [:> circle/circle-shape opts]
               :frame   [:> frame-wrapper {:shape shape}]
               :group   [:> group-wrapper {:shape shape :frame frame}]
               :svg-raw [:> svg-raw-wrapper {:shape shape :frame frame}]
               nil)]

            ;; Don't wrap svg elements inside a <g> otherwise some can break
            [:> svg-raw-wrapper {:shape shape :frame frame}]))))))

(defn get-viewbox [{:keys [x y width height] :or {x 0 y 0 width 100 height 100}}]
  (str/fmt "%s %s %s %s" x y width height))

(mf/defc page-svg
  {::mf/wrap [mf/memo]}
  [{:keys [data width height] :as props}]
  (let [objects (:objects data)
        root    (get objects uuid/zero)
        shapes  (->> (:shapes root)
                     (map #(get objects %)))

        vport   {:width width :height height}
        dim     (calculate-dimensions data vport)
        vbox    (get-viewbox dim)
        background-color (get-in data [:options :background] default-color)
        frame-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(frame-wrapper-factory objects))

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(shape-wrapper-factory objects))]
    [:svg {:view-box vbox
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& background {:vbox dim :color background-color}]
     (for [item shapes]
       (if (= (:type item) :frame)
         [:& frame-wrapper {:shape item
                            :key (:id item)}]
         [:& shape-wrapper {:shape item
                            :key (:id item)}]))]))

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        frame-id (:id frame)

        modifier-ids (concat [frame-id] (cp/get-children frame-id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        frame (assoc-in frame [:modifiers :displacement] modifier)

        width  (* (:width frame) zoom)
        height (* (:height frame) zoom)
        vbox   (str "0 0 " (:width frame 0)
                    " "    (:height frame 0))
        wrapper (mf/use-memo
                 (mf/deps objects)
                 #(frame-wrapper-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape frame :view-box vbox}]]))

(mf/defc component-svg
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [objects group zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x group) (:y group))
                     (gpt/negate)
                     (gmt/translate-matrix))

        group-id (:id group)

        modifier-ids (concat [group-id] (cp/get-children group-id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        group (assoc-in group [:modifiers :displacement] modifier)

        width  (* (:width group) zoom)
        height (* (:height group) zoom)
        vbox   (str "0 0 " (:width group 0)
                    " "    (:height group 0))
        wrapper (mf/use-memo
                  (mf/deps objects)
                  #(group-wrapper-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& wrapper {:shape group :view-box vbox}]]))

