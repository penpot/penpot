;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.path.segment :as path.segm]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn check-enabled [content selected-points]
  (when content
    (let [segments (path.segm/get-segments-with-points content selected-points)
          num-segments (count segments)
          num-points (count selected-points)
          points-selected? (seq selected-points)
          segments-selected? (seq segments)
          ;; max segments for n points is (n × (n -1)) / 2
          max-segments (-> num-points
                           (* (- num-points 1))
                           (/ 2))
          is-curve? (some #(path.segm/is-curve? content %) selected-points)]

      {:make-corner (and points-selected? is-curve?)
       :make-curve (and points-selected? (not is-curve?))
       :add-node segments-selected?
       :remove-node points-selected?
       :merge-nodes segments-selected?
       :join-nodes (and points-selected? (>= num-points 2) (< num-segments max-segments))
       :separate-nodes segments-selected?})))

(mf/defc path-actions*
  [{:keys [shape state]}]
  (let [{:keys [edit-mode selected-points snap-toggled]} state

        content (:content shape)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selected-points)
         #(check-enabled content selected-points))

        on-select-draw-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :draw))))

        on-select-edit-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :move))))

        on-add-node
        (mf/use-fn
         (mf/deps (:add-node enabled-buttons))
         (fn [_]
           (when (:add-node enabled-buttons)
             (st/emit! (drp/add-node)))))

        on-remove-node
        (mf/use-fn
         (mf/deps (:remove-node enabled-buttons))
         (fn [_]
           (when (:remove-node enabled-buttons)
             (st/emit! (drp/remove-node)))))

        on-merge-nodes
        (mf/use-fn
         (mf/deps (:merge-nodes enabled-buttons))
         (fn [_]
           (when (:merge-nodes enabled-buttons)
             (st/emit! (drp/merge-nodes)))))

        on-join-nodes
        (mf/use-fn
         (mf/deps (:join-nodes enabled-buttons))
         (fn [_]
           (when (:join-nodes enabled-buttons)
             (st/emit! (drp/join-nodes)))))

        on-separate-nodes
        (mf/use-fn
         (mf/deps (:separate-nodes enabled-buttons))
         (fn [_]
           (when (:separate-nodes enabled-buttons)
             (st/emit! (drp/separate-nodes)))))

        on-make-corner
        (mf/use-fn
         (mf/deps (:make-corner enabled-buttons))
         (fn [_]
           (when (:make-corner enabled-buttons)
             (st/emit! (drp/make-corner)))))

        on-make-curve
        (mf/use-fn
         (mf/deps (:make-curve enabled-buttons))
         (fn [_]
           (when (:make-curve enabled-buttons)
             (st/emit! (drp/make-curve)))))

        on-toggle-snap
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/toggle-snap))))]

    [:div {:class (stl/css :sub-actions)
           :data-dont-clear-path true}
     [:div {:class (stl/css :sub-actions-group)}
      ;; Draw Mode
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/pentool
                        :aria-pressed (= edit-mode :draw)
                        :aria-label (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-select-draw-mode}]
       ;; Edit mode
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/move
                        :aria-pressed (= edit-mode :move)
                        :aria-label (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-select-edit-mode}]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Add Node
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/add
                        :aria-label (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
                        :tooltip-placement "bottom"
                        :on-click on-add-node
                        :disabled (not (:add-node enabled-buttons))}]
      ;; Remove node
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/remove
                        :aria-label (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
                        :tooltip-placement "bottom"
                        :on-click on-remove-node
                        :disabled (not (:remove-node enabled-buttons))}]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Merge Nodes
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/merge-nodes
                        :aria-label (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-merge-nodes
                        :disabled (not (:merge-nodes enabled-buttons))}]
      ;; Join Nodes
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/join-nodes
                        :aria-label (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-join-nodes
                        :disabled (not (:join-nodes enabled-buttons))}]
      ;; Separate Nodes
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/separate-nodes
                        :aria-label (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-separate-nodes
                        :disabled (not (:separate-nodes enabled-buttons))}]]

     [:div {:class (stl/css :sub-actions-group)}
      ; Make Corner
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/to-corner
                        :aria-label (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                        :tooltip-placement "bottom"
                        :on-click on-make-corner
                        :disabled (not (:make-corner enabled-buttons))}]
      ;; Make Curve
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/to-curve
                        :aria-label (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                        :tooltip-placement "bottom"
                        :on-click on-make-curve
                        :disabled (not (:make-curve enabled-buttons))}]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Toggle snap
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :topbar-btn)
                        :icon i/snap-nodes
                        :aria-pressed snap-toggled
                        :aria-label (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                        :tooltip-placement "bottom"
                        :on-click on-toggle-snap}]]]))
