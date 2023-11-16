;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.change-owner
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::member-id ::us/uuid)
(s/def ::leave-modal-form
  (s/keys :req-un [::member-id]))

(mf/defc leave-and-reassign-modal
  {::mf/register modal/components
   ::mf/register-as :leave-and-reassign}
  [{:keys [profile team accept]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        form        (fm/use-form :spec ::leave-modal-form :initial {})
        members-map (mf/deref refs/dashboard-team-members)
        members     (vals members-map)

        options     (into [{:value ""
                            :label (tr "modals.leave-and-reassign.select-member-to-promote")}]
                          (filter #(not= (:label %) (:fullname profile))
                                  (map #(hash-map :label (:name %) :value (str (:id %))) members)))

        on-cancel   #(st/emit! (modal/hide))
        on-accept
        (fn [_]
          (let [member-id (get-in @form [:clean-data :member-id])]
            (accept member-id)))]

    (if new-css-system
      [:div {:class (stl/css :modal-overlay)}
       [:div {:class (stl/css :modal-container)}
        [:div {:class (stl/css :modal-header)}
         [:h2 {:class (stl/css :modal-title)} (tr "modals.leave-and-reassign.title")]
         [:button {:class (stl/css :modal-close-btn)
                   :on-click on-cancel} i/close-refactor]]

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
            :on-click on-accept}]]]]]


      [:div.modal-overlay
       [:div.modal-container.confirm-dialog
        [:div.modal-header
         [:div.modal-header-title
          [:h2 (tr "modals.leave-and-reassign.title")]]
         [:div.modal-close-button
          {:on-click on-cancel} i/close]]

        [:div.modal-content.generic-form
         [:p (tr "modals.leave-and-reassign.hint1" (:name team))]

         (if (empty? members)
           [:p (tr "modals.leave-and-reassign.forbidden")]
           [:*
            [:& fm/form {:form form}
             [:& fm/select {:name :member-id
                            :options options}]]])]

        [:div.modal-footer
         [:div.action-buttons
          [:input.cancel-button
           {:type "button"
            :value (tr "labels.cancel")
            :on-click on-cancel}]

          [:input.accept-button
           {:type "button"
            :class (if (:valid @form) "danger" "btn-disabled")
            :disabled (not (:valid @form))
            :value (tr "modals.leave-and-reassign.promote-and-leave")
            :on-click on-accept}]]]]])))
