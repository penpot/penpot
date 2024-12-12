;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.change-owner
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:leave-modal-form
  [:map {:title "LeaveModalForm"}
   [:member-id ::sm/uuid]])

(mf/defc leave-and-reassign-modal
  {::mf/register modal/components
   ::mf/register-as :leave-and-reassign}
  [{:keys [profile team accept]}]
  (let [form        (fm/use-form :schema schema:leave-modal-form :initial {})
        members     (get team :members)

        options
        (into [{:value ""
                :label (tr "modals.leave-and-reassign.select-member-to-promote")}]
              (comp
               (filter #(not= (:email %) (:email profile)))
               (map #(hash-map :label (:name %) :value (str (:id %)))))
              members)

        on-cancel   #(st/emit! (modal/hide))
        on-accept
        (fn [_]
          (let [member-id (get-in @form [:clean-data :member-id])]
            (accept member-id)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.leave-and-reassign.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} i/close]]

      [:div {:class (stl/css :modal-content)}
       [:p {:class (stl/css :modal-msg)}
        (tr "modals.leave-and-reassign.hint1" (:name team))]

       (if (empty? members)
         [:p {:class (stl/css :modal-msg)}
          (tr "modals.leave-and-reassign.forbidden")]
         [:*
          [:& fm/form {:form form}
           [:& fm/select {:name :member-id
                          :options options}]]])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css :cancel-button)
                 :type "button"
                 :value (tr "labels.cancel")
                 :on-click on-cancel}]

        [:input.accept-button
         {:type "button"
          :class (stl/css-case  :accept-btn true
                                :danger (:valid @form)
                                :global/disabled (not (:valid @form)))
          :disabled (not (:valid @form))
          :value (tr "modals.leave-and-reassign.promote-and-leave")
          :on-click on-accept}]]]]]))
