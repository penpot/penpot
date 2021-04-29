;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.context-menu
  (:require
   [rumext.alpha :as mf]
   [goog.object :as gobj]
   [app.main.ui.components.dropdown :refer [dropdown']]
   [app.main.ui.icons :as i]
   [app.common.uuid :as uuid]
   [app.util.dom :as dom]
   [app.util.object :as obj]))

(mf/defc context-menu
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

        local         (mf/use-state {:offset 0
                                     :levels nil})

        on-local-close
        (mf/use-callback
          (fn []
            (swap! local assoc :levels [{:parent-option nil
                                         :options options}])
            (on-close)))

        check-menu-offscreen
        (mf/use-callback
         (mf/deps top (:offset @local))
         (fn [node]
           (when (some? node)
             (let [{node-height :height}   (dom/get-bounding-rect node)
                   {window-height :height} (dom/get-window-size)
                   target-offset (if (> (+ top node-height) window-height)
                                   (- node-height)
                                   0)]

               (if (not= target-offset (:offset @local))
                 (swap! local assoc :offset target-offset))))))

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
                           :style {:top (+ top (:offset @local))
                                   :left left}}
        (let [level (-> @local :levels peek)]
          [:ul.context-menu-items {:class (dom/classnames :min-width min-width?)
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
           (for [[option-name option-handler sub-options] (:options level)]
             (when option-name
               (if (= option-name :separator)
                 [:li.separator]
                 [:li.context-menu-item
                  {:class (dom/classnames :is-selected (and selected (= option-name selected)))
                   :key option-name}
                  (if-not sub-options
                    [:a.context-menu-action {:on-click option-handler}
                     option-name]
                    [:a.context-menu-action.submenu
                     {:data-no-close true
                      :on-click (enter-submenu option-name sub-options)}
                     option-name
                     [:span i/arrow-slide]])])))])]])))
