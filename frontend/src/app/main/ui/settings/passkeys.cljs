;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.passkeys
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:passkeys
  (l/derived :passkeys st/state))

(mf/defc passkeys-hero
  []
  (let [on-click (mf/use-fn #(st/emit! (du/create-passkey)))]
    [:div.passkeys-hero-container
     [:div.passkeys-hero
      [:div.desc
       [:h2 (tr "dashboard.passkeys.title")]
       [:p (tr "dashboard.passkeys.description")]]

      [:button.btn-primary
       {:on-click on-click}
       [:span (tr "dashboard.passkeys.create")]]]]))

(mf/defc passkey-actions
  [{:keys [on-delete]}]
  (let [show*    (mf/use-state false)
        show?    (deref show*)
        menu-ref (mf/use-ref)

        menu-options
        (mf/with-memo [on-delete]
          [{:option-name    (tr "labels.delete")
            :id             "passkey-delete"
            :option-handler on-delete}])

        on-menu-close
        (mf/use-fn #(reset! show* false))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! show* true)))

        on-key-down
        (mf/use-fn
         (mf/deps on-menu-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-menu-click event))))]

    [:div.icon
     {:tab-index "0"
      :ref menu-ref
      :on-click on-menu-click
      :on-key-down on-key-down}

     i/actions
     [:& context-menu-a11y
      {:on-close on-menu-close
       :show show?
       :fixed? true
       :min-width? true
       :top "auto"
       :left "auto"
       :options menu-options}]]))

(mf/defc passkey-item
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [passkey]}]
  (let [locale      (mf/deref i18n/locale)
        created-at  (dt/format-date-locale (:created-at passkey) {:locale locale})
        passkey-id  (:id passkey)
        sign-count  (:sign-count passkey)

        on-delete-accept
        (mf/use-fn
         (mf/deps passkey-id)
         (fn []
           (let [params {:id passkey-id}
                 mdata  {:on-success #(st/emit! (du/fetch-passkeys))}]
             (st/emit! (du/delete-passkey (with-meta params mdata))))))

        on-delete
        (mf/use-fn
         (mf/deps on-delete-accept)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-passkey.title")
                       :message (tr "modals.delete-passkey.message")
                       :accept-label (tr "modals.delete-passkey.accept")
                       :on-accept on-delete-accept}))))]

    [:div.table-row
     [:div.table-field.name
      (uuid/uuid->short-id passkey-id)]
     [:div.table-field.create-date
      [:span.content created-at]]
     [:div.table-field.sign-count
      [:span.content sign-count]]

     [:div.table-field.actions
      [:& passkey-actions
       {:on-delete on-delete}]]]))

(mf/defc passkeys-page
  []
  (let [passkeys (mf/deref ref:passkeys)]

    (mf/with-effect []
      (dom/set-html-title (tr "dashboard.password.page-title"))
      (st/emit! (du/fetch-passkeys)))

    [:div.dashboard-passkeys
     [:div
      [:& passkeys-hero]
      (if (empty? passkeys)
        [:div.passkeys-empty
         [:div (tr "dashboard.passkeys.empty.no-passkeys")]
         [:div (tr "dashboard.passkeys.empty.add-one")]]
        [:div.dashboard-table
         [:div.table-rows
          (for [{:keys [id] :as item} passkeys]
            [:& passkey-item {:passkey item :key (dm/str id)}])]])]]))


