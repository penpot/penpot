;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.main.ui.workspace.shapes.frame.dynamic-modifiers :as fdm]
   [app.main.ui.workspace.shapes.frame.node-store :as fns]
   [app.main.ui.workspace.shapes.frame.thumbnail-render :as ftr]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn frame-shape-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-shape-inner
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "fonts"]))]
       ::mf/wrap-props false
       ::mf/forward-ref true}
      [props ref]

      (let [shape         (unchecked-get props "shape")
            fonts         (unchecked-get props "fonts")
            childs-ref    (mf/use-memo (mf/deps (:id shape)) #(refs/children-objects (:id shape)))
            childs        (mf/deref childs-ref)]

        [:& (mf/provider embed/context) {:value true}
         [:& shape-container {:shape shape :ref ref}
          [:& ff/fontfaces-style {:fonts fonts}]
          [:& frame-shape {:shape shape :childs childs} ]]]))))

(defn check-props
  [new-props old-props]
  (and
       (= (unchecked-get new-props "thumbnail?") (unchecked-get old-props "thumbnail?"))
       (= (unchecked-get new-props "shape") (unchecked-get old-props "shape"))
       (= (unchecked-get new-props "objects") (unchecked-get old-props "objects"))))

(defn frame-wrapper-factory
  [shape-wrapper]

  (let [frame-shape (frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-props)]
       ::mf/wrap-props false}
      [props]

      (let [shape              (unchecked-get props "shape")
            thumbnail?         (unchecked-get props "thumbnail?")
            objects            (unchecked-get props "objects")

            fonts              (mf/use-memo (mf/deps shape objects) #(ff/frame->fonts shape objects))
            fonts              (-> fonts (hooks/use-equal-memo))

            force-render       (mf/use-state false)

            ;; Thumbnail data
            frame-id           (:id shape)
            thumbnail-data-ref (mf/use-memo (mf/deps frame-id) #(refs/thumbnail-frame-data frame-id))
            thumbnail-data     (mf/deref thumbnail-data-ref)
            thumbnail?         (and thumbnail? (or (some? (:thumbnail shape)) (some? thumbnail-data)))

            ;; References to the current rendered node and the its parentn
            node-ref           (mf/use-var nil)

            ;; when `true` we've called the mount for the frame
            rendered?          (mf/use-var false)

            ;; Modifiers
            modifiers-ref      (mf/use-memo (mf/deps frame-id) #(refs/workspace-modifiers-by-frame-id frame-id))
            modifiers          (mf/deref modifiers-ref)

            disable-thumbnail? (d/not-empty? (dm/get-in modifiers [(:id shape) :modifiers]))

            [on-load-frame-dom thumb-renderer]
            (ftr/use-render-thumbnail shape node-ref rendered? thumbnail? disable-thumbnail?)

            on-frame-load
            (fns/use-node-store thumbnail? node-ref rendered?)]

        (fdm/use-dynamic-modifiers objects @node-ref modifiers)

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
         (mf/deps shape fonts thumbnail? on-load-frame-dom @force-render)
         (fn []
           (when (and (some? @node-ref) (or @rendered? (not thumbnail?) @force-render))
             (mf/mount
              (mf/element frame-shape
                          #js {:ref on-load-frame-dom :shape shape :fonts fonts})

              @node-ref)
             (when (not @rendered?) (reset! rendered? true)))))

        [:g.frame-container {:key "frame-container" :ref on-frame-load}
         thumb-renderer

         [:g.frame-thumbnail
          [:> frame/frame-thumbnail {:key (dm/str (:id shape))
                                     :shape (cond-> shape
                                              (some? thumbnail-data)
                                              (assoc :thumbnail thumbnail-data))}]]]))))
