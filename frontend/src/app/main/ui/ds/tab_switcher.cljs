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
  [{:keys [selected icon label aria-label id tab-ref] :rest props}]

  (let [class (stl/css-case :tab true
                            :selected selected)
        props (mf/spread-props props {:class class
                                      :role "tab"
                                      :aria-selected selected
                                      :title (or label aria-label)
                                      :tab-index  (if selected nil -1)
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
  [{:keys [tabs-refs tabs selected on-click button-position action-button] :rest props}]
  (let [class (stl/css-case :tab-nav true
                            :tab-nav-start (= "start" button-position)
                            :tab-nav-end (= "end" button-position))
        props (mf/spread-props props {:class (stl/css :tab-list)
                                      :role "tablist"
                                      :aria-orientation "horizontal"})]
    [:> "nav" {:class class}
     (when (= button-position "start")
       action-button)

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
                    :on-click   on-click
                    :id         id
                    :tab-ref    (nth tabs-refs index)}]))]

     (when (= button-position "end")
       action-button)]))

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

(def ^:private positions (set '("start" "end")))

(defn- valid-button-position? [position button]
  (or (nil? position) (and (contains? positions position) (some? button))))

(mf/defc tab-switcher*
  {::mf/props :obj}
  [{:keys [class tabs on-change-tab default-selected action-button-position action-button] :rest props}]
  ;; TODO: Use a schema to assert the tabs prop -> https://tree.taiga.io/project/penpot/task/8521
  (assert (valid-tabs? tabs) "unexpected props for tab-switcher")
  (assert (valid-button-position? action-button-position action-button) "invalid action-button-position")
  (let [tab-ids           (mapv #(obj/get % "id") tabs)

        active-tab-index* (mf/use-state (or (d/index-of tab-ids default-selected) 0))
        active-tab-index  (deref active-tab-index*)

        tabs-refs         (mapv (fn [_] (mf/use-ref)) tabs)

        active-tab        (nth tabs active-tab-index)
        panel-content     (obj/get active-tab "content")

        handle-click
        (mf/use-fn
         (mf/deps on-change-tab tab-ids)
         (fn [event]
           (let [id (dom/get-data (dom/get-current-target event) "id")
                 index (d/index-of tab-ids id)]
             (reset! active-tab-index* index)

             (when (fn? on-change-tab)
               (on-change-tab id)))))

        on-key-down
        (mf/use-fn
         (mf/deps tabs-refs active-tab-index)
         (fn [event]
           (let [len (count tabs-refs)
                 index (cond
                         (kbd/home? event) 0
                         (kbd/left-arrow? event) (mod (- active-tab-index 1) len)
                         (kbd/right-arrow? event) (mod (+ active-tab-index 1) len))]
             (when index
               (reset! active-tab-index* index)
               (dom/focus! (mf/ref-val (nth tabs-refs index)))))))

        class (dm/str class " " (stl/css :tabs))

        props (mf/spread-props props {:class class
                                      :on-key-down on-key-down})]

    [:> "article" props
     [:> "div" {:class (stl/css :padding-wrapper)}
      [:> tab-nav* {:button-position action-button-position
                    :action-button action-button
                    :tabs tabs
                    :selected active-tab-index
                    :on-click handle-click
                    :tabs-refs tabs-refs}]]

     [:> tab-panel* {:tab-index 0}
      panel-content]]))

