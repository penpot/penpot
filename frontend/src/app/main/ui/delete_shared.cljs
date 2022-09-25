;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.delete-shared
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(mf/defc delete-shared-dialog
  {::mf/register modal/components
   ::mf/register-as :delete-shared}
  [{:keys [on-accept
           on-cancel
           accept-style
           origin
           count-libraries] :as props}]
  (let [on-accept    (or on-accept identity)
        on-cancel    (or on-cancel identity)
        cancel-label (tr "labels.cancel")
        accept-style (or accept-style :danger)
        is-delete? (= origin :delete)
        dashboard-local  (mf/deref refs/dashboard-local)
        files->shared (:files-with-shared dashboard-local)
        count-files (count (keys files->shared))
        title (if is-delete?
                (tr "modals.delete-shared-confirm.title" (i18n/c count-libraries))
                (tr "modals.unpublish-shared-confirm.title" (i18n/c count-libraries)))
        message (if is-delete?
                  (tr "modals.delete-shared-confirm.message" (i18n/c count-libraries))
                  (tr "modals.unpublish-shared-confirm.message" (i18n/c count-libraries)))
        accept-label (if is-delete?
                       (tr "modals.delete-shared-confirm.accept" (i18n/c count-libraries))
                       (tr "modals.unpublish-shared-confirm.accept"))
        scd-message (if is-delete? 
                      (if (> count-libraries 1)
                        (tr "modals.delete-shared-confirm.scd-message-plural" (i18n/c count-files))
                        (tr "modals.delete-shared-confirm.scd-message" (i18n/c count-files)))
                      (if (> count-libraries 1)
                        (tr "modals.unpublish-shared-confirm.scd-message-plural" (i18n/c count-files))
                        (tr "modals.unpublish-shared-confirm.scd-message" (i18n/c count-files)))
                      )
        hint  (if is-delete?
                ""
                (tr "modals.unpublish-shared-confirm.hint" (i18n/c count-files)))

        accept-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept props)))

        cancel-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-cancel props)))]

    (mf/with-effect
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (st/emit! (modal/hide))
                  (on-accept props)))]
        (->> (events/listen js/document EventType.KEYDOWN on-keydown)
             (partial events/unlistenByKey)))
      #(st/emit! (dd/clean-temp-shared)))

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 title]]
       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      [:div.modal-content.delete-shared
       (when (and (string? message) (not= message ""))
         [:h3 message])

       (when (> (count files->shared) 0)
         [:*
          [:div
           (when (and (string? scd-message) (not= scd-message ""))
             [:h3 scd-message])
           [:ul.file-list
            (for [[id file] files->shared]
              [:li.modal-item-element
               {:key id}
               [:span "- " (:name file)]])]]
          (when (and (string? hint) (not= hint ""))
            [:h3 hint])])]

      [:div.modal-footer
       [:div.action-buttons
        (when-not (= cancel-label :omit)
          [:input.cancel-button
           {:type "button"
            :value cancel-label
            :on-click cancel-fn}])

        [:input.accept-button
         {:class (dom/classnames
                  :danger (= accept-style :danger)
                  :primary (= accept-style :primary))
          :type "button"
          :value accept-label
          :on-click accept-fn}]]]]]))
