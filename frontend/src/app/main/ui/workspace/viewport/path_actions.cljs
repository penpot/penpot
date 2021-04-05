;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.path-actions
  (:require
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as wph]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.shapes.path.common :as pc]
   [app.util.geom.path :as ugp]
   [rumext.alpha :as mf]))

(defn check-enabled [content selected-points]
  (let [segments (ugp/get-segments content selected-points)

        points-selected? (not (empty? selected-points))
        segments-selected? (not (empty? segments))]
    {:make-corner points-selected?
     :make-curve points-selected?
     :add-node segments-selected?
     :remove-node points-selected?
     :merge-nodes segments-selected?
     :join-nodes segments-selected?
     :separate-nodes segments-selected?}))

(mf/defc path-actions [{:keys [shape]}]
  (let [id (mf/deref refs/selected-edition)
        {:keys [edit-mode selected-points snap-toggled] :as all} (mf/deref pc/current-edit-path-ref)
        content (:content shape)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selected-points)
         #(check-enabled content selected-points))

        on-select-draw-mode
        (mf/use-callback
         (fn [event]
           (st/emit! (drp/change-edit-mode :draw))))
        
        on-select-edit-mode
        (mf/use-callback
         (fn [event]
           (st/emit! (drp/change-edit-mode :move))))
        
        on-add-node
        (mf/use-callback
         (mf/deps (:add-node enabled-buttons))
         (fn [event]
           (when (:add-node enabled-buttons)
             (st/emit! (drp/add-node)))))
        
        on-remove-node
        (mf/use-callback
         (mf/deps (:remove-node enabled-buttons))
         (fn [event]
           (when (:remove-node enabled-buttons)
             (st/emit! (drp/remove-node)))))
        
        on-merge-nodes
        (mf/use-callback
         (mf/deps (:merge-nodes enabled-buttons))
         (fn [event]
           (when (:merge-nodes enabled-buttons)
             (st/emit! (drp/merge-nodes)))))
        
        on-join-nodes
        (mf/use-callback
         (mf/deps (:join-nodes enabled-buttons))
         (fn [event]
           (when (:join-nodes enabled-buttons)
             (st/emit! (drp/join-nodes)))))
        
        on-separate-nodes
        (mf/use-callback
         (mf/deps (:separate-nodes enabled-buttons))
         (fn [event]
           (when (:separate-nodes enabled-buttons)
             (st/emit! (drp/separate-nodes)))))

        on-make-corner
        (mf/use-callback
         (mf/deps (:make-corner enabled-buttons))
         (fn [event]
           (when (:make-corner enabled-buttons)
             (st/emit! (drp/make-corner)))))
        
        on-make-curve
        (mf/use-callback
         (mf/deps (:make-curve enabled-buttons))
         (fn [event]
           (when (:make-curve enabled-buttons)
             (st/emit! (drp/make-curve)))))

        on-toggle-snap
        (mf/use-callback
         (fn [event]
           (st/emit! (drp/toggle-snap))))

        ]
    [:div.path-actions
     [:div.viewport-actions-group

      ;; Draw Mode
      [:div.viewport-actions-entry
       {:class (when (= edit-mode :draw) "is-toggled")
        :on-click on-select-draw-mode}
       i/pen]

      ;; Edit mode
      [:div.viewport-actions-entry
       {:class (when (= edit-mode :move) "is-toggled")
        :on-click on-select-edit-mode}
       i/pointer-inner]]
     
     [:div.viewport-actions-group
      ;; Add Node
      [:div.viewport-actions-entry
       {:class (when-not (:add-node enabled-buttons) "is-disabled")
        :on-click on-add-node}
       i/nodes-add]

      ;; Remove node
      [:div.viewport-actions-entry
       {:class (when-not (:remove-node enabled-buttons) "is-disabled")
        :on-click on-remove-node}
       i/nodes-remove]]

     [:div.viewport-actions-group
      ;; Merge Nodes
      [:div.viewport-actions-entry
       {:class (when-not (:merge-nodes enabled-buttons) "is-disabled")
        :on-click on-merge-nodes}
       i/nodes-merge]

      ;; Join Nodes
      [:div.viewport-actions-entry
       {:class (when-not (:join-nodes enabled-buttons) "is-disabled")
        :on-click on-join-nodes}
       i/nodes-join]

      ;; Separate Nodes
      [:div.viewport-actions-entry
       {:class (when-not (:separate-nodes enabled-buttons) "is-disabled")
        :on-click on-separate-nodes}
       i/nodes-separate]]

     ;; Make Corner
     [:div.viewport-actions-group
      [:div.viewport-actions-entry
       {:class (when-not (:make-corner enabled-buttons) "is-disabled")
        :on-click on-make-corner}
       i/nodes-corner]

      ;; Make Curve
      [:div.viewport-actions-entry
       {:class (when-not (:make-curve enabled-buttons) "is-disabled")
        :on-click on-make-curve}
       i/nodes-curve]]

     ;; Toggle snap
     [:div.viewport-actions-group
      [:div.viewport-actions-entry
       {:class (when snap-toggled "is-toggled")
        :on-click on-toggle-snap}
       i/nodes-snap]]]))
