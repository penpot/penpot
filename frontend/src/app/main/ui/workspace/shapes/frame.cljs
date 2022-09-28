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
   [app.main.fonts :as fonts]
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
  (and
       (= (unchecked-get new-props "thumbnail?") (unchecked-get old-props "thumbnail?"))
       (= (unchecked-get new-props "shape") (unchecked-get old-props "shape"))))

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

      (let [shape         (unchecked-get props "shape")
            frame-id      (:id shape)
            objects       (wsh/lookup-page-objects @st/state)
            node-ref      (mf/use-var nil)
            modifiers-ref (mf/use-memo (mf/deps frame-id) #(refs/workspace-modifiers-by-frame-id frame-id))
            modifiers     (mf/deref modifiers-ref)]

        (fdm/use-dynamic-modifiers objects @node-ref modifiers)

        (let [thumbnail?         (unchecked-get props "thumbnail?")
              fonts              (mf/use-memo (mf/deps shape objects) #(ff/shape->fonts shape objects))
              fonts              (-> fonts (hooks/use-equal-memo))

              force-render       (mf/use-state false)

              ;; Thumbnail data
              page-id            (mf/use-ctx ctx/current-page-id)

              ;; when `true` we've called the mount for the frame
              rendered?          (mf/use-var false)

              disable-thumbnail? (d/not-empty? (dm/get-in modifiers [(:id shape) :modifiers]))

              [on-load-frame-dom render-frame? thumbnail-renderer]
              (ftr/use-render-thumbnail page-id shape node-ref rendered? disable-thumbnail? @force-render)

              on-frame-load
              (fns/use-node-store thumbnail? node-ref rendered? render-frame?)]

          (mf/use-effect
           (mf/deps fonts)
           (fn []
             (->> (rx/from fonts)
                  (rx/merge-map fonts/fetch-font-css)
                  (rx/ignore))))

          (mf/use-effect
           (fn []
             ;; When a change in the data is received a "force-render" event is emited
             ;; that will force the component to be mounted in memory
             (let [sub
                   (->> (dwt/force-render-stream (:id shape))
                        (rx/take-while #(not @rendered?))
                        (rx/subs #(reset! force-render true)))]
               #(when sub
                  (rx/dispose! sub)))))

          (mf/use-effect
           (mf/deps shape fonts thumbnail? on-load-frame-dom @force-render render-frame?)
           (fn []
             (when (and (some? @node-ref) (or @rendered? (not thumbnail?) @force-render render-frame?))
               (mf/mount
                (mf/element frame-shape
                            #js {:ref on-load-frame-dom :shape shape :fonts fonts})

                @node-ref)
               (when (not @rendered?) (reset! rendered? true)))))

          [:& shape-container {:shape shape}
           [:g.frame-container {:id (dm/str "frame-container-" (:id shape))
                                :key "frame-container"
                                :ref on-frame-load
                                :opacity (when (:hidden shape) 0)}
            [:& ff/fontfaces-style {:fonts fonts}]
            [:g.frame-thumbnail-wrapper
             {:id (dm/str "thumbnail-container-" (:id shape))
              ;; Hide the thumbnail when not displaying
              :opacity (when (and @rendered? (not thumbnail?) (not render-frame?)) 0)}
             thumbnail-renderer]]])))))
