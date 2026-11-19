;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

;; Handler behavior icons: mirror, aligned, and independent.
(defn- handler-type-icon [type]
  (case type
    :mirror      i/handlers-equal
    :aligned     i/handlers-mirror
    :independent i/handlers-independent
    ;; Use the independent icon for mixed selections.
    :mixed       i/handlers-independent
    i/handlers-independent))

(defn toolbar-group-visibility
  [structural-visible? shape-visible? handler-visible?]
  (let [shape-handler-visible? (or shape-visible? handler-visible?)]
    {:shape-handler-visible? shape-handler-visible?
     :node-groups-separator-visible? (and structural-visible? shape-handler-visible?)
     :snap-separator-visible? (or structural-visible? shape-handler-visible?)}))

(mf/defc topbar-button*
  "A path node action button."
  {::mf/private true}
  [props]
  (let [props (mf/spread-props props {:variant "ghost"
                                      :tooltip-placement "bottom"
                                      :tooltip-class (stl/css :topbar-btn)})]
    [:> icon-button* props]))

(defn- cancel-timer!
  [timer-ref*]
  (when-let [timer (mf/ref-val timer-ref*)]
    (ts/dispose! timer)
    (mf/set-ref-val! timer-ref* nil)))

(mf/defc handler-type-menu*
  "Sets the handler behavior of selected nodes."
  {::mf/private true}
  [{:keys [active-type on-select]}]
  (let [open*        (mf/use-state false)
        open?        (deref open*)

        open-timer*  (mf/use-ref nil)
        close-timer* (mf/use-ref nil)

        select
        (mf/use-fn
         (mf/deps on-select)
         (fn [type]
           (reset! open* false)
           (on-select type)))

        on-trigger-click
        (mf/use-fn
         (mf/deps select active-type)
         (fn []
           (case (path.helpers/handler-trigger-action active-type)
             :open
             (do
               (cancel-timer! close-timer*)
               (cancel-timer! open-timer*)
               (reset! open* true))

             :select
             (select active-type))))

        on-display-menu
        (mf/use-fn
         (fn []
           (cancel-timer! close-timer*)
           (cancel-timer! open-timer*)
           (mf/set-ref-val!
            open-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* true)
                            (mf/set-ref-val! open-timer* nil))))))

        on-hide-menu
        (mf/use-fn
         (fn []
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (mf/set-ref-val!
            close-timer*
            (ts/schedule 350
                         #(do
                            (reset! open* false)
                            (mf/set-ref-val! close-timer* nil))))))]

    (mf/with-effect []
      (fn []
        (cancel-timer! open-timer*)
        (cancel-timer! close-timer*)))

    [:div {:class (stl/css :handler-menu)
           :on-pointer-enter on-display-menu
           :on-pointer-leave on-hide-menu}
     ;; Like the top toolbar flyouts: the menu opens on hover, so the
     ;; trigger has no tooltip.
     [:> icon-button* {:variant "ghost"
                       :class (stl/css :topbar-btn)
                       :flyout-indicator true
                       :has-tooltip false
                       :aria-label (tr "workspace.path.actions.handler-type")
                       :aria-haspopup true
                       :aria-expanded open?
                       :icon (handler-type-icon active-type)
                       :on-click on-trigger-click}]
     [:div {:class (stl/css-case :handler-popover true :open open?)
            :data-dont-clear-path true}
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.handler-mirror")
                          :aria-pressed (= active-type :mirror)
                          :icon (handler-type-icon :mirror)
                          :on-click #(select :mirror)}]
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.handler-aligned")
                          :aria-pressed (= active-type :aligned)
                          :icon (handler-type-icon :aligned)
                          :on-click #(select :aligned)}]
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.handler-independent")
                          :aria-pressed (= active-type :independent)
                          :icon (handler-type-icon :independent)
                          :on-click #(select :independent)}]]]))

(mf/defc path-actions*
  [{:keys [shape state]}]
  (let [{:keys [edit-mode selection snap-toggled]} state

        content (:content shape)

        ;; Include segment endpoints in node actions.
        selected-nodes (path.helpers/selected-node-indices content selection)

        enabled-buttons
        (mf/use-memo
         (mf/deps content selection)
         #(path.helpers/check-enabled content selected-nodes))

        on-select-draw-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :draw))))

        on-select-edit-mode
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/change-edit-mode :move))))

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
           (st/emit! (drp/toggle-snap))))

        ;; Show node actions only when they apply.
        structural-visible? (or (:merge-nodes enabled-buttons)
                                (:join-nodes enabled-buttons)
                                (:separate-nodes enabled-buttons))
        shape-visible?      (or (:make-corner enabled-buttons)
                                (:make-curve enabled-buttons))

        ;; Resolve selected handlers to their curve nodes.
        handler-nodes       (path.helpers/handler-target-nodes content selection)
        handler-state       (path.helpers/handler-selection-state
                             content (:handler-types state) handler-nodes)
        active-handler-type (:active-type handler-state)
        handler-visible?    (and (= edit-mode :move) (seq (:nodes handler-state)))

        group-visibility
        (toolbar-group-visibility structural-visible? shape-visible? handler-visible?)

        node-groups-separator-visible?
        (:node-groups-separator-visible? group-visibility)

        middle-visible?
        (:snap-separator-visible? group-visibility)

        on-set-handler-type
        (mf/use-fn
         (fn [type]
           (st/emit! (drp/set-handler-type type))))]

    [:div {:class (stl/css :sub-actions)
           :data-dont-clear-path true}
     ;; Mode: draw / move (always visible)
     [:div {:class (stl/css :sub-actions-group)}
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                          :aria-pressed (= edit-mode :draw)
                          :icon i/pentool
                          :on-click on-select-draw-mode}]
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                          :aria-pressed (= edit-mode :move)
                          :icon i/move
                          :on-click on-select-edit-mode}]]

     [:div {:class (stl/css :separator)}]

     ;; Structural node ops: merge / join / separate
     (when structural-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:merge-nodes enabled-buttons)
          [:> topbar-button* {:aria-label (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                              :on-click on-merge-nodes
                              :icon i/merge-nodes}])
        (when (:join-nodes enabled-buttons)
          [:> topbar-button* {:aria-label (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                              :on-click on-join-nodes
                              :icon i/join-nodes}])
        (when (:separate-nodes enabled-buttons)
          [:> topbar-button* {:aria-label (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                              :on-click on-separate-nodes
                              :icon i/separate-nodes}])])

     (when node-groups-separator-visible?
       [:div {:class (stl/css :separator)}])

     ;; Node shape and handler-behaviour ops
     (when shape-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:make-corner enabled-buttons)
          [:> topbar-button* {:aria-label (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                              :on-click on-make-corner
                              :icon i/to-corner}])
        (when (:make-curve enabled-buttons)
          [:> topbar-button* {:aria-label (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                              :on-click on-make-curve
                              :icon i/to-curve}])])

     ;; Handler behaviour of the selected node(s)
     (when handler-visible?
       [:> handler-type-menu* {:active-type active-handler-type
                               :on-select on-set-handler-type}])

     (when middle-visible?
       [:div {:class (stl/css :separator)}])

     ;; Toggle snap (always visible, pinned to the right)
     [:div {:class (stl/css :sub-actions-group :snap-group)}
      [:> topbar-button* {:aria-label (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                          :aria-pressed (boolean snap-toggled)
                          :icon i/snap
                          :on-click on-toggle-snap}]]]))
