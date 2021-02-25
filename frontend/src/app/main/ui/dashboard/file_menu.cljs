;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.main.ui.dashboard.file-menu
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [rumext.alpha :as mf]))

(mf/defc file-menu
  [{:keys [file show? on-edit on-menu-close top left] :as props}]
  (assert (some? file) "missing `file` prop")
  (assert (boolean? show?) "missing `show?` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-menu-close) "missing `on-menu-close` prop")
  (let [top  (or top 0)
        left (or left 0)

        on-new-tab
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (let [pparams {:project-id (:project-id file)
                          :file-id (:id file)}
                 qparams {:page-id (first (get-in file [:data :pages]))}]
             (st/emit! (rt/nav-new-window :workspace pparams qparams)))))

        delete-fn
        (mf/use-callback
         (mf/deps file)
         (st/emitf (dd/delete-file file)))

        on-delete
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-file-confirm.title")
                       :message (tr "modals.delete-file-confirm.message")
                       :accept-label (tr "modals.delete-file-confirm.accept")
                       :on-accept delete-fn}))))

        add-shared
        (mf/use-callback
         (mf/deps file)
         (st/emitf (dd/set-file-shared (assoc file :is-shared true))))

        del-shared
        (mf/use-callback
         (mf/deps file)
         (st/emitf (dd/set-file-shared (assoc file :is-shared false))))

        on-add-shared
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :message ""
                       :title (tr "modals.add-shared-confirm.message" (:name file))
                       :hint (tr "modals.add-shared-confirm.hint")
                       :cancel-label :omit
                       :accept-label (tr "modals.add-shared-confirm.accept")
                       :accept-style :primary
                       :on-accept add-shared}))))

        on-del-shared
        (mf/use-callback
         (mf/deps file)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :message ""
                       :title (tr "modals.remove-shared-confirm.message" (:name file))
                       :hint (tr "modals.remove-shared-confirm.hint")
                       :cancel-label :omit
                       :accept-label (tr "modals.remove-shared-confirm.accept")
                       :on-accept del-shared}))))]

    [:& context-menu {:on-close on-menu-close
                      :show show?
                      :fixed? (or (not= top 0) (not= left 0))
                      :top top
                      :left left
                      :options [[(tr "dashboard.open-in-new-tab") on-new-tab]
                                [(tr "labels.rename") on-edit]
                                [(tr "labels.delete") on-delete]
                                (if (:is-shared file)
                                  [(tr "dashboard.remove-shared") on-del-shared]
                                  [(tr "dashboard.add-shared") on-add-shared])]}]))

