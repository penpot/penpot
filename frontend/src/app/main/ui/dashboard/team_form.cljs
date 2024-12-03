;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private schema:team-form
  [:map {:title "TeamForm"}
   [:name [::sm/text {:max 250}]]])

(defn- on-create-success
  [_form response]
  (let [message "Team created successfully"
        team-id (:id response)]
    (st/emit! (ntf/success message)
              (dcm/go-to-dashboard-recent :team-id team-id))))

(defn- on-update-success
  [_form _response]
  (let [message "Team created successfully"]
    (st/emit! (ntf/success message)
              (modal/hide))))

(defn- on-error
  [form _response]
  (let [id  (get-in @form [:clean-data :id])]
    (if id
      (rx/of (ntf/error "Error on updating team."))
      (rx/of (ntf/error "Error on creating team.")))))

(defn- on-create-submit
  [form]
  (let [mdata  {:on-success (partial on-create-success form)
                :on-error   (partial on-error form)}
        params {:name (get-in @form [:clean-data :name])}]
    (st/emit! (-> (dtm/create-team (with-meta params mdata))
                  (with-meta {::ev/origin :dashboard})))))

(defn- on-update-submit
  [form]
  (let [mdata  {:on-success (partial on-update-success form)
                :on-error   (partial on-error form)}
        team   (get @form :clean-data)]
    (st/emit! (dtm/update-team (with-meta team mdata))
              (modal/hide))))

(defn- on-submit
  [form _]
  (let [data (:clean-data @form)]
    (if (:id data)
      (on-update-submit form)
      (on-create-submit form))))

(mf/defc team-form-modal
  {::mf/register modal/components
   ::mf/register-as :team-form}
  [{:keys [team] :as props}]
  (let [initial (mf/use-memo (fn []
                               (or (some-> team (select-keys [:name :id]))
                                   {})))
        form    (fm/use-form :schema schema:team-form
                             :initial initial)
        handle-keydown
        (mf/use-fn
         (fn [e]
           (when (kbd/enter? e)
             (dom/prevent-default e)
             (dom/stop-propagation e)
             (on-submit form e))))

        on-close
        (mf/use-fn #(st/emit! (modal/hide)))]

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
                  :on-click on-close} i/close]]

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


