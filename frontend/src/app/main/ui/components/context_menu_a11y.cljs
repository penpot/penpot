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
   [app.common.schema :as sm]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown-content*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

(def ^:private xf:options
  (comp
   (map :id)
   (filter some?)))

(defn- generate-ids-group
  [options has-parents?]
  (let [ids (sequence xf:options options)
        ids (if has-parents?
              (cons "go-back-sub-option" ids)
              ids)]
    (vec ids)))

(def ^:private schema:option
  [:schema {:registry
            {::option
             [:or
              :nil
              [:map [:name [:= :separator]]]
              [:and
               [:map
                [:name :string]
                [:id :string]
                [:title {:optional true} [:maybe :string]]
                [:disabled {:optional true} [:maybe :boolean]]
                [:handler {:optional true} fn?]
                [:options {:optional true}
                 [:sequential [:ref ::option]]]]
               [::sm/contains-any #{:handler :options}]]]}}
   [:ref ::option]])

(def ^:private valid-option?
  (sm/lazy-validator schema:option))

(mf/defc context-menu-inner*
  [{:keys [on-close options selectable selected
           top left fixed min-width origin width]
    :as props}]

  (assert (every? valid-option? options) "expected valid options")
  (assert (fn? on-close) "missing `on-close` prop")
  (assert (vector? options) "missing `options` prop")

  (let [width          (d/nilv width "initial")
        min-width      (d/nilv min-width false)
        left           (d/nilv left 0)
        top            (d/nilv top 0)

        route          (mf/deref refs/route)
        in-dashboard?  (= :dashboard-projects (:name (:data route)))

        state*         (mf/use-state
                        #(-> {:offset-y 0
                              :offset-x 0
                              :levels nil}))

        state          (deref state*)
        offset-x       (get state :offset-x)
        offset-y       (get state :offset-y)
        levels         (get state :levels)
        internal-id    (mf/use-id)

        on-local-close
        (mf/use-fn
         (mf/deps on-close)
         (fn []
           (swap! state* assoc :levels [{:parent nil :options options}])
           (when (fn? on-close)
             (on-close))))

        props
        (mf/spread-props props {:on-close on-local-close})

        ids
        (mf/with-memo [levels]
          (let [last-level (last levels)]
            (generate-ids-group (:options last-level)
                                (:parent last-level))))

        check-menu-offscreen
        (mf/use-fn
         (mf/deps top left offset-x offset-y)
         (fn [node]
           (when (some? node)
             (let [bounding-rect   (dom/get-bounding-rect node)
                   window-size     (dom/get-window-size)
                   node-height     (dm/get-prop bounding-rect :height)
                   node-width      (dm/get-prop bounding-rect :width)
                   window-height   (get window-size :height)
                   window-width    (get window-size :width)

                   target-offset-y (if (> (+ top node-height) window-height)
                                     (- node-height)
                                     0)
                   target-offset-x (if (> (+ left node-width) window-width)
                                     (- node-width)
                                     0)]

               (when (or (not= target-offset-y offset-y)
                         (not= target-offset-x offset-x))
                 (swap! state* assoc
                        :offset-y target-offset-y
                        :offset-x target-offset-x))))))

        ;; NOTE: this function is used for build navigation callbacks
        ;; so we don't really need to use the use-fn here. It is not
        ;; an efficient approach but this manages a reasonable small
        ;; list of objects, so doing it this way has no real
        ;; implications on performance but facilitates a lot the
        ;; implementation
        enter-submenu
        (fn [name options]
          (fn [event]
            (dom/stop-propagation event)
            (swap! state* update :levels conj {:parent name
                                               :options options})))
        on-submenu-exit
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state* update :levels pop)))

        ;; NOTE: this function is used for build navigation callbacks
        ;; so we don't really need to use the use-fn here. It is not
        ;; an efficient approach but this manages a reasonable small
        ;; list of objects, so doing it this way has no real
        ;; implications on performance but facilitates a lot the
        ;; implementation
        on-key-down
        (fn [options-original parent-original]
          (fn [event]
            (let [ids             (generate-ids-group options-original
                                                      parent-original)
                  first-id        (dom/get-element (first ids))
                  first-element   (dom/get-element first-id)
                  len             (count ids)

                  parent          (dom/get-target event)
                  parent-id       (dom/get-attribute parent "id")

                  option          (d/seek #(= parent-id (:id %)) options-original)
                  sub-options     (not-empty (:options option))
                  handler         (:handler option)
                  is-back-option? (= "go-back-sub-option" parent-id)]

              (when (kbd/home? event)
                (when first-element
                  (dom/focus! first-element)))

              (when (kbd/enter? event)
                (if is-back-option?
                  (on-submenu-exit event)

                  (if sub-options
                    (do
                      (dom/stop-propagation event)
                      (swap! state* update :levels conj {:parent (:name option)
                                                         :options sub-options}))

                    (do
                      (dom/stop-propagation event)
                      (handler event)))))

              (when (and is-back-option? (kbd/left-arrow? event))
                (on-submenu-exit event))

              (when (and sub-options (kbd/right-arrow? event))
                (dom/stop-propagation event)
                (swap! state* update :levels conj {:parent (:name option)
                                                   :options sub-options}))

              (when (kbd/up-arrow? event)
                (let [actual-selected (dom/get-active)
                      actual-id       (dom/get-attribute actual-selected "id")
                      actual-index    (d/index-of ids actual-id)
                      previous-id     (if (= 0 actual-index)
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
                (on-close event)
                (dom/focus! (dom/get-element origin))))))]

    (mf/with-effect [options]
      (swap! state* assoc :levels [{:parent nil
                                    :options options}]))

    (mf/with-effect [internal-id]
      (ug/dispatch! (ug/event "penpot:context-menu:open" #js {:id internal-id})))

    (mf/with-effect [internal-id on-local-close]
      (letfn [(on-event [event]
                (when-let [detail (unchecked-get event "detail")]
                  (when (not= internal-id (unchecked-get detail "id"))
                    (on-local-close event))))]
        (ug/listen "penpot:context-menu:open" on-event)
        (partial ug/unlisten "penpot:context-menu:open" on-event)))

    (mf/with-effect [ids]
      (tm/schedule-on-idle
       #(dom/focus! (dom/get-element (first ids)))))

    (when (some? levels)
      [:> dropdown-content* props
       (let [level   (peek levels)
             options (:options level)
             parent  (:parent level)]

         [:div {:class (stl/css-case
                        :is-selectable selectable
                        :context-menu true
                        :is-open true
                        :fixed fixed)
                :style {:top (+ top offset-y)
                        :left (+ left offset-x)}
                :on-key-down (on-key-down options parent)}

          [:ul {:class (stl/css-case :min-width min-width
                                     :context-menu-items true)
                :style {:width width}
                :role "menu"
                :ref check-menu-offscreen}

           (when parent
             [:*
              [:li {:id "go-back-sub-option"
                    :class (stl/css :context-menu-item)
                    :role "menuitem"
                    :tab-index "0"
                    :on-key-down dom/prevent-default}
               [:button {:class (stl/css :context-menu-action :submenu-back)
                         :data-no-close true
                         :on-click on-submenu-exit}
                [:span {:class (stl/css :submenu-icon-back)} deprecated-icon/arrow]
                parent]]

              [:li {:class (stl/css :separator)}]])

           (for [[index option] (d/enumerate options)]
             (let [name        (:name option)
                   id          (:id option)
                   sub-options (:options option)
                   handler     (:handler option)
                   title       (:title option)
                   disabled    (:disabled option)]
               (when name
                 (if (= name :separator)
                   [:li {:key (dm/str "context-item-" index)
                         :class (stl/css :separator)}]
                   [:li {:id id
                         :key id
                         :class (stl/css-case
                                 :is-selected (and selected (= name selected))
                                 :selected (and selected (= id selected))
                                 :context-menu-item true)
                         :tab-index "0"
                         :role "menuitem"
                         :on-key-down dom/prevent-default}
                    (if-not sub-options
                      [:a {:class (stl/css-case :context-menu-action true :context-menu-action-disabled disabled)
                           :title title
                           :on-click #(do (dom/stop-propagation %)
                                          (when-not disabled
                                            (on-close %)
                                            (handler %)))
                           :data-testid id}
                       (if (and in-dashboard? (= name "Default"))
                         (tr "dashboard.default-team-name")
                         name)

                       (when (and selected (= id selected))
                         [:span {:class (stl/css :selected-icon)} deprecated-icon/tick])]

                      [:a {:class (stl/css :context-menu-action :submenu)
                           :data-no-close true
                           :on-click (enter-submenu name sub-options)
                           :data-testid id}
                       name
                       [:span {:class (stl/css :submenu-icon)} deprecated-icon/arrow]])]))))]])])))

(mf/defc context-menu*
  {::mf/private true}
  [{:keys [show] :as props}]

  (assert (boolean? show) "expected `show` prop to be a boolean")

  (when ^boolean show
    [:> context-menu-inner* props]))
