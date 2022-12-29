;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.context-menu-a11y
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown']]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [goog.object :as gobj]
   [rumext.v2 :as mf]))

(mf/defc context-menu-item
  {::mf/wrap-props false}
  [props]

  (let [children    (gobj/get props "children")
        on-click    (gobj/get props "on-click")
        on-key-down (gobj/get props "on-key-down")
        id          (gobj/get props "id")
        klass       (gobj/get props "klass")
        key         (gobj/get props "key")
        data-test   (gobj/get props "data-test")]
    [:li {:id id
          :class klass
          :tab-index "0"
          :on-key-down on-key-down
          :on-click on-click
          :key key
          :role "menuitem"
          :data-test data-test}
     children]))

(mf/defc context-menu-a11y'
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")

  (let [open?         (gobj/get props "show")
        on-close      (gobj/get props "on-close")
        options       (gobj/get props "options")
        is-selectable (gobj/get props "selectable")
        selected      (gobj/get props "selected")
        top           (gobj/get props "top" 0)
        left          (gobj/get props "left" 0)
        fixed?        (gobj/get props "fixed?" false)
        min-width?    (gobj/get props "min-width?" false)
        route         (mf/deref refs/route)
        in-dashboard? (= :dashboard-projects (:name (:data route)))

        local         (mf/use-state {:offset-y 0
                                     :offset-x 0
                                     :levels nil})

        on-local-close
        (mf/use-callback
         (fn []
           (swap! local assoc :levels [{:parent-option nil
                                        :options options}])
           (on-close)))

        check-menu-offscreen
        (mf/use-callback
         (mf/deps top (:offset-y @local) left (:offset-x @local))
         (fn [node]
           (when (some? node)
             (let [bounding_rect (dom/get-bounding-rect node)
                   window_size (dom/get-window-size)
                   {node-height :height node-width :width} bounding_rect
                   {window-height :height window-width :width} window_size
                   target-offset-y (if (> (+ top node-height) window-height)
                                     (- node-height)
                                     0)
                   target-offset-x (if (> (+ left node-width) window-width)
                                     (- node-width)
                                     0)]

               (when (or (not= target-offset-y (:offset-y @local)) (not= target-offset-x (:offset-x @local)))
                 (swap! local assoc :offset-y target-offset-y :offset-x target-offset-x))))))

        enter-submenu
        (mf/use-callback
         (mf/deps options)
         (fn [option-name sub-options]
           (fn [event]
             (dom/stop-propagation event)
             (swap! local update :levels
                    conj {:parent-option option-name
                          :options sub-options}))))

        exit-submenu
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (swap! local update :levels pop)))

        props (obj/merge props #js {:on-close on-local-close})]

    (mf/use-effect
     (mf/deps options)
     #(swap! local assoc :levels [{:parent-option nil
                                   :options options}]))

    (when (and open? (some? (:levels @local)))
      [:> dropdown' props
       [:div.context-menu {:class (dom/classnames :is-open open?
                                                  :fixed fixed?
                                                  :is-selectable is-selectable)
                           :style {:top (+ top (:offset-y @local))
                                   :left (+ left (:offset-x @local))}}
        (let [level (-> @local :levels peek)]
          [:ul.context-menu-items {:class (dom/classnames :min-width min-width?)
                                   :role "menu"
                                   :ref check-menu-offscreen}
           (when-let [parent-option (:parent-option level)]
             [:*
              [:li.context-menu-item
               [:a.context-menu-action.submenu-back
                {:data-no-close true
                 :on-click exit-submenu}
                [:span i/arrow-slide]
                parent-option]]
              [:li.separator]])
           (for [[index [option-name id option-handler sub-options data-test]] (d/enumerate (:options level))]
             (when option-name
               (if (= option-name :separator)
                 [:li.separator {:key (dm/str "context-item-" index)}]
                 [:li.context-menu-item
                  {:id id
                   :class (dom/classnames :is-selected (and selected (= option-name selected)))
                   :key (dm/str "context-item-" index)}
                  (if-not sub-options
                    [:a.context-menu-action {:on-click #(do (dom/stop-propagation %)
                                                            (on-close)
                                                            (option-handler %))
                                             :data-test data-test}
                     (if (and in-dashboard? (= option-name "Default"))
                       (tr "dashboard.default-team-name")
                       option-name)]
                    [:a.context-menu-action.submenu
                     {:data-no-close true
                      :on-click (enter-submenu option-name sub-options)
                      :data-test data-test}
                     option-name
                     [:span i/arrow-slide]])])))])]])))

(mf/defc context-menu-a11y
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")
  
  (when (gobj/get props "show")
    (mf/element context-menu-a11y' props)))
