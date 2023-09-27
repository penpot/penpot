;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.tab-container
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc tab-element
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        new-css-system (mf/use-ctx ctx/new-css-system)]
    [:div {:class (stl/css new-css-system :tab-element)}
     children]))

(mf/defc tab-container
  {::mf/wrap-props false}
  [props]
  (let [children        (->>
                         (unchecked-get props "children")
                         (filter some?))
        selected        (unchecked-get props "selected")
        on-change       (unchecked-get props "on-change-tab")
        collapsable?    (unchecked-get props "collapsable?")
        handle-collapse (unchecked-get props "handle-collapse")
        class           (unchecked-get props "class")
        content-class   (unchecked-get props "content-class")

        state           (mf/use-state #(or selected (-> children first .-props .-id)))
        selected        (or selected @state)

        select-fn
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [id (-> event
                        (dom/get-current-target)
                        (dom/get-data "id")
                        (keyword))]
             (reset! state id)
             (when (fn? on-change) (on-change id)))))]

    [:div {:class (stl/css :tab-container)}
     [:div {:class (dm/str class " "(stl/css :tab-container-tabs))}
      (when collapsable?
        [:button
         {:on-click handle-collapse
          :class (stl/css :collapse-sidebar)
          :aria-label (tr "workspace.sidebar.collapse")}
         i/arrow-refactor])
      [:div  {:class (stl/css :tab-container-tab-wrapper)}
       (for [tab children]
         (let [props (.-props tab)
               id    (.-id props)
               title (.-title props)]
           [:div
            {:key (str/concat "tab-" (d/name id))
             :data-id (d/name id)
             :on-click select-fn
             :class  (stl/css-case :tab-container-tab-title true
                                     :current (= selected id))}
            title]))]]
     [:div {:class (dm/str content-class " " (stl/css  :tab-container-content ))}
      (d/seek #(= selected (-> % .-props .-id)) children)]]))
