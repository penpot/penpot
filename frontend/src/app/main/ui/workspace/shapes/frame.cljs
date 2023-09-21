;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :refer [check-shape-props]]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as fdm]
   [app.util.debug :as dbg]
   [rumext.v2 :as mf]))

(defn frame-shape-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-shape-inner
      {::mf/wrap [#(mf/memo' % check-shape-props)]
       ::mf/wrap-props false
       ::mf/forward-ref true}
      [props ref]

      (let [shape      (unchecked-get props "shape")
            shape-id   (dm/get-prop shape :id)

            childs-ref (mf/with-memo [shape-id]
                         (refs/children-objects shape-id))
            childs     (mf/deref childs-ref)]

        [:& (mf/provider embed/context) {:value true}
         [:& shape-container {:shape shape :ref ref :disable-shadows? (cph/is-direct-child-of-root? shape)}
          [:& frame-shape {:shape shape :childs childs}]]]))))

(defn check-props
  [new-props old-props]
  (and (= (unchecked-get new-props "thumbnail?")
          (unchecked-get old-props "thumbnail?"))
       (check-shape-props new-props old-props)))

(defn nested-frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            objects    (wsh/lookup-page-objects @st/state)

            frame-id   (dm/get-prop shape :id)

            node-ref   (mf/use-ref nil)
            modifiers* (mf/with-memo [frame-id]
                         (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers  (mf/deref modifiers*)]

        (fdm/use-dynamic-modifiers objects (mf/ref-val node-ref) modifiers)
        [:& frame-shape {:shape shape :ref node-ref}]))))


(defn root-frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape              (unchecked-get props "shape")
            thumbnail?         (unchecked-get props "thumbnail?")

            ;; page-id            (mf/use-ctx ctx/current-page-id)
            frame-id           (:id shape)

            objects            (wsh/lookup-page-objects @st/state)

            container-ref      (mf/use-ref nil)
            content-ref        (mf/use-ref nil)

            all-children-ref   (mf/with-memo [frame-id]
                                 (refs/all-children-objects frame-id))
            all-children       (mf/deref all-children-ref)

            bounds
            (if (:show-content shape)
              (gsh/shapes->rect (cons shape all-children))
              (-> shape :points grc/points->rect))

            x                  (dm/get-prop bounds :x)
            y                  (dm/get-prop bounds :y)
            width              (dm/get-prop bounds :width)
            height             (dm/get-prop bounds :height)

            thumbnail-uri*     (mf/with-memo [frame-id]
                                 (refs/thumbnail-frame-data frame-id))
            thumbnail-uri      (mf/deref thumbnail-uri*)

            modifiers-ref      (mf/with-memo [frame-id]
                                 (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers          (mf/deref modifiers-ref)

            debug?             (dbg/enabled? :thumbnails)]

        (when-not (some? thumbnail-uri)
          (st/emit! (dwt/update-thumbnail frame-id)))

        (fdm/use-dynamic-modifiers objects (mf/ref-val content-ref) modifiers)

        [:& shape-container {:shape shape}
         [:g.frame-container
          {:id (dm/str "frame-container-" frame-id)
           :key "frame-container"
           ;; :ref on-container-ref
           :opacity (when (:hidden shape) 0)}

          ;; When thumbnail is enabled.
          [:g.frame-imposter
           ;; Render thumbnail image.
           [:image.thumbnail-bitmap
            {;; :ref on-imposter-ref
             :x x
             :y y
             :width width
             :height height
             :href thumbnail-uri
             :style {:display (when-not thumbnail? "none")}}]

           ;; Render border around image when we are debugging
           ;; thumbnails.
           (when ^boolean debug?
             [:rect {:x (+ x 2)
                     :y (+ y 2)
                     :width (- width 4)
                     :height (- height 4)
                     :stroke "#f0f"
                     :stroke-width 2}])]

          ;; When thumbnail is disabled.
          (when-not thumbnail?
            [:g.frame-content
             {:id (dm/str "frame-content-" frame-id)
              :ref container-ref}
             [:& frame-shape {:shape shape :ref content-ref}]])]]))))
