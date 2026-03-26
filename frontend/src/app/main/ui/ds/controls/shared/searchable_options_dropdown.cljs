;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.searchable-options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.weak :refer [weak-key]]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.controls.shared.dropdown-navigation :refer [use-dropdown-navigation]]
   [app.main.ui.ds.controls.shared.option :refer [option*]]
   [app.main.ui.ds.controls.shared.token-option :refer [token-option*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-list
  [:and :string
   [:fn {:error/message "invalid data: invalid icon"} #(contains? i/icon-list %)]])

(def schema:option
  "A schema for the option data structure expected to receive on props
  for the `options-dropdown*` component."
  [:map
   [:id {:optional true} :string]
   [:resolved-value {:optional true}
    [:or :int :string :float :map]]
   [:name {:optional true} :string]
   [:value {:optional true} :keyword]
   [:icon {:optional true} schema:icon-list]
   [:label {:optional true} :string]
   [:aria-label {:optional true} :string]])

(def ^:private
  xf:filter-blank-id
  (filter #(str/blank? (get % :id))))

(def ^:private
  xf:filter-non-blank-id
  (remove #(str/blank? (get % :id))))

(defn- render-option
  [option ref on-click selected focused]
  (let [id   (get option :id)
        name (get option :name)
        type (get option :type)]

    (mf/html
     (case type
       :group
       [:li {:class (stl/css :group-option)
             :role "presentation"
             :key (weak-key option)}
        [:> icon*
         {:icon-id i/arrow-down
          :size "m"
          :class (stl/css :option-check)
          :aria-hidden (when name true)}]
        (d/name name)]

       :separator
       [:hr {:key (weak-key option) :class (stl/css :option-separator)}]

       :empty
       [:li {:key (weak-key option) :class (stl/css :option-empty) :role "presentation"}
        (get option :label)]

       ;; Token option
       :token
       [:> token-option* {:selected (= id selected)
                          :key (weak-key option)
                          :id id
                          :name name
                          :resolved (get option :resolved-value)
                          :ref ref
                          :role "option"
                          :focused (= id focused)
                          :on-click on-click}]

       ;; Normal option
       [:> option* {:selected (= id selected)
                    :key (weak-key option)
                    :id id
                    :label (get option :label)
                    :aria-label (get option :aria-label)
                    :icon (get option :icon)
                    :ref ref
                    :role "option"
                    :focused (= id focused)
                    :dimmed (true? (:dimmed option))
                    :on-click on-click}]))))

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:class {:optional true} :string]
   [:wrapper-ref {:optional true} :any]
   [:on-click fn?]
   [:options [:vector schema:option]]
   [:selected {:optional true} :any]
   [:focused {:optional true} :any]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:align {:optional true} [:maybe [:enum :left :right]]]])

(mf/defc searchable-options-dropdown*
  {::mf/schema schema:options-dropdown}
  [{:keys [on-click options selected focused empty-to-end align wrapper-ref class searchable search-placeholder] :rest props}]
  (let [align             (d/nilv align :left)

        ;; Search state — solo cuando searchable
        search*           (mf/use-state "")
        search            (deref search*)
        search-input-ref  (mf/use-ref nil)

        ;; Navegación interna — solo cuando searchable
        internal-list-ref (mf/use-ref nil)
        actual-list-ref   (or wrapper-ref internal-list-ref)
        nodes-ref         (mf/use-ref nil)

        filtered-options
        (mf/with-memo [options empty-to-end search searchable]
          (let [opts (if ^boolean empty-to-end
                       (into [] xf:filter-non-blank-id options)
                       options)]
            (if (and searchable (seq search))
              (filterv (fn [opt]
                         (or (not= :token (:type opt))
                             (str/includes? (str/lower (:name opt ""))
                                            (str/lower search))))
                       opts)
              opts)))

        options-blank
        (mf/with-memo [empty-to-end options]
          (when ^boolean empty-to-end
            (into [] xf:filter-blank-id options)))

        focusable-ids
        (mf/with-memo [filtered-options searchable]
          (when searchable
            (mapv :id (csu/focusable-options filtered-options))))

        on-search-change
        (mf/use-fn
         (fn [event]
           (reset! search* (dom/get-target-val event))))

        {:keys [focused-id focused-id* on-key-down]}
        (use-dropdown-navigation
         {:focusable-ids    focusable-ids
          :nodes-ref        nodes-ref
          :on-enter         (fn [id]
                              (when-let [node (obj/get (mf/ref-val nodes-ref) id)]
                                (.click node)))
          :searchable       searchable
          :search-input-ref search-input-ref
          :on-close         nil})

        ;; El focused efectivo: interno si searchable, externo si no
        effective-focused (if searchable focused-id focused)

        on-click-inner
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (dom/stop-propagation event)
           (on-click event)))

        set-option-ref
        (mf/use-fn
         (fn [node]
           (when node
             (let [state (d/nilv (mf/ref-val nodes-ref) #js {})
                   id    (dom/get-data node "id")]
               (mf/set-ref-val! nodes-ref (obj/set! state id node))
               (fn []
                 (let [state (d/nilv (mf/ref-val nodes-ref) #js {})]
                   (mf/set-ref-val! nodes-ref (obj/unset! state id))))))))

        list-props
        (mf/spread-props props
                         {:class       [class (stl/css-case :option-list true
                                                            :left-align (= align :left)
                                                            :right-align (= align :right))]
                          :ref         actual-list-ref
                          :tab-index   "-1"
                          :role        "listbox"
                          :on-key-down (when searchable on-key-down)})]

    (mf/with-effect []
      (when searchable
        (ts/schedule 0
                     #(if (mf/ref-val search-input-ref)
                        (dom/focus! (mf/ref-val search-input-ref))
                        (when-let [list (mf/ref-val actual-list-ref)]
                          (dom/focus! list))))))

    (mf/with-effect [focused-id]
      (when (and searchable (some? focused-id))
        (when-let [list (mf/ref-val actual-list-ref)]
          (when-not (dom/active? list)
            (dom/focus! list)))
        (when-let [node (obj/get (mf/ref-val nodes-ref) focused-id)]
          (dom/scroll-into-view-if-needed! node {:block "nearest" :inline "nearest"}))))

    [:> :ul list-props
     (when searchable
       [:li {:class (stl/css :option-search)
             :role  "presentation"}
        [:> ds/input* {:placeholder (d/nilv search-placeholder "Search...")
                       :value       search
                       :ref         search-input-ref
                       :variant     "comfortable"
                       :on-change   on-search-change
                       :on-click    #(reset! focused-id* nil)
                       :on-key-down on-key-down}]])

     (for [option filtered-options]
       (render-option option set-option-ref on-click-inner selected effective-focused))

     (when (seq options-blank)
       [:*
        (when (seq filtered-options)
          [:hr {:class (stl/css :option-separator)}])
        (for [option options-blank]
          (render-option option set-option-ref on-click-inner selected effective-focused))])]))
