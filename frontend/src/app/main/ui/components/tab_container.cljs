;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.tab-container
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc tab-element
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")]
    [:div.tab-element
     [:div.tab-element-content children]]))

(mf/defc tab-container
  {::mf/wrap-props false}
  [props]
  (let [children  (unchecked-get props "children")
        selected  (unchecked-get props "selected")
        on-change (unchecked-get props "on-change-tab")

        state     (mf/use-state #(or selected (-> children first .-props .-id)))
        selected  (or selected @state)

        select-fn
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [id (d/read-string (.. event -target -dataset -id))]
             (reset! state id)
             (when (fn? on-change) (on-change id)))))]

    [:div.tab-container
     [:div.tab-container-tabs
      (for [tab children]
        (let [props (.-props tab)
              id    (.-id props)
              title (.-title props)]
          [:div.tab-container-tab-title
           {:key (str/concat "tab-" (d/name id))
            :data-id (pr-str id)
            :on-click select-fn
            :class (when (= selected id) "current")}
           title]))]
     [:div.tab-container-content
      (d/seek #(= selected (-> % .-props .-id)) children)]]))
