;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.export
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(def ^:const options [:all :merge :detach])

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as :export}
  [{:keys [team-id files]}]
  (let [selected-option (mf/use-state :all)

        cancel-fn
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))))

        accept-fn
        (mf/use-callback
         (mf/deps @selected-option)
         (fn [event]
           (dom/prevent-default event)
           
           (->> (uw/ask-many!
                 {:cmd :export-file
                  :team-id team-id
                  :export-type @selected-option
                  :files files})
                (rx/subs
                 (fn [msg]
                   (when  (= :finish (:type msg))
                     (dom/trigger-download-uri (:filename msg) (:mtype msg) (:uri msg))))))

           (st/emit! (modal/hide))))

        on-change-handler
        (mf/use-callback
         (fn [_ type]
           (reset! selected-option type)))]

    [:div.modal-overlay
     [:div.modal-container.export-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.export.title")]]

       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      [:div.modal-content
       [:p.explain (tr "dashboard.export.explain")]
       [:p.detail (tr "dashboard.export.detail")]

       (for [type [:all :merge :detach]]
         (let [selected? (= @selected-option type)]
           [:div.export-option {:class (when selected? "selected")}
            [:label.option-container
             [:h3 (tr (str "dashboard.export.options." (d/name type) ".title"))] 
             [:p  (tr (str "dashboard.export.options." (d/name type) ".message"))]
             [:input {:type "radio"
                      :checked selected?
                      :on-change #(on-change-handler % type)
                      :name "export-option"}]
             [:span {:class "option-radio-check"}]]]))]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click cancel-fn}]

        [:input.accept-button
         {:class "primary"
          :type "button"
          :value (tr "labels.export")
          :on-click accept-fn}]]]]]))
