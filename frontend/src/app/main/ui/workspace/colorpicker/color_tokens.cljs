;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.colorpicker.color-tokens
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.event :as-alias ev]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc token-empty-state*
  {::mf/private true}
  []
  [:div {:class (stl/css :color-token-empty-state)}
   (tr "color-token.empty-state")])

(mf/defc list-item*
  {::mf/private true}
  [{:keys [token on-token-pill-click selected] :rest props}]
  (let [on-click
        (mf/use-fn
         (mf/deps token on-token-pill-click)
         (fn [event]
           (on-token-pill-click event token)))
        id-tooltip  (mf/use-id)
        resolved    (:resolved-value token)
        color-value (dwta/value->color resolved)]
    [:> tooltip* {:id id-tooltip
                  :style {:width "100%"}
                  :content (:name token)}
     [:button {:class (stl/css-case :color-token-item true
                                    :color-token-selected selected)
               :aria-labelledby id-tooltip
               :on-click on-click}
      [:> swatch* {:background color-value
                   :tooltip-content (tr "workspace.tokens.resolved-value" resolved)
                   :size "small"}]
      [:div {:class (stl/css :token-name)}
       (:name token)]
      (when selected
        [:> i/icon* {:icon-id i/tick
                     :size "s"
                     :class (stl/css :token-selected-icon)}])]]))

(mf/defc grid-item*
  {::mf/private true}
  [{:keys [token on-token-pill-click selected] :rest props}]
  (let [on-click
        (mf/use-fn
         (mf/deps token on-token-pill-click)
         (fn [event]
           (on-token-pill-click event token)))
        resolved    (:resolved-value token)
        token-name  (:name token)
        color-value (dwta/value->color resolved)]
    [:div {:class (stl/css-case :color-token-item-grid true
                                :color-token-selected-grid selected)}
     [:> swatch* {:background color-value
                  :tooltip-content
                  (mf/html
                   [:*
                    [:div (dm/str (tr "workspace.tokens.token-name") ": " token-name)]
                    [:div (tr "workspace.tokens.resolved-value" resolved)]])
                  :on-click on-click
                  :size "medium"}]]))

