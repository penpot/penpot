;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.delete-shared
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private noop (constantly nil))

(mf/defc delete-shared-dialog
  {::mf/register modal/components
   ::mf/register-as :delete-shared-libraries
   ::mf/wrap-props false}
  [{:keys [ids on-accept on-cancel accept-style origin count-libraries]}]
  (let [references*  (mf/use-state nil)
        references   (deref references*)

        on-accept    (or on-accept noop)
        on-cancel    (or on-cancel noop)

        cancel-label (tr "labels.cancel")
        accept-style (or accept-style :danger)

        count-files  (count (keys references))

        title        (case origin
                       :delete    (tr "modals.delete-shared-confirm.title" (i18n/c count-libraries))
                       :unpublish (tr "modals.unpublish-shared-confirm.title" (i18n/c count-libraries))
                       :move      (tr "modals.move-shared-confirm.title" (i18n/c count-libraries)))

        subtitle     (case origin
                       :delete    (tr "modals.delete-shared-confirm.message" (i18n/c count-libraries))
                       :unpublish (tr "modals.unpublish-shared-confirm.message" (i18n/c count-libraries))
                       :move      (tr "modals.move-shared-confirm.message" (i18n/c count-libraries)))

        accept-label (case origin
                       :delete    (tr "modals.delete-shared-confirm.accept" (i18n/c count-libraries))
                       :unpublish (tr "modals.unpublish-shared-confirm.accept" (i18n/c count-libraries))
                       :move      (tr "modals.move-shared-confirm.accept" (i18n/c count-libraries)))

        no-files-msg (tr "modals.delete-shared-confirm.activated.no-files-message" (i18n/c count-libraries))

        scd-msg      (tr "modals.delete-shared-confirm.activated.scd-message" (i18n/c count-libraries))

        hint         (tr "modals.delete-unpublish-shared-confirm.activated.hint" (i18n/c count-files))

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
           (rx/filter some?)
           (rx/mapcat #(rp/cmd! :get-library-file-references {:file-id %}))
           (rx/mapcat identity)
           (rx/map (juxt :id :name))
           (rx/reduce conj [])
           (rx/subs! #(reset! references* %))))

    (mf/with-effect [accept-fn]
      (letfn [(on-keydown [event]
                (when (k/enter? event)
                  (dom/prevent-default event)
                  (dom/stop-propagation event)
                  (accept-fn)))]
        (let [key (events/listen js/document "keydown" on-keydown)]
          (partial events/unlistenByKey key))))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} title]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click cancel-fn} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       (when (and (string? subtitle) (not= subtitle ""))
         [:h3 {:class (stl/css :modal-subtitle)}  subtitle])
       (when (not= 0 count-libraries)
         (if (pos? (count references))
           [:*
            [:div
             (when (and (string? scd-msg) (not= scd-msg ""))
               [:h3 {:class (stl/css :modal-scd-msg)} scd-msg])
             [:ul {:class (stl/css :element-list)}
              (for [[file-id file-name] references]
                [:li {:class (stl/css :list-item)
                      :key (dm/str file-id)}
                 [:span "- " file-name]])]]
            (when (and (string? hint) (not= hint ""))
              [:> context-notification* {:level :info
                                         :appearance :ghost}
               hint])]
           [:*
            [:h3 {:class (stl/css :modal-msg)} no-files-msg]]))]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        (when-not (= cancel-label :omit)
          [:input {:class (stl/css :cancel-button)
                   :type "button"
                   :value cancel-label
                   :on-click cancel-fn}])

        [:input {:class (stl/css-case :accept-btn true
                                      :danger (= accept-style :danger)
                                      :primary (= accept-style :primary))
                 :type "button"
                 :value accept-label
                 :on-click accept-fn}]]]]]))
