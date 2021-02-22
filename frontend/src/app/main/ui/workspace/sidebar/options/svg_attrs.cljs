;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.svg-attrs
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.common :as dwc]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc attribute-value [{:keys [attr value on-change] :as props}]
  (let [handle-change
        (mf/use-callback
         (mf/deps attr on-change)
         (fn [event]
           (on-change attr (dom/get-target-val event))))]
    [:div.element-set-content
     [:& input-row {:label (name attr)
                    :type :text
                    :class "large"
                    :value (str value)
                    :on-change handle-change}]]))

(mf/defc svg-attrs-menu [{:keys [ids type values]}]
  (let [handle-change
        (mf/use-callback
         (mf/deps ids)
         (fn [attr value]
           (let [update-fn
                 (fn [shape] (assoc-in shape [:svg-attrs attr] value))]

             (st/emit! (dwc/update-shapes ids update-fn)))))]

    (when-not (empty? (:svg-attrs values))
      [:div.element-set
       [:div.element-set-title
        [:span (tr "workspace.sidebar.options.svg-attrs.title")]]

       (for [[index [attr-key attr-value]] (d/enumerate (:svg-attrs values))]
         [:& attribute-value {:key attr-key
                              :ids ids
                              :attr attr-key
                              :value attr-value
                              :on-change handle-change}])])))
