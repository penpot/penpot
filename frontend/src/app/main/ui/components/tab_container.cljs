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
   [app.main.ui.icons :as deprecated-icon]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(set! *warn-on-infer* false)

(mf/defc tab-element
  {::mf/wrap-props false}
  [{:keys [children]}]
  children)

(mf/defc tab-container
  {::mf/wrap-props false}
  [{:keys [children selected on-change-tab collapsable handle-collapse header-class content-class]}]
  (let [children  (-> (array/normalize-to-array children)
                      (array/without-nils))

        selected* (mf/use-state #(or selected (-> children first .-props .-id)))
        selected  (or selected @selected*)

        on-click  (mf/use-fn
                   (mf/deps on-change-tab)
                   (fn [event]
                     (let [id (-> event
                                  (dom/get-current-target)
                                  (dom/get-data "id")
                                  (keyword))]
                       (reset! selected* id)
                       (when (fn? on-change-tab)
                         (on-change-tab id)))))]

    [:section {:class (stl/css :tab-container)}
     [:header {:class (dm/str header-class " " (stl/css :tab-container-tabs))}
      (when ^boolean collapsable
        [:button
         {:on-click handle-collapse
          :class (stl/css :collapse-sidebar)
          :aria-label (tr "workspace.sidebar.collapse")}
         deprecated-icon/arrow])
      [:div  {:class (stl/css :tab-container-tab-wrapper)}
       (for [tab children]
         (let [props (.-props tab)
               id    (.-id props)
               title (.-title props)
               sid   (d/name id)
               tooltip (if (string? title) title nil)]
           [:div {:key (str/concat "tab-" sid)
                  :title tooltip
                  :data-id sid
                  :data-testid sid
                  :on-click on-click
                  :class  (stl/css-case
                           :tab-container-tab-title true
                           :current (= selected id))}
            [:span {:class (stl/css :content)}
             title]]))]]

     [:div {:class (dm/str content-class " " (stl/css  :tab-container-content))}
      (d/seek #(= selected (-> % .-props .-id))
              children)]]))
