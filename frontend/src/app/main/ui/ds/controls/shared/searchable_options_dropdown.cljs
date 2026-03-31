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
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.controls.shared.dropdown-navigation :refer [use-dropdown-navigation]]
   [app.main.ui.ds.controls.shared.render-option :refer [render-option]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
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

(def ^:private schema:options-dropdown
  [:map
   [:ref {:optional true} fn?]
   [:class {:optional true} :string]
   [:wrapper-ref {:optional true} :any]
   [:placeholder {:optional true} :string]
   [:on-click fn?]
   [:options [:vector schema:option]]
   [:selected {:optional true} :any]
   [:align {:optional true} [:maybe [:enum :left :right]]]])

(mf/defc searchable-options-dropdown*
  {::mf/schema schema:options-dropdown}
  [{:keys [on-click options selected align class placeholder] :rest props}]
  (let [align             (d/nilv align :left)

        search*           (mf/use-state "")
        search            (deref search*)
        search-input-ref  (mf/use-ref nil)

        list-ref (mf/use-ref nil)
        nodes-ref         (mf/use-ref nil)

        filtered-options
        (mf/with-memo [options search]
          (if (seq search)
            (filterv (fn [opt]
                       (or (not= :token (:type opt))
                           (str/includes? (str/lower (:name opt ""))
                                          (str/lower search))))
                     options)
            options))

        focusable-ids
        (mf/with-memo [filtered-options]
          (mapv :id (csu/focusable-options filtered-options)))

        on-search-change
        (mf/use-fn
         (fn [event]
           (reset! search* (dom/get-target-val event))))

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

        {:keys [focused-id focused-id* on-key-down]}
        (use-dropdown-navigation
         {:focusable-ids    focusable-ids
          :nodes-ref        nodes-ref
          :on-enter         (fn [id]
                              (when-let [node (obj/get (mf/ref-val nodes-ref) id)]
                                (.click node)))
          :searchable       true
          :search-input-ref search-input-ref
          :on-close         nil})

        on-click-inner
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (dom/stop-propagation event)
           (on-click event)))

        list-props
        (mf/spread-props props
                         {:class       [class (stl/css-case :option-list true
                                                            :left-align (= align :left)
                                                            :right-align (= align :right))]
                          :ref         list-ref
                          :tab-index   "-1"
                          :role        "listbox"
                          :on-key-down on-key-down})]

    (mf/with-effect []
      (ts/schedule 0
                   #(if (mf/ref-val search-input-ref)
                      (dom/focus! (mf/ref-val search-input-ref))
                      (when-let [list (mf/ref-val list-ref)]
                        (dom/focus! list)))))

    (mf/with-effect [focused-id]
      (when (some? focused-id)
        (when-let [list (mf/ref-val list-ref)]
          (when-not (dom/active? list)
            (dom/focus! list)))
        (when-let [node (obj/get (mf/ref-val nodes-ref) focused-id)]
          (dom/scroll-into-view-if-needed! node {:block "nearest" :inline "nearest"}))))

    [:> :ul list-props
     [:li {:class (stl/css :option-search)
           :role  "presentation"}
      [:> ds/input* {:placeholder (or placeholder (tr "dashboard.search-placeholder"))
                     :value       search
                     :ref         search-input-ref
                     :variant     "comfortable"
                     :on-change   on-search-change
                     :on-click    #(reset! focused-id* nil)
                     :on-key-down on-key-down}]]

     (for [option filtered-options]
       (render-option option set-option-ref on-click-inner selected focused-id))]))
