;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.viewer.frame-viewer
  "The main container for a frame in viewer mode"
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.math :as mth]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.common.pages :as cp]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.main.ui.shapes.group :as group]))

(declare shape-wrapper)

(defn frame-wrapper
  [objects]
  (mf/fnc frame-wrapper
    [{:keys [shape] :as props}]
    (let [childs (mapv #(get objects %)
                       (:shapes shape))
          shape-wrapper (mf/use-memo (mf/deps objects)
                                     #(shape-wrapper objects))
          frame-shape   (mf/use-memo (mf/deps objects)
                                     #(frame/frame-shape shape-wrapper))
          shape (geom/transform-shape shape)]
      [:& frame-shape {:shape shape :childs childs}])))

(defn group-wrapper
  [objects]
  (mf/fnc group-wrapper
    [{:keys [shape frame] :as props}]
    (let [children (mapv #(get objects %)
                         (:shapes shape))
          shape-wrapper (mf/use-memo (mf/deps objects)
                                     #(shape-wrapper objects))
          group-shape   (mf/use-memo (mf/deps objects)
                                     #(group/group-shape shape-wrapper))]
      [:& group-shape {:frame frame
                       :shape shape
                       :children children}])))

(defn shape-wrapper
  [objects]
  (mf/fnc shape-wrapper
    [{:keys [frame shape] :as props}]
    (let [group-wrapper (mf/use-memo (mf/deps objects) #(group-wrapper objects))]
      (when (and shape (not (:hidden shape)))
        (let [shape (geom/transform-shape frame shape)
              opts #js {:shape shape}]
          (case (:type shape)
            :curve [:> path/path-viewer-wrapper opts]
            :text [:> text/text-viewer-wrapper opts]
            :icon [:> icon/icon-viewer-wrapper opts]
            :rect [:> rect/rect-viewer-wrapper opts]
            :path [:> path/path-viewer-wrapper opts]
            :image [:> image/image-viewer-wrapper opts]
            :circle [:> circle/circle-viewer-wrapper opts]
            :group [:> group-wrapper {:shape shape :frame frame}]
            nil))))))

(mf/defc frame-viewer-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        frame-id (:id frame)
        modifier-ids (concat [frame-id] (cp/get-children frame-id objects))

        update-fn (fn [state shape-id]
                    (-> state
                        (assoc-in [shape-id :modifiers :displacement] modifier)))
        objects (reduce update-fn objects modifier-ids)
        frame (assoc-in frame [:modifiers :displacement] modifier )

        width (* (:width frame) zoom)
        height (* (:height frame) zoom)
        vbox (str "0 0 " (:width frame 0) " " (:height frame 0))
        frame-wrapper (mf/use-memo (mf/deps objects)
                                   #(frame-wrapper objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     [:& frame-wrapper {:shape frame
                        :view-box vbox}]]))

