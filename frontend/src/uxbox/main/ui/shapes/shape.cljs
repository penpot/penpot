;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.shape
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.main.ui.shapes.group :as group]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.main.refs :as refs]))

(defn- shape-wrapper-memo-equals?
  [np op]
  (let [n-shape (unchecked-get np "shape")
        o-shape (unchecked-get op "shape")
        n-frame (unchecked-get np "frame")
        o-frame (unchecked-get op "frame")]
    ;; (prn "shape-wrapper-memo-equals?" (identical? n-frame o-frame))
    (if (= (:type n-shape) :group)
      false
      (and (identical? n-shape o-shape)
           (identical? n-frame o-frame)))))

(declare group-wrapper)
(declare frame-wrapper)

(mf/defc shape-wrapper
  {::mf/wrap [#(mf/memo' % shape-wrapper-memo-equals?)]
   ::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        frame (unchecked-get props "frame")
        opts #js {:shape (->> shape (gsh/transform-shape frame))
                  :frame frame}]
    (when (and shape (not (:hidden shape)))
      [:*
       (case (:type shape)
         :group [:> group-wrapper opts]
         :curve [:> path/path-wrapper opts]
         :text [:> text/text-wrapper opts]
         :icon [:> icon/icon-wrapper opts]
         :rect [:> rect/rect-wrapper opts]
         :path [:> path/path-wrapper opts]
         :image [:> image/image-wrapper opts]
         :circle [:> circle/circle-wrapper opts]

         ;; Only used when drawing a new frame.
         :frame [:> frame-wrapper {:shape shape}]
         nil)
       [:& bounding-box {:shape shape :frame frame}]])))

(def group-wrapper (group/group-wrapper shape-wrapper))
(def frame-wrapper (frame/frame-wrapper shape-wrapper))
