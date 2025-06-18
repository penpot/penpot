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
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private pentool-icon
  (i/icon-xref :pentool (stl/css :pentool-icon :pathbar-icon)))

(def ^:private move-icon
  (i/icon-xref :move (stl/css :move-icon :pathbar-icon)))

(def ^:private add-icon
  (i/icon-xref :add (stl/css :add-icon :pathbar-icon)))

(def ^:private remove-icon
  (i/icon-xref :remove (stl/css :remove :pathbar-icon)))

(def ^:private merge-nodes-icon
  (i/icon-xref :merge-nodes (stl/css :merge-nodes-icon :pathbar-icon)))

(def ^:private join-nodes-icon
  (i/icon-xref :join-nodes (stl/css :join-nodes-icon :pathbar-icon)))

(def ^:private separate-nodes-icon
  (i/icon-xref :separate-nodes (stl/css :separate-nodes-icon :pathbar-icon)))

(def ^:private to-corner-icon
  (i/icon-xref :to-corner (stl/css :to-corner-icon :pathbar-icon)))

(def ^:private to-curve-icon
  (i/icon-xref :to-curve (stl/css :to-curve-icon :pathbar-icon)))

(def ^:private snap-nodes-icon
  (i/icon-xref :snap-nodes (stl/css :snap-nodes-icon :pathbar-icon)))

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
      [:button {:class  (stl/css-case :is-toggled (= edit-mode :draw)
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                :on-click on-select-draw-mode}
       pentool-icon]

       ;; Edit mode
      [:button {:class (stl/css-case :is-toggled (= edit-mode :move)
                                     :topbar-btn true)
                :title (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                :on-click on-select-edit-mode}
       move-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Add Node
      [:button {:disabled (not (:add-node enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.add-node" (sc/get-tooltip :add-node))
                :on-click on-add-node}
       add-icon]

      ;; Remove node
      [:button {:disabled (not (:remove-node enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.delete-node" (sc/get-tooltip :delete-node))
                :on-click on-remove-node}
       remove-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ;; Merge Nodes
      [:button {:disabled (not (:merge-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                :on-click on-merge-nodes}
       merge-nodes-icon]

      ;; Join Nodes
      [:button {:disabled (not (:join-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                :on-click on-join-nodes}
       join-nodes-icon]

      ;; Separate Nodes
      [:button {:disabled (not (:separate-nodes enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                :on-click on-separate-nodes}
       separate-nodes-icon]]

     [:div {:class (stl/css :sub-actions-group)}
      ; Make Corner
      [:button {:disabled (not (:make-corner enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                :on-click on-make-corner}
       to-corner-icon]

      ;; Make Curve
      [:button {:disabled (not (:make-curve enabled-buttons))
                :class (stl/css :topbar-btn)
                :title (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                :on-click on-make-curve}
       to-curve-icon]]
     [:div {:class (stl/css :sub-actions-group)}
      ;; Toggle snap
      [:button {:class  (stl/css-case :is-toggled snap-toggled
                                      :topbar-btn true)
                :title (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                :on-click on-toggle-snap}
       snap-nodes-icon]]]))
