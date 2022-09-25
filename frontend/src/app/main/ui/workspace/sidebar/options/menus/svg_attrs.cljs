;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.svg-attrs
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc attribute-value [{:keys [attr value on-change on-delete] :as props}]
  (let [handle-change
        (mf/use-callback
         (mf/deps attr on-change)
         (fn [event]
           (on-change attr (dom/get-target-val event))))

        handle-delete
        (mf/use-callback
         (mf/deps attr on-delete)
         (fn []
           (on-delete attr)))

        label (->> attr last d/name)]
    [:div.element-set-content
     (if (string? value)
       [:div.row-flex.row-flex-removable
        [:& input-row {:label label
                       :type :text
                       :class "large"
                       :value (str value)
                       :on-change handle-change}]
        [:div.element-set-actions
         [:div.element-set-actions-button {:on-click handle-delete}
          i/minus]]]

       [:*
        [:div.element-set-title
         {:style {:border-bottom "1px solid #444" :margin-bottom "0.5rem"}}
         [:span (str (d/name (last attr)))]]

        (for [[key value] value]
          [:& attribute-value {:key key
                               :attr (conj attr key)
                               :value value
                               :on-change on-change
                               :on-delete on-delete}])])]))

(mf/defc svg-attrs-menu [{:keys [ids values]}]
  (let [handle-change
        (mf/use-callback
         (mf/deps ids)
         (fn [attr value]
           (let [update-fn
                 (fn [shape] (assoc-in shape (concat [:svg-attrs] attr) value))]
             (st/emit! (dch/update-shapes ids update-fn)))))

        handle-delete
        (mf/use-callback
         (mf/deps ids)
         (fn [attr]
           (let [update-fn
                 (fn [shape]
                   (let [update-path (concat [:svg-attrs] (butlast attr))
                         shape (update-in shape update-path dissoc (last attr))

                         shape (cond-> shape
                                 (empty? (get-in shape [:svg-attrs :style]))
                                 (update :svg-attrs dissoc :style))]
                     shape))]
             (st/emit! (dch/update-shapes ids update-fn)))))

        ]

    (when-not (empty? (:svg-attrs values))
      [:div.element-set
       [:div.element-set-title
        [:span (tr "workspace.sidebar.options.svg-attrs.title")]]

       (for [[attr-key attr-value] (:svg-attrs values)]
         [:& attribute-value {:key attr-key
                              :attr [attr-key]
                              :value attr-value
                              :on-change handle-change
                              :on-delete handle-delete}])])))
