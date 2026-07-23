;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.path-actions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(def ^:private pentool-icon
  (deprecated-icon/icon-xref :pentool (stl/css :pentool-icon :pathbar-icon)))

(def ^:private move-icon
  (deprecated-icon/icon-xref :move (stl/css :move-icon :pathbar-icon)))

(def ^:private merge-nodes-icon
  (deprecated-icon/icon-xref :merge-nodes (stl/css :merge-nodes-icon :pathbar-icon)))

(def ^:private join-nodes-icon
  (deprecated-icon/icon-xref :join-nodes (stl/css :join-nodes-icon :pathbar-icon)))

(def ^:private separate-nodes-icon
  (deprecated-icon/icon-xref :separate-nodes (stl/css :separate-nodes-icon :pathbar-icon)))

(def ^:private to-corner-icon
  (deprecated-icon/icon-xref :to-corner (stl/css :to-corner-icon :pathbar-icon)))

(def ^:private to-curve-icon
  (deprecated-icon/icon-xref :to-curve (stl/css :to-curve-icon :pathbar-icon)))

(def ^:private snap-icon
  (deprecated-icon/icon-xref :snap (stl/css :snap-icon :pathbar-icon)))

;; Handler behavior icons: mirror, aligned, and independent.
(def ^:private handler-mirror-icon
  (deprecated-icon/icon-xref :handlers-equal (stl/css :pathbar-icon)))

(def ^:private handler-aligned-icon
  (deprecated-icon/icon-xref :handlers-mirror (stl/css :pathbar-icon)))

(def ^:private handler-independent-icon
  (deprecated-icon/icon-xref :handlers-independent (stl/css :pathbar-icon)))

(defn- handler-type-icon [type]
  (case type
    :mirror      handler-mirror-icon
    :aligned     handler-aligned-icon
    :independent handler-independent-icon
    ;; Use the independent icon for mixed selections.
    :mixed       handler-independent-icon
    handler-independent-icon))

(defn toolbar-group-visibility
  [structural-visible? shape-visible? handler-visible?]
  (let [shape-handler-visible? (or shape-visible? handler-visible?)]
    {:shape-handler-visible? shape-handler-visible?
     :node-groups-separator-visible? (and structural-visible? shape-handler-visible?)
     :snap-separator-visible? (or structural-visible? shape-handler-visible?)}))

(mf/defc topbar-button*
  "A path node action button."
  {::mf/private true}
  [{:keys [title on-click icon]}]
  [:button {:class (stl/css :topbar-btn)
            :title title
            :on-click on-click}
   icon])

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
     [:button {:class (stl/css :topbar-btn :handler-trigger)
               :title (tr "workspace.path.actions.handler-type")
               :aria-haspopup true
               :aria-expanded open?
               :on-click on-trigger-click}
      (handler-type-icon active-type)
      [:svg {:view-box "0 0 6 6"
             :aria-hidden true
             :class (stl/css :flyout-indicator)}
       [:path {:d "M4,2 L4,3.15 C4,3.62 3.62,4 3.15,4 L2,4"
               :stroke-linecap "round"}]]]
     [:div {:class (stl/css-case :handler-popover true :open open?)
            :data-dont-clear-path true}
      [:button {:class (stl/css-case :is-toggled (= active-type :mirror) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-mirror")
                :on-click #(select :mirror)}
       handler-mirror-icon]
      [:button {:class (stl/css-case :is-toggled (= active-type :aligned) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-aligned")
                :on-click #(select :aligned)}
       handler-aligned-icon]
      [:button {:class (stl/css-case :is-toggled (= active-type :independent) :topbar-btn true)
                :title (tr "workspace.path.actions.handler-independent")
                :on-click #(select :independent)}
       handler-independent-icon]]]))

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
      [:button {:class (stl/css-case :is-toggled (= edit-mode :draw) :topbar-btn true)
                :title (tr "workspace.path.actions.draw-nodes" (sc/get-tooltip :draw-nodes))
                :on-click on-select-draw-mode}
       pentool-icon]
      [:button {:class (stl/css-case :is-toggled (= edit-mode :move) :topbar-btn true)
                :title (tr "workspace.path.actions.move-nodes" (sc/get-tooltip :move-nodes))
                :on-click on-select-edit-mode}
       move-icon]]

     [:div {:class (stl/css :separator)}]

     ;; Structural node ops: merge / join / separate
     (when structural-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:merge-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.merge-nodes" (sc/get-tooltip :merge-nodes))
                              :on-click on-merge-nodes
                              :icon merge-nodes-icon}])
        (when (:join-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.join-nodes" (sc/get-tooltip :join-nodes))
                              :on-click on-join-nodes
                              :icon join-nodes-icon}])
        (when (:separate-nodes enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.separate-nodes" (sc/get-tooltip :separate-nodes))
                              :on-click on-separate-nodes
                              :icon separate-nodes-icon}])])

     (when node-groups-separator-visible?
       [:div {:class (stl/css :separator)}])

     ;; Node shape and handler-behaviour ops
     (when shape-visible?
       [:div {:class (stl/css :sub-actions-group)}
        (when (:make-corner enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.make-corner" (sc/get-tooltip :make-corner))
                              :on-click on-make-corner
                              :icon to-corner-icon}])
        (when (:make-curve enabled-buttons)
          [:> topbar-button* {:title (tr "workspace.path.actions.make-curve" (sc/get-tooltip :make-curve))
                              :on-click on-make-curve
                              :icon to-curve-icon}])])

     ;; Handler behaviour of the selected node(s)
     (when handler-visible?
       [:> handler-type-menu* {:active-type active-handler-type
                               :on-select on-set-handler-type}])

     (when middle-visible?
       [:div {:class (stl/css :separator)}])

     ;; Toggle snap (always visible, pinned to the right)
     [:div {:class (stl/css :sub-actions-group :snap-group)}
      [:button {:class (stl/css-case :is-toggled snap-toggled :topbar-btn true)
                :title (tr "workspace.path.actions.snap-nodes" (sc/get-tooltip :snap-nodes))
                :on-click on-toggle-snap}
       snap-icon]]]))
