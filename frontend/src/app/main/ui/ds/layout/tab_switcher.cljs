;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.layout.tab-switcher
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc tab*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [selected icon label aria-label id on-ref] :rest props}]
  (let [class (stl/css-case :tab true
                            :selected selected)
        props (mf/spread-props props
                               {:class class
                                :role "tab"
                                :aria-selected selected
                                :title (or label aria-label)
                                :tab-index  (if selected nil -1)
                                :ref (fn [node]
                                       (on-ref node id))
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
        [:span {:class (stl/css-case :tab-text true
                                     :tab-text-and-icon icon)}
         label])]]))

(mf/defc tab-nav*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-ref tabs selected on-click button-position action-button] :rest props}]
  (let [class (stl/css-case :tab-nav true
                            :tab-nav-start (= "start" button-position)
                            :tab-nav-end (= "end" button-position))
        props (mf/spread-props props
                               {:class (stl/css :tab-list)
                                :role "tablist"
                                :aria-orientation "horizontal"})]
    [:nav {:class class}
     (when (= button-position "start")
       action-button)

     [:> "ul" props
      (for [element ^js tabs]
        (let [icon       (obj/get element "icon")
              label      (obj/get element "label")
              aria-label (obj/get element "aria-label")
              id         (obj/get element "id")]

          [:> tab* {:icon       icon
                    :key        (dm/str "tab-" id)
                    :label      label
                    :aria-label aria-label
                    :selected   (= id selected)
                    :on-click   on-click
                    :on-ref     on-ref
                    :id         id}]))]

     (when (= button-position "end")
       action-button)]))

(defn- get-tab
  [tabs id]
  (or (array/find #(= id (obj/get % "id")) tabs)
      (aget tabs 0)))

(defn- get-selected-tab-id
  [tabs default]
  (let [tab (get-tab tabs default)]
    (obj/get tab "id")))

(def ^:private schema:tab
  [:and
   [:map {:title "tab"}
    [:icon {:optional true}
     [:and :string [:fn #(contains? icon-list %)]]]
    [:label {:optional true} :string]
    [:aria-label {:optional true} :string]
    [:content some?]]
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
   [:on-change-tab {:optional true} fn?]
   [:default-selected {:optional true} :string]
   [:selected {:optional true} :string]
   [:action-button {:optional true} some?]
   [:action-button-position {:optional true}
    [:maybe [:enum "start" "end"]]]])

(mf/defc tab-switcher*
  {::mf/props :obj
   ::mf/schema schema:tab-switcher}
  [{:keys [tabs class on-change-tab default-selected selected action-button-position action-button] :rest props}]
  (let [selected*        (mf/use-state #(or selected (get-selected-tab-id tabs default-selected)))
        selected         (or selected (deref selected*))

        tabs-nodes-refs  (mf/use-ref nil)
        tabs-ref         (mf/use-ref nil)

        on-click
        (mf/use-fn
         (mf/deps on-change-tab)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")]
             (reset! selected* id)

             (when (fn? on-change-tab)
               (on-change-tab id)))))

        on-ref
        (mf/use-fn
         (fn [node id]
           (let [refs (or (mf/ref-val tabs-nodes-refs) #js {})
                 refs (if node
                        (obj/set! refs id node)
                        (obj/unset! refs id))]
             (mf/set-ref-val! tabs-nodes-refs refs))))

        on-key-down
        (mf/use-fn
         (mf/deps selected)
         (fn [event]
           (let [tabs  (mf/ref-val tabs-ref)
                 len   (alength tabs)
                 sel?  #(= selected (obj/get % "id"))
                 id    (cond
                         (kbd/home? event)
                         (let [tab (aget tabs 0)]
                           (obj/get tab "id"))

                         (kbd/left-arrow? event)
                         (let [index (array/find-index sel? tabs)
                               index (mod (- index 1) len)
                               tab   (aget tabs index)]
                           (obj/get tab "id"))

                         (kbd/right-arrow? event)
                         (let [index (array/find-index sel? tabs)
                               index (mod (+ index 1) len)
                               tab   (aget tabs index)]
                           (obj/get tab "id")))]

             (when (some? id)
               (reset! selected* id)
               (let [nodes (mf/ref-val tabs-nodes-refs)
                     node  (obj/get nodes id)]
                 (dom/focus! node))))))

        class (dm/str class " " (stl/css :tabs))

        props (mf/spread-props props {:class class})]

    (mf/with-effect [tabs]
      (mf/set-ref-val! tabs-ref tabs))

    [:> :article props
     [:div {:class (stl/css :padding-wrapper)}
      [:> tab-nav* {:button-position action-button-position
                    :action-button action-button
                    :tabs tabs
                    :on-ref on-ref
                    :selected selected
                    :on-key-down on-key-down
                    :on-click on-click}]]

     (let [active-tab (get-tab tabs selected)
           content    (obj/get active-tab "content")
           id         (obj/get active-tab "id")]
       [:section {:class (stl/css :tab-panel)
                  :tab-index 0
                  :role "tabpanel"
                  :aria-labelledby id}
        content])]))
