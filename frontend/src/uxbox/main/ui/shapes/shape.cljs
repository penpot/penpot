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
   [uxbox.main.ui.shapes.frame :as frame]))

(defn wrap-memo-shape
  ([component]
   (mf/memo'
    component
    (fn [np op]
      (let [n-shape (unchecked-get np "shape")
            o-shape (unchecked-get op "shape")]
        (= n-shape o-shape))))))

(declare group-wrapper)
(declare frame-wrapper)

(mf/defc shape-wrapper
  {::mf/wrap [wrap-memo-shape]}
  [{:keys [shape] :as props}]
  (when (and shape (not (:hidden shape)))
    (case (:type shape)
      :group [:& group-wrapper {:shape shape}]
      :curve [:& path/path-wrapper {:shape shape}]
      :text [:& text/text-wrapper {:shape shape}]
      :icon [:& icon/icon-wrapper {:shape shape}]
      :rect [:& rect/rect-wrapper {:shape shape}]
      :path [:& path/path-wrapper {:shape shape}]
      :image [:& image/image-wrapper {:shape shape}]
      :circle [:& circle/circle-wrapper {:shape shape}]
      :frame [:& frame-wrapper {:shape shape}]
      nil)))

(def group-wrapper (group/group-wrapper shape-wrapper))
(def frame-wrapper (frame/frame-wrapper shape-wrapper))
