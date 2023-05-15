;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.delete-shared
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private noop (constantly nil))

(mf/defc delete-shared-dialog
  {::mf/register modal/components
   ::mf/register-as :delete-shared-libraries
   ::mf/wrap-props false}
  [{:keys [ids on-accept on-cancel accept-style origin count-libraries]}]
  (let [references*  (mf/use-state {})
        references   (deref references*)

        on-accept    (or on-accept noop)
        on-cancel    (or on-cancel noop)

        cancel-label (tr "labels.cancel")
        accept-style (or accept-style :danger)

        is-delete?   (= origin :delete)
        count-files  (count (keys references))

        title        (if ^boolean is-delete?
                       (tr "modals.delete-shared-confirm.title" (i18n/c count-libraries))
                       (tr "modals.unpublish-shared-confirm.title" (i18n/c count-libraries)))

        subtitle     (if ^boolean is-delete?
                       (tr "modals.delete-shared-confirm.message" (i18n/c count-libraries))
                       (tr "modals.unpublish-shared-confirm.message" (i18n/c count-libraries)))

        accept-label (if ^boolean is-delete?
                       (tr "modals.delete-shared-confirm.accept" (i18n/c count-libraries))
                       (tr "modals.unpublish-shared-confirm.accept" (i18n/c count-libraries)))

        no-files-msg (if ^boolean is-delete?
                       (tr "modals.delete-shared-confirm.no-files-message" (i18n/c count-libraries))
                       (tr "modals.unpublish-shared-confirm.no-files-message" (i18n/c count-libraries)))

        scd-msg      (if ^boolean is-delete?
                       (if (= count-files 1)
                         (tr "modals.delete-shared-confirm.scd-message" (i18n/c count-libraries))
                         (tr "modals.delete-shared-confirm.scd-message-many" (i18n/c count-libraries)))
                       (if (= count-files 1)
                         (tr "modals.unpublish-shared-confirm.scd-message" (i18n/c count-libraries))
                         (tr "modals.unpublish-shared-confirm.scd-message-many" (i18n/c count-libraries))))
        hint         (if ^boolean is-delete?
                       (if (= count-files 1)
                         (tr "modals.delete-shared-confirm.hint" (i18n/c count-libraries))
                             (tr "modals.delete-shared-confirm.hint-many" (i18n/c count-libraries)))
                       (if (= count-files 1)
                         (tr "modals.unpublish-shared-confirm.hint" (i18n/c count-libraries))
                         (tr "modals.unpublish-shared-confirm.hint-many" (i18n/c count-libraries))))

        accept-fn
        (mf/use-fn
         (mf/deps on-accept)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept)))

        cancel-fn
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-cancel)))]

    (mf/with-effect [ids]
      (->> (rx/from ids)
           (rx/map #(array-map :file-id %))
           (rx/mapcat #(rp/cmd! :get-library-file-references %))
           (rx/mapcat identity)
           (rx/map (juxt :id :name))
           (rx/reduce conj [])
           (rx/subs #(reset! references* %))))

    (mf/with-effect [accept-fn]
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (accept-fn)))]
        (let [key (events/listen js/document "keydown" on-keydown)]
          (partial events/unlistenByKey key))))

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 title]]
       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      [:div.modal-content.delete-shared
       (when (and (string? subtitle) (not= subtitle ""))
         [:h3 subtitle])
       (when (not= 0 count-libraries)
         (if (pos? (count references))
           [:*
            [:div
             (when (and (string? scd-msg) (not= scd-msg ""))
               [:h3 scd-msg])
             [:ul.file-list
              (for [[file-id file-name] references]
                [:li.modal-item-element
                 {:key (dm/str file-id)}
                 [:span "- " file-name]])]]
            (when (and (string? hint) (not= hint ""))
              [:h3 hint])]
           [:*
            [:h3 no-files-msg]]))]

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
