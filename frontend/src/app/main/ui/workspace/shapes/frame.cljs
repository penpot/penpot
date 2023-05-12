;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as fdm]
   [app.main.ui.workspace.shapes.frame.node-store :as fns]
   [app.main.ui.workspace.shapes.frame.thumbnail-render :as ftr]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(defn frame-shape-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-shape-inner
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
       ::mf/wrap-props false
       ::mf/forward-ref true}
      [props ref]

      (let [shape      (unchecked-get props "shape")
            childs-ref (mf/use-memo (mf/deps (:id shape)) #(refs/children-objects (:id shape)))
            childs     (mf/deref childs-ref)]

        [:& (mf/provider embed/context) {:value true}
         [:& shape-container {:shape shape :ref ref :disable-shadows? (cph/root-frame? shape)}
          [:& frame-shape {:shape shape :childs childs} ]]]))))

(defn check-props
  [new-props old-props]
  (and (= (unchecked-get new-props "thumbnail?")
          (unchecked-get old-props "thumbnail?"))
       (= (unchecked-get new-props "shape")
          (unchecked-get old-props "shape"))))

(defn nested-frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape         (unchecked-get props "shape")
            frame-id      (:id shape)
            objects       (wsh/lookup-page-objects @st/state)
            node-ref      (mf/use-var nil)
            modifiers-ref (mf/use-memo (mf/deps frame-id) #(refs/workspace-modifiers-by-frame-id frame-id))
            modifiers     (mf/deref modifiers-ref)]

        (fdm/use-dynamic-modifiers objects @node-ref modifiers)
        (let [shape (unchecked-get props "shape")]
          [:& frame-shape {:shape shape :ref node-ref}])))))

(defn root-frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape              (unchecked-get props "shape")
            thumbnail?         (unchecked-get props "thumbnail?")

            page-id            (mf/use-ctx ctx/current-page-id)
            frame-id           (:id shape)

            objects            (wsh/lookup-page-objects @st/state)

            node*              (mf/use-var nil)
            force-render*      (mf/use-state false)
            force-render?      (deref force-render*)

            ;; when `true` we've called the mount for the frame
            rendered*          (mf/use-var false)

            modifiers-ref      (mf/with-memo [frame-id]
                                 (refs/workspace-modifiers-by-frame-id frame-id))
            modifiers          (mf/deref modifiers-ref)


            fonts              (mf/with-memo [shape objects]
                                 (ff/shape->fonts shape objects))
            fonts              (hooks/use-equal-memo fonts)

            disable-thumbnail? (d/not-empty? (dm/get-in modifiers [frame-id :modifiers]))

            [on-load-frame-dom render-frame? thumbnail-renderer]
            (ftr/use-render-thumbnail page-id shape node* rendered* disable-thumbnail? force-render?)

            on-frame-load
            (fns/use-node-store thumbnail? node* rendered* render-frame?)]

        (fdm/use-dynamic-modifiers objects @node* modifiers)

        (mf/with-effect []
          ;; When a change in the data is received a "force-render" event is emitted
          ;; that will force the component to be mounted in memory
          (let [sub (->> (dwt/force-render-stream frame-id)
                         (rx/take-while #(not @rendered*))
                         (rx/subs #(reset! force-render* true)))]
            #(some-> sub rx/dispose!)))

        (mf/with-effect [shape fonts thumbnail? on-load-frame-dom force-render? render-frame?]
          (when (and (some? @node*)
                     (or @rendered*
                         (not thumbnail?)
                         force-render?
                         render-frame?))
            (let [elem (mf/element frame-shape #js {:ref on-load-frame-dom :shape shape :fonts fonts})]
              (mf/mount elem @node*)
              (when (not @rendered*)
                (reset! rendered* true)))))

        [:& shape-container {:shape shape}
         [:g.frame-container {:id (dm/str "frame-container-" frame-id)
                              :key "frame-container"
                              :ref on-frame-load
                              :opacity (when (:hidden shape) 0)}
          [:& ff/fontfaces-style {:fonts fonts}]
          [:g.frame-thumbnail-wrapper
           {:id (dm/str "thumbnail-container-" frame-id)
            ;; Hide the thumbnail when not displaying
            :opacity (when-not thumbnail? 0)}
           thumbnail-renderer]]

         ]))))

