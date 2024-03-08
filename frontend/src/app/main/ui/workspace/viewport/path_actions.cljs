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

    [:div {:class (stl/css :sub-actions)}
     [:div {:class (stl/css :sub-actions-group)}

            ;; Draw Mode
      [:button
       {:class  (stl/css-case :is-toggled (= edit-mode :draw))
        :title (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
        :on-click on-select-draw-mode}
       i/pentool]

            ;; Edit mode
      [:button
       {:class (stl/css-case :is-toggled (= edit-mode :move))
        :title (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
        :on-click on-select-edit-mode}
       i/move]]

     [:div {:class (stl/css :sub-actions-group)}
            ;; Add Node
      [:button
       {:disabled (not (:add-node enabled-buttons))
        :title (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
        :on-click on-add-node}
       i/add]

            ;; Remove node
      [:button
       {:disabled (not (:remove-node enabled-buttons))
        :title (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
        :on-click on-remove-node}
       i/remove-icon]]

     [:div {:class (stl/css :sub-actions-group)}
            ;; Merge Nodes
      [:button
       {:disabled (not (:merge-nodes enabled-buttons))
        :title (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
        :on-click on-merge-nodes}
       i/merge-nodes]

            ;; Join Nodes
      [:button
       {:disabled (not (:join-nodes enabled-buttons))
        :title (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
        :on-click on-join-nodes}
       i/join-nodes]

            ;; Separate Nodes
      [:button
       {:disabled (not (:separate-nodes enabled-buttons))
        :title (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
        :on-click on-separate-nodes}
       i/separate-nodes]]

           ;; Make Corner
     [:div {:class (stl/css :sub-actions-group)}
      [:button
       {:disabled (not (:make-corner enabled-buttons))
        :title (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
        :on-click on-make-corner}
       i/to-corner]

            ;; Make Curve
      [:button
       {:disabled (not (:make-curve enabled-buttons))
        :title (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
        :on-click on-make-curve}
       i/to-curve]]

           ;; Toggle snap
     [:div {:class (stl/css :sub-actions-group)}
      [:button
       {:class  (stl/css-case :is-toggled snap-toggled)
        :title (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
        :on-click on-toggle-snap}
       i/snap-nodes]]]))

