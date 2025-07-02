;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.layout.tab-switcher
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(mf/defc tab*
  {::mf/private true}
  [{:keys [selected icon label aria-label id ref] :rest props}]
  (let [class (stl/css-case
               :tab true
               :selected selected)
        props (mf/spread-props props
                               {:class class
                                :role "tab"
                                :aria-selected selected
                                :title (or label aria-label)
                                :tab-index  (if selected nil -1)
                                :ref ref
                                :data-id id
                                ;; This prop is to be used for accessibility purposes only.
                                :id id})]

    [:li
     [:> :button props
      (when (some? icon)
        [:> icon*
         {:icon-id icon
          :aria-hidden (when label true)
          :aria-label  (when (not label) aria-label)}])
      (when (string? label)
        [:span {:class (stl/css-case
                        :tab-text true
                        :tab-text-and-icon icon)}
         label])]]))

(mf/defc tab-nav*
  {::mf/private true
   ::mf/memo true}
  [{:keys [ref tabs selected on-click button-position action-button] :rest props}]
  (let [nav-class
        (stl/css-case :tab-nav true
                      :tab-nav-start (= "start" button-position)
                      :tab-nav-end (= "end" button-position))
        props
        (mf/spread-props props
                         {:class (stl/css :tab-list)
                          :role "tablist"
                          :aria-orientation "horizontal"})]
    [:nav {:class nav-class}
     (when (= button-position "start")
       action-button)

     [:> :ul props
      (for [element tabs]
        (let [icon       (get element :icon)
              label      (get element :label)
              aria-label (get element :aria-label)
              id         (get element :id)]

          [:> tab* {:icon       icon
                    :key        id
                    :label      label
                    :aria-label aria-label
                    :selected   (= id selected)
                    :on-click   on-click
                    :ref        ref
                    :id         id}]))]

     (when (= button-position "end")
       action-button)]))

(def ^:private schema:tab-attrs
  [:map {:title "tab"}
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:label {:optional true} :string]
   [:aria-label {:optional true} :string]])

(def ^:private schema:tab
  [:and
   schema:tab-attrs
   [:fn {:error/message "invalid data: missing required props"}
    (fn [tab]
      (or (and (contains? tab :icon)
               (or (contains? tab :label)
                   (contains? tab :aria-label)))
          (contains? tab :label)))]])

(def ^:private schema:tab-switcher
  [:map
   [:tabs [:vector {:min 1} schema:tab]]
   [:class {:optional true} :string]
   [:on-change fn?]
   [:selected :string]
   [:action-button {:optional true} some?]
   [:action-button-position {:optional true}
    [:maybe [:enum "start" "end"]]]])

(mf/defc tab-switcher*
  {::mf/schema schema:tab-switcher}
  [{:keys [tabs class on-change selected action-button-position action-button children] :rest props}]
  (let [nodes-ref (mf/use-ref nil)

        tabs
        (if (array? tabs)
          (mfu/bean tabs)
          tabs)


        on-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")]
             (when (fn? on-change)
               (on-change id)))))

        on-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state)
             (fn []
               (let [state (mf/ref-val nodes-ref)
                     state (d/nilv state #js {})
                     id    (dom/get-data node "id")
                     state (obj/unset! state id)]
                 (mf/set-ref-val! nodes-ref state))))))

        on-key-down
        (mf/use-fn
         (mf/deps selected tabs on-change)
         (fn [event]
           (let [len   (count tabs)
                 sel?  #(= selected (get % :id))
                 id    (cond
                         (kbd/home? event)
                         (let [tab (nth tabs 0)]
                           (get tab :id))

                         (kbd/left-arrow? event)
                         (let [index (d/index-of-pred tabs sel?)
                               index (mod (- index 1) len)
                               tab   (nth tabs index)]
                           (get tab :id))

                         (kbd/right-arrow? event)
                         (let [index (d/index-of-pred tabs sel?)
                               index (mod (+ index 1) len)
                               tab   (nth tabs index)]
                           (get tab :id)))]

             (when (some? id)
               (on-change id)
               (let [nodes (mf/ref-val nodes-ref)
                     node  (obj/get nodes id)]
                 (dom/focus! node))))))

        props
        (mf/spread-props props {:class [class (stl/css :tabs)]})]

    [:> :article props
     [:div {:class (stl/css :padding-wrapper)}
      [:> tab-nav* {:button-position action-button-position
                    :action-button action-button
                    :tabs tabs
                    :ref on-ref
                    :selected selected
                    :on-key-down on-key-down
                    :on-click on-click}]]

     [:section {:class (stl/css :tab-panel)
                :tab-index 0
                :role "tabpanel"
                :aria-labelledby selected}
      children]]))
