;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.svg-attrs
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc attribute-value [{:keys [attr value on-change on-delete] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        handle-change
        (mf/use-fn
         (mf/deps attr on-change)
         (fn [event]
           (on-change attr (dom/get-target-val event))))

        handle-delete
        (mf/use-fn
         (mf/deps attr on-delete)
         (fn []
           (on-delete attr)))

        label (->> attr last d/name)]
    (if new-css-system
      [:*
       (if (string? value)
         [:div {:class (stl/css :attr-content)}
          [:span {:class (stl/css :attr-name)} label]
          [:div  {:class (stl/css :attr-input)}
           [:input {:value value
                    :class "input-text"
                    :on-change handle-change}]]
          [:div  {:class (stl/css :attr-actions)}
           [:button {:class (stl/css :attr-action-btn)
                     :on-click handle-delete}
            i/remove-refactor]]]
         [:div  {:class (stl/css :attr-row)}
          [:span  {:class (stl/css :attr-title)}
           (str (d/name (last attr)))]

          (for [[key value] value]
            [:& attribute-value {:key key
                                 :attr (conj attr key)
                                 :value value
                                 :on-change on-change
                                 :on-delete on-delete}])])]


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
                                 :on-delete on-delete}])])])))

(mf/defc svg-attrs-menu [{:keys [ids values]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        state*          (mf/use-state true)
        open?           (deref state*)
        attrs           (:svg-attrs values)
        has-attributes? (or (= :multiple attrs) (some? (seq attrs)))

        toggle-content  (mf/use-fn #(swap! state* not))
        handle-change
        (mf/use-fn
         (mf/deps ids)
         (fn [attr value]
           (let [update-fn
                 (fn [shape] (assoc-in shape (concat [:svg-attrs] attr) value))]
             (st/emit! (dch/update-shapes ids update-fn)))))

        handle-delete
        (mf/use-fn
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
             (st/emit! (dch/update-shapes ids update-fn)))))]

    (when-not (empty? attrs)
      (if new-css-system
        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-set-title)}
          [:& title-bar {:collapsable? has-attributes?
                         :collapsed?   (not open?)
                         :on-collapsed toggle-content
                         :title        (tr "workspace.sidebar.options.svg-attrs.title")
                         :class        (stl/css-case :title-spacing-svg-attrs (not has-attributes?))}]]
         [:div {:class (stl/css :element-set-content)}
          (for [[attr-key attr-value] attrs]
            [:& attribute-value {:key attr-key
                                 :attr [attr-key]
                                 :value attr-value
                                 :on-change handle-change
                                 :on-delete handle-delete}])]]

        [:div.element-set
         [:div.element-set-title
          [:span (tr "workspace.sidebar.options.svg-attrs.title")]]

         (for [[attr-key attr-value] attrs]
           [:& attribute-value {:key attr-key
                                :attr [attr-key]
                                :value attr-value
                                :on-change handle-change
                                :on-delete handle-delete}])]))))
