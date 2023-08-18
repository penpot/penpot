;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.shapes.path.common :as pc]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.path.tools :as upt]
   [rumext.v2 :as mf]))

(defn check-enabled [content selected-points]
  (let [segments (upt/get-segments content selected-points)
        num-segments (count segments)
        num-points (count selected-points)
        points-selected? (seq selected-points)
        segments-selected? (seq segments)
        ;; max segments for n points is (n × (n -1)) / 2
        max-segments (-> num-points
                         (* (- num-points 1))
                         (/ 2))
        is-curve? (some #(upt/is-curve? content %) selected-points)]

    {:make-corner (and points-selected? is-curve?)
     :make-curve (and points-selected? (not is-curve?))
     :add-node segments-selected?
     :remove-node points-selected?
     :merge-nodes segments-selected?
     :join-nodes (and points-selected? (>= num-points 2) (< num-segments max-segments))
     :separate-nodes segments-selected?}))

(mf/defc path-actions [{:keys [shape]}]
  (let [{:keys [edit-mode selected-points snap-toggled] :as all} (mf/deref pc/current-edit-path-ref)
        content (:content shape)
        new-css-system (mf/use-ctx ctx/new-css-system)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selected-points)
         #(check-enabled content selected-points))

        on-select-draw-mode
        (mf/use-callback
         (fn [_]
           (st/emit! (drp/change-edit-mode :draw))))

        on-select-edit-mode
        (mf/use-callback
         (fn [_]
           (st/emit! (drp/change-edit-mode :move))))

        on-add-node
        (mf/use-callback
         (mf/deps (:add-node enabled-buttons))
         (fn [_]
           (when (:add-node enabled-buttons)
             (st/emit! (drp/add-node)))))

        on-remove-node
        (mf/use-callback
         (mf/deps (:remove-node enabled-buttons))
         (fn [_]
           (when (:remove-node enabled-buttons)
             (st/emit! (drp/remove-node)))))

        on-merge-nodes
        (mf/use-callback
         (mf/deps (:merge-nodes enabled-buttons))
         (fn [_]
           (when (:merge-nodes enabled-buttons)
             (st/emit! (drp/merge-nodes)))))

        on-join-nodes
        (mf/use-callback
         (mf/deps (:join-nodes enabled-buttons))
         (fn [_]
           (when (:join-nodes enabled-buttons)
             (st/emit! (drp/join-nodes)))))

        on-separate-nodes
        (mf/use-callback
         (mf/deps (:separate-nodes enabled-buttons))
         (fn [_]
           (when (:separate-nodes enabled-buttons)
             (st/emit! (drp/separate-nodes)))))

        on-make-corner
        (mf/use-callback
         (mf/deps (:make-corner enabled-buttons))
         (fn [_]
           (when (:make-corner enabled-buttons)
             (st/emit! (drp/make-corner)))))

        on-make-curve
        (mf/use-callback
         (mf/deps (:make-curve enabled-buttons))
         (fn [_]
           (when (:make-curve enabled-buttons)
             (st/emit! (drp/make-curve)))))

        on-toggle-snap
        (mf/use-callback
         (fn [_]
           (st/emit! (drp/toggle-snap))))]

    (if new-css-system
      [:div {:class (stl/css :sub-actions)}
       [:div {:class (stl/css :sub-actions-group)}

      ;; Draw Mode
        [:button
         {:class  (stl/css-case :is-toggled (= edit-mode :draw))
          :alt (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
          :on-click on-select-draw-mode}
         i/pentool-refactor]

      ;; Edit mode
        [:button
         {:class (stl/css-case :is-toggled (= edit-mode :move))
          :alt (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
          :on-click on-select-edit-mode}
         i/move-refactor]]

       [:div {:class (stl/css :sub-actions-group)}

      ;; Add Node
        [:button
         {:class  (stl/css-case :is-disabled (:add-node enabled-buttons))
          :alt (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
          :on-click on-add-node}
         i/add-refactor]

      ;; Remove node
        [:button
         {:class (stl/css-case :is-disabled (:remove-node enabled-buttons))
          :alt (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
          :on-click on-remove-node}
         i/remove-refactor]]

       [:div {:class (stl/css :sub-actions-group)}
      ;; Merge Nodes
        [:button
         {:class (stl/css-case :is-disabled (:merge-nodes enabled-buttons))
          :alt (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
          :on-click on-merge-nodes}
         i/merge-nodes-refactor]

      ;; Join Nodes
        [:button
         {:class (stl/css-case :is-disabled (:join-nodes enabled-buttons))
          :alt (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
          :on-click on-join-nodes}
         i/join-nodes-refactor]

      ;; Separate Nodes
        [:button
         {:class (stl/css-case :is-disabled (:separate-nodes enabled-buttons))
          :alt (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
          :on-click on-separate-nodes}
         i/separate-nodes-refactor]]

     ;; Make Corner
       [:div {:class (stl/css :sub-actions-group)}
        [:button
         {:class (stl/css-case :is-disabled (:make-corner enabled-buttons))
          :alt (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
          :on-click on-make-corner}
         i/to-corner-refactor]

      ;; Make Curve
        [:button
         {:class (stl/css-case :is-disabled (:make-curve enabled-buttons))
          :alt (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
          :on-click on-make-curve}
         i/to-curve-refactor]]

     ;; Toggle snap
       [:div {:class (stl/css :sub-actions-group)}
        [:button
         {:class  (stl/css-case :is-toggled snap-toggled)
          :alt (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
          :on-click on-toggle-snap}
         i/snap-nodes-refactor]]]



      [:div.path-actions
       [:div.viewport-actions-group

      ;; Draw Mode
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when (= edit-mode :draw) "is-toggled")
          :alt (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
          :on-click on-select-draw-mode}
         i/pen]

      ;; Edit mode
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when (= edit-mode :move) "is-toggled")
          :alt (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
          :on-click on-select-edit-mode}
         i/pointer-inner]]

       [:div.viewport-actions-group
      ;; Add Node
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:add-node enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
          :on-click on-add-node}
         i/nodes-add]

      ;; Remove node
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:remove-node enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
          :on-click on-remove-node}
         i/nodes-remove]]

       [:div.viewport-actions-group
      ;; Merge Nodes
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:merge-nodes enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
          :on-click on-merge-nodes}
         i/nodes-merge]

      ;; Join Nodes
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:join-nodes enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
          :on-click on-join-nodes}
         i/nodes-join]

      ;; Separate Nodes
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:separate-nodes enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
          :on-click on-separate-nodes}
         i/nodes-separate]]

     ;; Make Corner
       [:div.viewport-actions-group
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:make-corner enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
          :on-click on-make-corner}
         i/nodes-corner]

      ;; Make Curve
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when-not (:make-curve enabled-buttons) "is-disabled")
          :alt (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
          :on-click on-make-curve}
         i/nodes-curve]]

     ;; Toggle snap
       [:div.viewport-actions-group
        [:div.viewport-actions-entry.tooltip.tooltip-bottom
         {:class (when snap-toggled "is-toggled")
          :alt (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
          :on-click on-toggle-snap}
         i/nodes-snap]]])))

