;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.pages :as cp]
   [uxbox.util.math :as mth]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.main.ui.shapes.group :as group]))

(def ^:private background-color "#E8E9EA") ;; $color-canvas

(mf/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill background-color}])

(defn- calculate-dimensions
  [data]
  (let [shapes (vals (:objects data))
        shape (geom/shapes->rect-shape shapes)
        width (+ (:x shape) (:width shape) 100)
        height (+ (:y shape) (:height shape) 100)]
    {:width (if (mth/nan? width) 100 width)
     :height (if (mth/nan? height) 100 height)}))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))
            shape (geom/transform-shape shape)]
        [:& frame-shape {:shape shape :childs childs}]))))

(defn group-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        group-shape   (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      [{:keys [shape frame] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        [:& group-shape {:frame frame
                         :shape shape
                         :childs childs}]))))

(defn shape-wrapper-factory
  [objects]
  (mf/fnc shape-wrapper
    [{:keys [frame shape] :as props}]
    (let [group-wrapper (mf/use-memo (mf/deps objects) #(group-wrapper-factory objects))]
      (when (and shape (not (:hidden shape)))
        (let [shape (geom/transform-shape frame shape)
              opts #js {:shape shape}]
          (case (:type shape)
            :curve [:> path/path-shape opts]
            :text [:> text/text-shape opts]
            :icon [:> icon/icon-shape opts]
            :rect [:> rect/rect-shape opts]
            :path [:> path/path-shape opts]
            :image [:> image/image-shape opts]
            :circle [:> circle/circle-shape opts]
            :group [:> group-wrapper {:shape shape :frame frame}]
            nil))))))

(mf/defc page-svg
  {::mf/wrap [mf/memo]}
  [{:keys [data] :as props}]
  (let [objects (:objects data)
        dim     (calculate-dimensions data)
        root    (get objects uuid/zero)
        shapes  (->> (:shapes root)
                     (map #(get objects %)))

        vbox    (str "0 0 " (:width dim 0) " " (:height dim 0))

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
     [:& background]
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

