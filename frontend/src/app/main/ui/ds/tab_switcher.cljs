;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.tab-switcher
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc tab*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [selected focused icon label aria-label id tab-ref] :rest props}]

  (let [class (stl/css-case :tab true
                            :selected selected)
        props (mf/spread-props props {:class class
                                      :role "tab"
                                      :aria-selected selected
                                      :title (or label aria-label)
                                      :tab-index  (if (or focused selected) "0" "-1")
                                      :ref tab-ref
                                      :data-id id})]

    [:> "li" {}
     [:> "button" props
      (when icon
        [:> icon*
         {:id icon
          :aria-hidden (when label true)
          :aria-label  (when (not label) aria-label)}])
      (when label
        [:span {:class (stl/css-case :tab-text true
                                     :tab-text-and-icon icon)} label])]]))

(mf/defc tab-nav*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [button-position open on-collapse  tabs selected on-change-tab focused tabs-refs] :rest props}]
  (let [class (stl/css-case :tab-nav true
                            :tab-nav-start (= "start" button-position)
                            :tab-nav-end (= "end" button-position))
        props (mf/spread-props props {:class (stl/css :tab-list)
                                      :role "tablist"
                                      :aria-orientation "horizontal"})]
    [:> "nav" {:class class}
     (when (= button-position "start")
      ;;  TODO: We need to create a new button varian to avoid defining this ad-hoc for this component
       [:> "button" {:on-click on-collapse
                     :aria-label "collapse tabs"
                     :class (stl/css-case :collapse-btn true
                                          :rotated open)}
        [:> icon*  {:id i/arrow}]])
     (when open
       [:> "ul" props
        (for [[index element] (map-indexed vector tabs)]
          (let [icon       (obj/get element "icon")
                label      (obj/get element "label")
                aria-label (obj/get element "aria-label")
                id         (obj/get element "id")]

            [:> tab* {:icon       icon
                      :key        (dm/str "tab-" id)
                      :label      label
                      :aria-label aria-label
                      :selected   (= index selected)
                      :focused    (= index focused)
                      :on-click   on-change-tab
                      :id         id
                      :tab-ref    (nth tabs-refs index)}]))])

     (when (= button-position "end")
       [:> "button" {:on-click on-collapse
                     :aria-label "collapse"
                     :class (stl/css-case :collapse-btn true
                                          :rotated (not open))}
        [:> icon*  {:id i/arrow}]])]))



(mf/defc tab-panel*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [children name] :rest props}]
  (let [props (mf/spread-props props {:class (stl/css :tab-panel)
                                      :aria-labelledby name
                                      :role "tabpanel"})]
    [:> "section" props
     children]))

(defn- valid-tabs?
  [tabs]
  (every? (fn [tab]
            (let [icon (obj/get tab "icon")
                  label (obj/get tab "label")
                  aria-label (obj/get tab "aria-label")]
              (and  (or (not icon) (contains? icon-list icon))
                    (not (and icon (nil? label) (nil? aria-label)))
                    (not (and aria-label (or (nil? icon) label))))))
          (seq tabs)))

(mf/defc tab-switcher*
  {::mf/props :obj}
  [{:keys [class tabs callback default-selected button-position nav-padding-end nav-padding-start] :rest props}]
  ;; TODO: Implement a improved way to check this props
  (assert (valid-tabs? tabs) "unexpected props for tab-switcher")
  (let [tab-ids     (mapv #(obj/get % "id") tabs)
        state*      (mf/use-state #(do {:open true
                                        :selected-index  (or (d/index-of tab-ids default-selected) 0)
                                        :focused-index nil}))
        parent-ref     (mf/use-ref)
        state          (deref state*)
        open           (:open state)
        selected-index (:selected-index state)
        focused-index  (:focused-index state)
        tabs-refs      (mapv (fn [_] (mf/use-ref)) tabs)

        selected-element (nth tabs selected-index)
        panel-content (obj/get selected-element "content")

        on-collapse
        (mf/use-fn
         #(swap! state* update :open not))

        on-change-tab
        (mf/use-fn
         (mf/deps callback)
         (fn [event]
           (let [id (dom/get-data (dom/get-current-target event) "id")]
             (swap! state* assoc :selected id)

             (when (fn? callback)
               (callback id)))))

        on-key-down
        (mf/use-fn
         (mf/deps tabs-refs selected-index focused-index)
         (fn [event]
           (let [len (count tabs-refs)
                 is-focus-selected (= selected-index focused-index)
                 focus-index (or focused-index selected-index)]

             (when (kbd/home? event)
               (swap! state* assoc :focused-index 0)
               (dom/focus! (mf/ref-val (nth tabs-refs 0))))

             (when (kbd/left-arrow? event)
               (let [previous-index (mod (- focus-index 1) len)]
                 (swap! state* assoc :focused-index previous-index)
                 (dom/focus! (mf/ref-val (nth tabs-refs previous-index)))))

             (when (kbd/right-arrow? event)
               (let [next-index (mod (+ focus-index 1) len)]
                 (swap! state* assoc :focused-index next-index)
                 (dom/focus! (mf/ref-val (nth tabs-refs next-index)))))

             (when (kbd/tab? event)
               (when (= false is-focus-selected)
                 (swap! state* assoc :focused-index nil)))

             (when (kbd/enter? event)
               (swap! state* assoc :selected-index focused-index)))))

        class (dm/str class " " (stl/css-case :tabs true
                                              :tabs-collapsed-end (and (not open) (= button-position "end"))
                                              :tabs-collapsed (not open)))

        props (mf/spread-props props {:class class
                                      :ref parent-ref
                                      :on-key-down on-key-down})]

    [:> "article" props
    ;;  TODO: Check with design the need of these paddings
     [:> "div" {:class (stl/css :padding-wrapper)
                :style #js {"--tabs-nav-padding-end" nav-padding-end
                            "--tabs-nav-padding-start"  nav-padding-start}}
      [:> tab-nav* {:open open
                    :button-position button-position
                    :on-collapse on-collapse
                    :tabs tabs
                    :selected selected-index
                    :focused focused-index
                    :on-change-tab on-change-tab
                    :tabs-refs tabs-refs}]]

     (when open
       [:> tab-panel* {}
        panel-content])]))