(defn group->set-name
  "Given a group structure, returns a representative set name.
   
   Input:
   {:group \"brand\"
    :sets  [\"light\" \"dark\"]
    :tokens [...]}

   Output:
   - If :group exists → \"brand/light\" (first set in :sets)
   - If :group is nil → the first (and only) value of :sets"
  [{:keys [group sets]}]
  (if group
    (str group "/" (first sets))
    (first sets)))

(mf/defc set-section*
  {::mf/private true}
  [{:keys [collapsed toggle-sets-open set name color-origin on-token-change] :rest props}]

  (let [list-style* (mf/use-state :list)
        list-style  (deref list-style*)
        toggle-list-style
        (mf/use-fn
         (mf/deps list-style)
         (fn []
           (let [new-style (if (= :list list-style) :grid :list)]
             (reset! list-style* new-style))))

        toggle-set
        (mf/use-fn
         (mf/deps name toggle-sets-open)
         (fn []
           (toggle-sets-open name)))

        objects         (mf/deref refs/workspace-page-objects)
        selected        (mf/deref refs/selected-shapes)

        selected-shapes
        (mf/with-memo [selected objects]
          (into [] (keep (d/getf objects)) selected))
        first-shape       (first selected-shapes)
        applied-tokens    (:applied-tokens first-shape)
        has-color-tokens? (get applied-tokens :fill)
        has-stroke-tokens? (get applied-tokens :stroke-color)

        on-token-pill-click
        (mf/use-fn
         (mf/deps selected-shapes selected color-origin)
         (fn [event token]
           (dom/stop-propagation event)
           (when (seq selected-shapes)
             (if (= :color-selection color-origin)
               (on-token-change event token)
               (let [attributes (if (= color-origin :stroke) #{:stroke-color} #{:fill})
                     shape-ids (into #{} (map :id selected-shapes))]
                 (if (or
                      (= (:name token) has-stroke-tokens?)
                      (= (:name token) has-color-tokens?))
                   (st/emit! (dwta/unapply-token {:attributes attributes
                                                  :token token
                                                  :shape-ids shape-ids}))
                   (st/emit! (dwta/apply-token {:shape-ids shape-ids
                                                :attributes attributes
                                                :token token
                                                :on-update-shape dwta/update-fill-stroke}))))))))

        create-token-on-set
        (mf/use-fn
         (mf/deps set)
         (fn [_]
           (let [first-set-name (group->set-name set)
                 set-item-id (dm/str "token-set-item-" first-set-name)
                 set-element (dom/get-element set-item-id)]
             (when set-element
               (dom/click set-element)
               (tm/schedule-on-idle
                (let [button-element (dom/get-element "add-token-button-Color")]
                  #(dom/click button-element)))))))]

    [:div {:class (stl/css :color-token-set)}
     [:> title-bar* {:collapsable    true
                     :collapsed      collapsed
                     :all-clickable  true
                     :on-collapsed   toggle-set
                     :class (stl/css :set-title-bar)
                     :title          name}

      (when (not collapsed)
        [:div {:class (stl/css :set-actions)}
         [:> icon-button* {:on-click toggle-list-style
                           :variant "action"
                           :aria-label (if (= :list list-style)
                                         (tr "workspace.assets.grid-view")
                                         (tr "workspace.assets.list-view"))
                           :icon (if (= :list list-style)
                                   i/flex-grid
                                   i/view-as-list)}]
         [:> icon-button* {:on-click create-token-on-set
                           :variant "action"
                           :aria-label (tr "workspace.tokens.add-token" "color")
                           :icon i/add}]])]

     (when (not collapsed)
       [:div {:class (stl/css-case :color-token-list true
                                   :list-view (= list-style :list)
                                   :grid-view (= list-style :grid))}

        (for [token (:tokens set)]
          (let [selected? (if (= color-origin :fill)
                            (= has-color-tokens? (:name token))
                            (= has-stroke-tokens? (:name token)))]
            (if (= :grid list-style)
              [:> grid-item* {:key (str "token-grid-" (:id token))
                              :on-token-pill-click on-token-pill-click
                              :selected selected?
                              :token token}]
              [:> list-item* {:key (str "token-list-" (:id token))
                              :on-token-pill-click on-token-pill-click
                              :selected selected?
                              :token token}])))])]))
(defn- label-group-or-set [{:keys [group sets]}]
  (if group
    (str group " (" (str/join ", " sets) ")")
    (first sets)))

(defn- filter-combined-tokens
  "Filters the combined-tokens structure by token name.
   Removes sets or groups if they end up with no tokens.
   
   Input: 
   [{:group \"brand\", :sets [\"light\" \"dark\"], :tokens [{:name \"background\"} {:name \"foreground\"}]}
    {:group nil, :sets [\"primitivos\"], :tokens [{:name \"blue-100\"} {:name \"red-100\"}]}]
   
   (filter-combined-tokens ... \"blue\")
   Output: 
   [{:group nil, :sets [\"primitivos\"], :tokens [{:name \"blue-100\"}]}]
   => keeps only tokens matching \"blue\", and removes sets/groups if no tokens match."

  [combined-tokens term]
  (let [term (str/lower (str/trim term))]
    (if (str/blank? term)
      combined-tokens
      (->> combined-tokens
           (map (fn [{:keys [tokens] :as entry}]
                  (let [filtered (filterv #(str/includes?
                                            (str/lower (:name %))
                                            term)
                                          tokens)]
                    (when (seq filtered)
                      (assoc entry :tokens filtered)))))
           (remove nil?)
           vec))))

(mf/defc token-section*
  {}
  [{:keys [combined-tokens color-origin on-token-change] :rest props}]
  (let [sets (set (mapv label-group-or-set combined-tokens))
        filter-term* (mf/use-state "")
        filter-term (deref filter-term*)
        open-sets* (mf/use-state sets)
        open-sets  (deref open-sets*)
        toggle-sets-open
        (mf/use-fn
         (mf/deps open-sets)
         (fn [name]
           (if (contains? open-sets name)
             (swap! open-sets* disj name)
             (swap! open-sets* conj name))))

        on-filter-tokens
        (mf/use-fn
         (mf/deps filter-term)
         (fn [event]
           (let [value (-> event (dom/get-target)
                           (dom/get-value))]
             (reset! filter-term* value))))
        filtered-combined (filter-combined-tokens combined-tokens filter-term)]
    (if combined-tokens
      [:div {:class (stl/css :color-tokens-section)}
       [:> input* {:placeholder "Search by token name"
                   :icon i/search
                   :max-length max-input-length
                   :variant "comfortable"
                   :class (stl/css :search-input)
                   :default-value filter-term
                   :on-change on-filter-tokens}]
       (for [combined-sets filtered-combined]
         (let  [name (label-group-or-set combined-sets)]
           [:> set-section*
            {:collapsed (not (contains?  open-sets name))
             :key (str "set-" name)
             :toggle-sets-open toggle-sets-open
             :color-origin color-origin
             :on-token-change on-token-change
             :name name
             :set combined-sets}]))]
      [:> token-empty-state*])))

