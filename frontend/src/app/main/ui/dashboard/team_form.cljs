;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(defn- on-create-success
  [_form response]
  (let [msg "Team created successfully"]
    (st/emit! (msg/success msg)
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id (:id response)}))))

(defn- on-update-success
  [_form _response]
  (let [msg "Team created successfully"]
    (st/emit! (msg/success msg)
              (modal/hide))))

(defn- on-error
  [form _response]
  (let [id  (get-in @form [:clean-data :id])]
    (if id
      (rx/of (msg/error "Error on updating team."))
      (rx/of (msg/error "Error on creating team.")))))

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
                             :validators [(fm/validate-not-empty :name (tr "auth.name.not-all-space"))
                                          (fm/validate-length :name fm/max-length-allowed (tr "auth.name.too-long"))]
                             :initial initial)
        handle-keydown
        (mf/use-callback
         (mf/deps)
         (fn [e]
           (when (kbd/enter? e)
             (dom/prevent-default e)
             (dom/stop-propagation e)
             (on-submit form e))))

        on-close #(st/emit! (modal/hide))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form
                   :on-submit on-submit
                   :class (stl/css :team-form)}

       [:div {:class (stl/css :modal-header)}
        (if team
          [:h2 {:class (stl/css :modal-title)}
           (tr "labels.rename-team")]
          [:h2 {:class (stl/css :modal-title)}
           (tr "labels.create-team")])

        [:button {:class (stl/css :modal-close-btn)
                  :on-click on-close} i/close-refactor]]

       [:div {:class (stl/css :modal-content)}
        [:& fm/input {:type "text"
                      :auto-focus? true
                      :class (stl/css :group-name-input)
                      :form form
                      :name :name
                      :placeholder "E.g. Design"
                      :label (tr "labels.create-team.placeholder")
                      :on-key-down handle-keydown}]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:> fm/submit-button*
          {:label (if team
                    (tr "labels.update-team")
                    (tr "labels.create-team"))
           :class (stl/css :accept-btn)}]]]]]]))


