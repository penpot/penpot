;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes
  "A workspace specific shapes wrappers.

  Shapes that has some peculiarities are defined in its own
  namespace under app.ui.workspace.shapes.* prefix, all the
  others are defined using a generic wrapper implemented in
  common."
  (:require
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.ui.context :as ctx]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.main.ui.workspace.shapes.bool :as bool]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.ui.workspace.shapes.frame :as frame]
   [app.main.ui.workspace.shapes.group :as group]
   [app.main.ui.workspace.shapes.path :as path]
   [app.main.ui.workspace.shapes.svg-raw :as svg-raw]
   [app.main.ui.workspace.shapes.text :as text]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(declare shape-wrapper)
(declare group-wrapper)
(declare svg-raw-wrapper)
(declare bool-wrapper)
(declare root-frame-wrapper)
(declare nested-frame-wrapper)

(def circle-wrapper (common/generic-wrapper-factory circle/circle-shape))
(def image-wrapper (common/generic-wrapper-factory image/image-shape))
(def rect-wrapper (common/generic-wrapper-factory rect/rect-shape))

(mf/defc root-shape
  "Draws the root shape of the viewport and recursively all the shapes"
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [objects       (obj/get props "objects")
        active-frames (obj/get props "active-frames")
        shapes        (cph/get-immediate-children objects)

        ;; We group the objects together per frame-id so if an object of a different
        ;; frame changes won't affect the rendering frame
        frame-objects
        (mf/use-memo
         (mf/deps objects)
         #(cph/objects-by-frame objects))]

    [:g {:id (dm/str "shape-" uuid/zero)}
     [:& (mf/provider ctx/active-frames) {:value active-frames}
      ;; Render font faces only for shapes that are part of the root
      ;; frame but don't belongs to any other frame.
      (let [xform (comp
                   (remove cph/frame-shape?)
                   (mapcat #(cph/get-children-with-self objects (:id %))))]
        [:& ff/fontfaces-style {:shapes (into [] xform shapes)}])

      [:g.frame-children
       (for [shape shapes]
         [:g.ws-shape-wrapper {:key (dm/str (dm/get-prop shape :id))}
          (if (not ^boolean (cph/frame-shape? shape))
            [:& shape-wrapper
             {:shape shape}]
            (if ^boolean (cph/is-direct-child-of-root? shape)
              [:& root-frame-wrapper
               {:shape shape
                :objects (get frame-objects (dm/get-prop shape :id))
                :thumbnail? (not (contains? active-frames (dm/get-prop shape :id)))}]
              [:& nested-frame-wrapper
               {:shape shape
                :objects (get frame-objects (dm/get-prop shape :id))}]))])]]]))

(defn- check-shape-wrapper-props
  [np op]
  (frame/check-shape (unchecked-get np "shape")
                     (unchecked-get op "shape")))

(mf/defc shape-wrapper
  {::mf/wrap [#(mf/memo' % check-shape-wrapper-props)]
   ::mf/wrap-props false}
  [props]
  (let [shape      (unchecked-get props "shape")
        shape-type (dm/get-prop shape :type)
        shape-id   (dm/get-prop shape :id)

        ;; FIXME: WARN: this breaks react rule of hooks (hooks can't be under conditional)
        active-frames
        (when (cph/is-direct-child-of-root? shape)
          (mf/use-ctx ctx/active-frames))

        thumbnail?
        (and (some? active-frames)
             (not (contains? active-frames shape-id)))

        opts  #js {:shape shape :thumbnail? thumbnail?}

        [wrapper wrapper-props]
        (if (= :svg-raw shape-type)
          [mf/Fragment nil]
          ["g" #js {:className "workspace-shape-wrapper"}])]

    (when (and (some? shape)
               (not ^boolean (:hidden shape)))
      [:> wrapper wrapper-props
       (case shape-type
         :path    [:> path/path-wrapper opts]
         :text    [:> text/text-wrapper opts]
         :group   [:> group-wrapper opts]
         :rect    [:> rect-wrapper opts]
         :image   [:> image-wrapper opts]
         :circle  [:> circle-wrapper opts]
         :svg-raw [:> svg-raw-wrapper opts]
         :bool    [:> bool-wrapper opts]
         :frame   [:> nested-frame-wrapper opts]

         nil)])))

(def group-wrapper (group/group-wrapper-factory shape-wrapper))
(def svg-raw-wrapper (svg-raw/svg-raw-wrapper-factory shape-wrapper))
(def bool-wrapper (bool/bool-wrapper-factory shape-wrapper))
(def root-frame-wrapper (frame/root-frame-wrapper-factory shape-wrapper))
(def nested-frame-wrapper (frame/nested-frame-wrapper-factory shape-wrapper))

