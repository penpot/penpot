;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.context-menu-a11y
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown']]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as tm]
   [goog.object :as gobj]
   [rumext.v2 :as mf]))

(defn generate-ids-group
  [options parent-name]
  (let  [ids (->> options
                  (map :id)
                  (filter some?))]
    (if parent-name
      (cons "go-back-sub-option" ids)
      ids)))

(mf/defc context-menu-a11y-item
  {::mf/wrap-props false}
  [props]

  (let [children    (gobj/get props "children")
        on-click    (gobj/get props "on-click")
        on-key-down (gobj/get props "on-key-down")
        id          (gobj/get props "id")
        klass       (gobj/get props "class")
        key-index   (gobj/get props "key-index")
        data-test   (gobj/get props "data-test")]
    [:li {:id id
          :class klass
          :tab-index "0"
          :on-key-down on-key-down
          :on-click on-click
          :key key-index
          :role "menuitem"
          :data-test data-test}
     children]))

(mf/defc context-menu-a11y'
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")
  (let [open?          (gobj/get props "show")
        on-close       (gobj/get props "on-close")
        options        (gobj/get props "options")
        is-selectable  (gobj/get props "selectable")
        selected       (gobj/get props "selected")
        top            (gobj/get props "top" 0)
        left           (gobj/get props "left" 0)
        fixed?         (gobj/get props "fixed?" false)
        min-width?     (gobj/get props "min-width?" false)
        origin         (gobj/get props "origin")
        route          (mf/deref refs/route)
        in-dashboard?  (= :dashboard-projects (:name (:data route)))
        local          (mf/use-state {:offset-y 0
                                      :offset-x 0
                                      :levels nil})
        width          (gobj/get props "width" "initial")


        on-local-close
        (mf/use-callback
         (fn []
           (swap! local assoc :levels [{:parent-option nil
                                        :options options}])
           (on-close)))

        props (obj/merge props #js {:on-close on-local-close})

        ids (generate-ids-group (:options (last (:levels @local))) (:parent-option (last (:levels @local))))
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

        on-key-down
        (fn [options-original parent-original]
          (fn [event]
            (let [ids (generate-ids-group options-original parent-original)
                  first-id (dom/get-element (first ids))
                  first-element (dom/get-element first-id)
                  len (count ids)
                  parent (dom/get-target event)
                  parent-id (dom/get-attribute parent "id")
                  option (first (filter  #(= parent-id (:id %)) options-original))
                  sub-options (:sub-options option)
                  has-suboptions? (some? (:sub-options option))
                  option-handler (:option-handler option)
                  is-back-option (= "go-back-sub-option" parent-id)]
              (when (kbd/home? event)
                (when first-element
                  (dom/focus! first-element)))

              (when (kbd/enter? event)
                (if is-back-option
                  (exit-submenu event)

                  (if has-suboptions?
                    (do
                      (dom/stop-propagation event)
                      (swap! local update :levels
                             conj {:parent-option (:option-name option)
                                   :options sub-options}))

                    (do
                      (dom/stop-propagation event)
                      (option-handler event)))))

              (when (and is-back-option
                         (kbd/left-arrow? event))
                (exit-submenu event))

              (when (and has-suboptions? (kbd/right-arrow? event))
                (dom/stop-propagation event)
                (swap! local update :levels
                       conj {:parent-option (:option-name option)
                             :options sub-options}))
              (when (kbd/up-arrow? event)
                (let [actual-selected (dom/get-active)
                      actual-id (dom/get-attribute actual-selected "id")
                      actual-index (d/index-of ids actual-id)
                      previous-id (if (= 0 actual-index)
                                    (last ids)
                                    (nth ids (- actual-index 1)))]
                  (dom/focus! (dom/get-element previous-id))))

              (when (kbd/down-arrow? event)
                (let [actual-selected (dom/get-active)
                      actual-id (dom/get-attribute actual-selected "id")
                      actual-index (d/index-of ids actual-id)
                      next-id (if (= (- len 1) actual-index)
                                (first ids)
                                (nth ids (+ 1 actual-index)))]
                  (dom/focus! (dom/get-element next-id))))

              (when (or (kbd/esc? event) (kbd/tab? event))
                (on-close)
                (dom/focus! (dom/get-element origin))))))]

    (mf/with-effect [options]
      (swap! local assoc :levels [{:parent-option nil
                                   :options options}]))

    (mf/with-effect [ids]
      (tm/schedule-on-idle
       #(dom/focus! (dom/get-element (first ids)))))

    (when (and open? (some? (:levels @local)))
      [:> dropdown' props
       (let [level (-> @local :levels peek)
             original-options (:options level)
             parent-original (:parent-option level)]
         [:div {:class (stl/css-case :is-selectable is-selectable
                                     :context-menu true
                                     :is-open open?
                                     :fixed fixed?)
                :style {:top (+ top (:offset-y @local))
                        :left (+ left (:offset-x @local))}
                :on-key-down (on-key-down original-options parent-original)}
          (let [level (-> @local :levels peek)]
            [:ul {:class (stl/css-case :min-width min-width?
                                       :context-menu-items true)
                  :style {:width width}
                  :role "menu"
                  :ref check-menu-offscreen}
             (when-let [parent-option (:parent-option level)]
               [:*
                [:& context-menu-a11y-item
                 {:id "go-back-sub-option"
                  :class (stl/css :context-menu-item)
                  :tab-index "0"
                  :on-key-down (fn [event]
                                 (dom/prevent-default event))}
                 [:button {:class (stl/css :context-menu-action :submenu-back)
                           :data-no-close true
                           :on-click exit-submenu}
                  [:span {:class (stl/css :submenu-icon-back)} i/arrow]
                  parent-option]]

                [:li {:class (stl/css :separator)}]])

             (for [[index option] (d/enumerate (:options level))]
               (let [option-name (:option-name option)
                     id (:id option)
                     sub-options (:sub-options option)
                     option-handler (:option-handler option)
                     data-test (:data-test option)]
                 (when option-name
                   (if (= option-name :separator)
                     [:li {:key (dm/str "context-item-" index)
                           :class (stl/css :separator)}]
                     [:& context-menu-a11y-item
                      {:id id
                       :key id
                       :class (stl/css-case
                               :is-selected (and selected (= option-name selected))
                               :selected (and selected (= data-test selected))
                               :context-menu-item true)
                       :key-index (dm/str "context-item-" index)
                       :tab-index "0"
                       :on-key-down (fn [event]
                                      (dom/prevent-default event))}
                      (if-not sub-options
                        [:a {:class (stl/css :context-menu-action)
                             :on-click #(do (dom/stop-propagation %)
                                            (on-close)
                                            (option-handler %))
                             :data-test data-test}
                         (if (and in-dashboard? (= option-name "Default"))
                           (tr "dashboard.default-team-name")
                           option-name)

                         (when (and selected (= data-test selected))
                           [:span {:class (stl/css :selected-icon)} i/tick])]

                        [:a {:class (stl/css :context-menu-action :submenu)
                             :data-no-close true
                             :on-click (enter-submenu option-name sub-options)
                             :data-test data-test}
                         option-name
                         [:span {:class (stl/css :submenu-icon)} i/arrow]])]))))])])])))

(mf/defc context-menu-a11y
  {::mf/wrap-props false}
  [props]
  (assert (fn? (gobj/get props "on-close")) "missing `on-close` prop")
  (assert (boolean? (gobj/get props "show")) "missing `show` prop")
  (assert (vector? (gobj/get props "options")) "missing `options` prop")

  (when (gobj/get props "show")
    (mf/element context-menu-a11y' props)))
