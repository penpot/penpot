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
   [app.main.data.common :as dcm]
   [app.main.data.event :as-alias ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
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
                    [:div
                     [:span (dm/str (tr "workspace.tokens.token-name") ": ")] [:span {:class (stl/css :token-name)} token-name]]
                    [:div (tr "workspace.tokens.resolved-value" resolved)]])
                  :on-click on-click
                  :size "medium"}]]))

(defn group->paths
  "Given a map with :group string (slash-separated), returns a set of vectors
   representing the cumulative group hierarchy.

   Example:
   {:group \"test/gracia\"}
   => #{[\"test\"] [\"test\" \"gracia\"]}"
  [m]
  (let [parts (when-let [g (:group m)]
                (str/split g #"/"))]
    (if (seq parts)
      (->> (range 1 (inc (count parts)))
           (map (fn [i] (vec (take i parts))))
           set)
      #{})))

(mf/defc set-section*
  {::mf/private true}
  [{:keys [collapsed toggle-sets-open group-or-set name color-origin on-token-change applied-token] :rest props}]

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

        first-shape        (first selected-shapes)
        applied-tokens     (:applied-tokens first-shape)
        has-color-tokens?  (get applied-tokens :fill)
        has-stroke-tokens? (get applied-tokens :stroke-color)

        on-token-pill-click
        (mf/use-fn
         (mf/deps selected-shapes)
         (fn [event token]
           (dom/stop-propagation event)
           (when (seq selected-shapes)
             (on-token-change event token))))

        create-token-on-set
        (mf/use-fn
         (mf/deps group-or-set)
         (fn [_]
           (let [;; We want to create a token on the first set
                 ;; if there are many in this group
                 path-set (group->paths group-or-set)
                 id (:id (first (:sets group-or-set)))]
             (st/emit! (dcm/go-to-workspace :layout :tokens)
                       (when path-set
                         (ptk/data-event :expand-token-sets {:paths path-set}))
                       (dwtl/set-selected-token-set-id id)
                       (dwtl/set-token-type-section-open :color true)
                       (let [{:keys [modal title]} (get dwta/token-properties :color)
                             window-size (dom/get-window-size)
                             left-sidebar (dom/get-element "left-sidebar-aside")
                             x-size (dom/get-data left-sidebar "width")
                             modal-height 392
                             x (- (int x-size) 30)
                             y (- (/ (:height window-size) 2) (/ modal-height 2))]
                         (modal/show (:key modal)
                                     {:x x
                                      :y y
                                      :position :right
                                      :fields (:fields modal)
                                      :title title
                                      :action "create"
                                      :token-type :color}))))))

        icon-id (if collapsed i/arrow-right i/arrow-down)]

    [:article {:class (stl/css :color-token-set)}
     [:header {:class (stl/css :set-title-bar)}
      [:button {:class (stl/css :set-title-btn)
                :aria-controls (str "set-panel-" (d/name name))
                :aria-expanded (not collapsed)
                :aria-label (tr "inspect.tabs.styles.toggle-style" name)
                :on-click toggle-set}
       [:> i/icon* {:icon-id icon-id
                    :size "s"
                    :class (stl/css :set-title-icon)}]
       [:span {:class (stl/css :set-title)} name]]
      [:div {:class (stl/css-case :set-title-actions true
                                  :set-title-action-hidden collapsed)}
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
                         :icon i/add}]]]

     (when (not collapsed)
       [:div {:id (str "set-panel-" (d/name name))
              :class (stl/css-case :color-token-list true
                                   :list-view (= list-style :list)
                                   :grid-view (= list-style :grid))}

        (for [token (:tokens group-or-set)]
          (let [selected? (case color-origin
                            :fill (= has-color-tokens? (:name token))
                            :stroke-color (= has-stroke-tokens? (:name token))
                            :color-selection (= applied-token (:name token))
                            false)]
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
    (str group " (" (str/join ", " (map :name sets)) ")")
    (:name (first sets))))

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
                  (let [filtered (filter #(str/includes?
                                           (str/lower (:name %))
                                           term)
                                         tokens)]
                    (when (seq filtered)
                      (assoc entry :tokens filtered)))))
           (remove nil?)))))

(defn- sort-combined-tokens
  "Sorts tokens alphabetically by :name inside each group/set.
   Input:
   [{:group \"brand\", :sets [\"light\" \"dark\"], :tokens [{:name \"foreground\"} {:name \"background\"}]}]

   Output:
   [{:group \"brand\", :sets [\"light\" \"dark\"], :tokens [{:name \"background\"} {:name \"foreground\"}]}]"
  [combined-tokens]
  (map (fn [entry]
         (update entry :tokens #(sort-by :name %)))
       combined-tokens))

(mf/defc token-section*
  {}
  [{:keys [combined-tokens color-origin on-token-change applied-token] :rest props}]
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
             (reset! filter-term* value)
             (reset! open-sets* sets))))
        filtered-combined (filter-combined-tokens combined-tokens filter-term)
        sorted-tokens     (sort-combined-tokens filtered-combined)]
    (if (seq combined-tokens)
      [:div {:class (stl/css :color-tokens-section)}
       [:> input* {:placeholder "Search by token name"
                   :icon i/search
                   :max-length max-input-length
                   :variant "comfortable"
                   :class (stl/css :search-input)
                   :default-value filter-term
                   :on-change on-filter-tokens}]
       (if (seq sorted-tokens)
         [:div {:class (stl/css :color-tokens-inputs)}
          (for [combined-sets sorted-tokens]
            (let  [name (label-group-or-set combined-sets)]
              [:> set-section*
               {:collapsed (not (contains?  open-sets name))
                :key (str "set-" name)
                :toggle-sets-open toggle-sets-open
                :color-origin color-origin
                :on-token-change on-token-change
                :name name
                :applied-token applied-token
                :group-or-set combined-sets}]))]
         [:> token-empty-state*])]
      [:> token-empty-state*])))

