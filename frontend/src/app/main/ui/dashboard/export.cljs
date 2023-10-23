;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.export
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(def ^:const options [:all :merge :detach])

(mf/defc export-entry
  {::mf/wrap-props false}
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

(defn- mark-file-error
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-error? true
                  :loading? false))
        files))

(defn- mark-file-success
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-success? true
                  :loading? false))
        files))

(def export-types
  [:all :merge :detach])

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as :export
   ::mf/wrap-props false}
  [{:keys [team-id files has-libraries? binary?]}]
  (let [components-v2 (features/use-feature "components/v2")
        state*        (mf/use-state
                       #(let [files (mapv (fn [file] (assoc file :loading? true)) files)]
                          {:status :prepare
                           :selected :all
                           :files files}))

        state         (deref state*)
        selected      (:selected state)
        status        (:status state)

        start-export
        (mf/use-fn
         (mf/deps team-id selected files components-v2)
         (fn []
           (swap! state* assoc :status :exporting)
           (->> (uw/ask-many!
                 {:cmd (if binary? :export-binary-file :export-standard-file)
                  :team-id team-id
                  :export-type selected
                  :files files
                  :components-v2 components-v2})
                (rx/delay-emit 1000)
                (rx/subs
                 (fn [msg]
                   (cond
                     (= :error (:type msg))
                     (swap! state* update :files mark-file-error (:file-id msg))

                     (= :finish (:type msg))
                     (do
                       (swap! state* update :files mark-file-success (:file-id msg))
                       (dom/trigger-download-uri (:filename msg) (:mtype msg) (:uri msg)))))))))

        on-cancel
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))))

        on-accept
        (mf/use-fn
         (mf/deps start-export)
         (fn [event]
           (dom/prevent-default event)
           (start-export)))

        on-change
        (mf/use-fn
         (fn [event]
           (let [type (-> (dom/get-target event)
                          (dom/get-data "type")
                          (keyword))]
             (swap! state* assoc :selected type))))]

    (mf/with-effect [has-libraries?]
      ;; Start download automatically when no libraries
      (when-not has-libraries?
        (start-export)))

    [:div.modal-overlay
     [:div.modal-container.export-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.export.title")]]

       [:div.modal-close-button
        {:on-click on-cancel} i/close]]

      (cond
        (= status :prepare)
        [:*
         [:div.modal-content
          [:p.explain (tr "dashboard.export.explain")]
          [:p.detail (tr "dashboard.export.detail")]

          (for [type export-types]
            [:div.export-option {:class (when (= selected type) "selected")
                                 :key (name type)}
             [:label.option-container
              ;; Execution time translation strings:
              ;;   dashboard.export.options.all.message
              ;;   dashboard.export.options.all.title
              ;;   dashboard.export.options.detach.message
              ;;   dashboard.export.options.detach.title
              ;;   dashboard.export.options.merge.message
              ;;   dashboard.export.options.merge.title
              [:h3 (tr (dm/str "dashboard.export.options." (d/name type) ".title"))]
              [:p  (tr (dm/str "dashboard.export.options." (d/name type) ".message"))]
              [:input {:type "radio"
                       :checked (= selected type)
                       :data-type (name type)
                       :on-change on-change
                       :name "export-option"}]
              [:span {:class "option-radio-check"}]]])]

         [:div.modal-footer
          [:div.action-buttons
           [:input.cancel-button
            {:type "button"
             :value (tr "labels.cancel")
             :on-click on-cancel}]

           [:input.accept-button
            {:class "primary"
             :type "button"
             :value (tr "labels.continue")
             :on-click on-accept}]]]]

        (= status :exporting)
        [:*
         [:div.modal-content
          (for [file (:files state)]
            [:& export-entry {:file file :key (dm/str (:id file))}])]

         [:div.modal-footer
          [:div.action-buttons
           [:input.accept-button
            {:class "primary"
             :type "button"
             :value (tr "labels.close")
             :disabled (->> state :files (some :loading?))
             :on-click on-cancel}]]]])]]))
