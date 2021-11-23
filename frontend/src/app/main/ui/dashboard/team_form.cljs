;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.team-form
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(defn- on-create-success
  [_form response]
  (let [msg "Team created successfully"]
    (st/emit! (dm/success msg)
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id (:id response)}))))

(defn- on-update-success
  [_form _response]
  (let [msg "Team created successfully"]
    (st/emit! (dm/success msg)
              (modal/hide))))

(defn- on-error
  [form _response]
  (let [id  (get-in @form [:clean-data :id])]
    (if id
      (rx/of (dm/error "Error on updating team."))
      (rx/of (dm/error "Error on creating team.")))))

(defn- on-create-submit
  [form]
  (let [mdata  {:on-success (partial on-create-success form)
                :on-error   (partial on-error form)}
        params {:name (get-in @form [:clean-data :name])}]
    (st/emit! (dd/create-team (with-meta params mdata)))))

(defn- on-update-submit
  [form]
  (let [mdata  {:on-success (partial on-update-success form)
                :on-error   (partial on-error form)}
        team   (get @form :clean-data)]
    (st/emit! (dd/update-team (with-meta team mdata))
              (modal/hide))))

(defn- on-submit
  [form _]
  (let [data (:clean-data @form)]
    (if (:id data)
      (on-update-submit form)
      (on-create-submit form))))

(mf/defc team-form-modal {::mf/register modal/components
   ::mf/register-as :team-form}
  [{:keys [team] :as props}]
  (let [initial (mf/use-memo (fn [] (or team {})))
        form    (fm/use-form :spec ::team-form
                             :initial initial)]
    [:div.modal-overlay
     [:div.modal-container.team-form-modal
      [:& fm/form {:form form :on-submit on-submit}

       [:div.modal-header
        [:div.modal-header-title
         (if team
           [:h2 (tr "labels.rename-team")]
           [:h2 (tr "labels.create-team")])]

        [:div.modal-close-button
         {:on-click (st/emitf (modal/hide))} i/close]]

       [:div.modal-content.generic-form
        [:& fm/input {:type "text"
                      :auto-focus? true
                      :form form
                      :name :name
                      :label (tr "labels.create-team.placeholder")}]]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button
          {:label (if team
                    (tr "labels.update-team")
                    (tr "labels.create-team"))}]]]]]]))


