;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.export
  (:require
   [app.common.data :as d]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(def ^:const options [:all :merge :detach])

(mf/defc export-entry
  [{:keys [file]}]

  [:div.file-entry
   {:class (dom/classnames
            :loading  (:loading? file)
            :success  (:export-success? file)
            :error    (:export-error? file))}
   [:div.file-name
    [:div.file-icon
     (cond (:export-success? file) i/tick
           (:export-error? file)   i/close
           (:loading? file)        i/loader-pencil)]

    [:div.file-name-label (:name file)]]])

(defn mark-file-error [files file-id]
  (->> files
       (mapv #(cond-> %
                (= file-id (:id %))
                (assoc :export-error? true
                       :loading? false)))))

(defn mark-file-success [files file-id]
  (->> files
       (mapv #(cond-> %
                (= file-id (:id %))
                (assoc :export-success? true
                       :loading? false)))))

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as :export}
  [{:keys [team-id files has-libraries?]}]
  (let [state (mf/use-state {:status :prepare
                             :files  (->> files (mapv #(assoc % :loading? true)))})
        selected-option (mf/use-state :all)

        start-export
        (fn []
          (st/emit! (ptk/event ::ev/event {::ev/name "export-files"
                                           :num-files (count (:files @state))
                                           :option @selected-option}))

          (swap! state assoc :status :exporting)
          (->> (uw/ask-many!
                {:cmd :export-file
                 :team-id team-id
                 :export-type @selected-option
                 :files (->> files (mapv :id))})
               (rx/delay-emit 1000)
               (rx/subs
                (fn [msg]
                  (when  (= :error (:type msg))
                    (swap! state update :files mark-file-error (:file-id msg)))

                  (when  (= :finish (:type msg))
                    (swap! state update :files mark-file-success (:file-id msg))
                    (dom/trigger-download-uri (:filename msg) (:mtype msg) (:uri msg)))))))
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
           (start-export)))

        on-change-handler
        (mf/use-callback
         (fn [_ type]
           (reset! selected-option type)))]

    (mf/use-effect
     (fn []
       (when-not has-libraries?
         ;; Start download automatically
         (start-export))))

    [:div.modal-overlay
     [:div.modal-container.export-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.export.title")]]

       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      (cond
        (= (:status @state) :prepare)
        [:*
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
             :value (tr "labels.continue")
             :on-click accept-fn}]]]]

        (= (:status @state) :exporting)
        [:*
         [:div.modal-content
          (for [file (:files @state)]
            [:& export-entry {:file file}])]

         [:div.modal-footer
          [:div.action-buttons
           [:input.accept-button
            {:class "primary"
             :type "button"
             :value (tr "labels.close")
             :disabled (->> @state :files (some :loading?))
             :on-click cancel-fn}]]]])]]))
