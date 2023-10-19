;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.thumbnails :as thc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :refer [check-shape-props]]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as fdm]
   [app.util.debug :as dbg]
   [app.util.timers :as tm]
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

       (identical?
        (unchecked-get new-props "objects")
        (unchecked-get old-props "objects"))

       ^boolean
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

      (let [shape          (unchecked-get props "shape")
            thumbnail?     (unchecked-get props "thumbnail?")
            objects        (unchecked-get props "objects")

            file-id        (mf/use-ctx ctx/current-file-id)
            page-id        (mf/use-ctx ctx/current-page-id)
            frame-id       (dm/get-prop shape :id)

            container-ref  (mf/use-ref nil)
            content-ref    (mf/use-ref nil)

            ;; FIXME: apply specific rendering optimizations separating to a component
            bounds         (if (:show-content shape)
                             (let [ids      (cph/get-children-ids objects frame-id)
                                   children (sequence (keep (d/getf objects)) ids)]
                               (gsh/shapes->rect (cons shape children)))
                             (-> shape :points grc/points->rect))

            x              (dm/get-prop bounds :x)
            y              (dm/get-prop bounds :y)
            width          (dm/get-prop bounds :width)
            height         (dm/get-prop bounds :height)

            thumbnail-uri* (mf/with-memo [file-id page-id frame-id]
                             (let [object-id (thc/fmt-object-id file-id page-id frame-id "frame")]
                               (refs/workspace-thumbnail-by-id object-id)))
            thumbnail-uri (mf/deref thumbnail-uri*)

            modifiers-ref  (mf/with-memo [frame-id]
                             (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers      (mf/deref modifiers-ref)

            hidden?        (true? (:hidden shape))]

        ;; NOTE: we don't add deps because we want this to be executed
        ;; once on mount with only referenced the initial data
        (mf/with-effect []
          (when-not (some? thumbnail-uri)
            (tm/schedule-on-idle
             #(st/emit! (dwt/request-thumbnail file-id page-id frame-id "frame")))))

        (fdm/use-dynamic-modifiers objects (mf/ref-val content-ref) modifiers)

        [:& shape-container {:shape shape}
         [:g.frame-container
          {:id (dm/str "frame-container-" frame-id)
           :key "frame-container"
           :opacity (when ^boolean hidden? 0)}

          [:g.frame-imposter
           [:image.thumbnail-bitmap
            {:x x
             :y y
             :width width
             :height height
             :href thumbnail-uri
             :style {:display (when-not ^boolean thumbnail? "none")}}]

           ;; Render border around image when we are debugging
           ;; thumbnails.
           (when (dbg/enabled? :thumbnails)
             [:rect {:x (+ x 2)
                     :y (+ y 2)
                     :width (- width 4)
                     :height (- height 4)
                     :stroke "#f0f"
                     :stroke-width 2}])]

          ;; When thumbnail is disabled.
          (when (or (not ^boolean thumbnail?)
                    (not ^boolean thumbnail-uri))
            [:g.frame-content
             {:id (dm/str "frame-content-" frame-id)
              :ref container-ref}
             [:& frame-shape {:shape shape :ref content-ref}]])]]))))

