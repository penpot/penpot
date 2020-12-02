;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes
  "A workspace specific shapes wrappers.

  Shapes that has some peculiarities are defined in its own
  namespace under app.ui.workspace.shapes.* prefix, all the
  others are defined using a generic wrapper implemented in
  common."
  (:require
   [app.common.geom.shapes :as geom]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.workspace.shapes.bounding-box :refer [bounding-box]]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.ui.workspace.shapes.frame :as frame]
   [app.main.ui.workspace.shapes.group :as group]
   [app.main.ui.workspace.shapes.path :as path]
   [app.main.ui.workspace.shapes.text :as text]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(declare group-wrapper)
(declare frame-wrapper)

(def circle-wrapper (common/generic-wrapper-factory circle/circle-shape))
(def image-wrapper (common/generic-wrapper-factory image/image-shape))
(def rect-wrapper (common/generic-wrapper-factory rect/rect-shape))

(defn- shape-wrapper-memo-equals?
  [np op]
  (let [n-shape (obj/get np "shape")
        o-shape (obj/get op "shape")
        n-frame (obj/get np "frame")
        o-frame (obj/get op "frame")]
    ;; (prn "shape-wrapper-memo-equals?" (identical? n-frame o-frame))
    (if (= (:type n-shape) :group)
      false
      (and (identical? n-shape o-shape)
           (identical? n-frame o-frame)))))

(defn make-is-moving-ref
  [id]
  (fn []
    (let [check-moving (fn [local]
                         (and (= :move (:transform local))
                              (contains? (:selected local) id)))]
      (l/derived check-moving refs/workspace-local))))

(mf/defc shape-wrapper
  {::mf/wrap [#(mf/memo' % shape-wrapper-memo-equals?)]
   ::mf/wrap-props false}
  [props]
  (let [shape  (obj/get props "shape")
        frame  (obj/get props "frame")
        ghost? (obj/get props "ghost?")
        shape  (-> (geom/transform-shape shape)
                   (geom/translate-to-frame frame))
        opts  #js {:shape shape
                   :frame frame}

        alt?   (hooks/use-rxsub ms/keyboard-alt)

        moving-iref (mf/use-memo (mf/deps (:id shape)) (make-is-moving-ref (:id shape)))
        moving?     (mf/deref moving-iref)]

    (when (and shape
               (or ghost? (not moving?))
               (not (:hidden shape)))
      [:g.shape-wrapper {:style {:cursor (if alt? cur/duplicate nil)}}
       (case (:type shape)
         :path [:> path/path-wrapper opts]
         :text [:> text/text-wrapper opts]
         :group [:> group-wrapper opts]
         :rect [:> rect-wrapper opts]
         :image [:> image-wrapper opts]
         :circle [:> circle-wrapper opts]

         ;; Only used when drawing a new frame.
         :frame [:> frame-wrapper {:shape shape}]
         nil)
       [:& bounding-box {:shape shape :frame frame}]])))

(def group-wrapper (group/group-wrapper-factory shape-wrapper))
(def frame-wrapper (frame/frame-wrapper-factory shape-wrapper))

