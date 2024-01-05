;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.export
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:const options [:all :merge :detach])

(mf/defc export-entry
  {::mf/wrap-props false}
  [{:keys [file]}]
  [:div {:class (stl/css-case :file-entry true
                              :loading  (:loading? file)
                              :success  (:export-success? file)
                              :error    (:export-error? file))}

   [:div {:class (stl/css :file-name)}
    [:span {:class (stl/css :file-icon)}
     (cond (:export-success? file) i/tick-refactor
           (:export-error? file)   i/close-refactor
           (:loading? file)        i/loader-pencil)]

    [:div {:class (stl/css :file-name-label)}
     (:name file)]]])

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
  [{:keys [team-id files has-libraries? binary? features]}]
  (let [state*          (mf/use-state
                         #(let [files (mapv (fn [file] (assoc file :loading? true)) files)]
                            {:status :prepare
                             :selected :all
                             :files files}))

        state           (deref state*)
        selected        (:selected state)
        status          (:status state)

        start-export
        (mf/use-fn
         (mf/deps team-id selected files features)
         (fn []
           (swap! state* assoc :status :exporting)
           (->> (uw/ask-many!
                 {:cmd (if binary? :export-binary-file :export-standard-file)
                  :team-id team-id
                  :features features
                  :export-type selected
                  :files files})
                (rx/mapcat #(->> (rx/of %)
                                 (rx/delay 1000)))
                (rx/subs!
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

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)}
        (tr "dashboard.export.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} i/close-refactor]]

      (cond
        (= status :prepare)
        [:*
         [:div {:class (stl/css :modal-content)}
          [:p {:class (stl/css :modal-msg)} (tr "dashboard.export.explain")]
          [:p {:class (stl/css :modal-scd-msg)} (tr "dashboard.export.detail")]

          (for [type export-types]
            [:div {:class (stl/css :export-option true)
                   :key (name type)}
             [:label {:for (str "export-" type)
                      :class (stl/css-case :global/checked (= selected type))}
                                ;; Execution time translation strings:
                                ;;   dashboard.export.options.all.message
                                ;;   dashboard.export.options.all.title
                                ;;   dashboard.export.options.detach.message
                                ;;   dashboard.export.options.detach.title
                                ;;   dashboard.export.options.merge.message
                                ;;   dashboard.export.options.merge.title
              [:span {:class (stl/css-case :global/checked (= selected type))}
               (when (= selected type)
                 i/status-tick-refactor)]
              [:div {:class (stl/css :option-content)}
               [:h3 {:class (stl/css :modal-subtitle)} (tr (dm/str "dashboard.export.options." (d/name type) ".title"))]
               [:p  {:class (stl/css :modal-msg)} (tr (dm/str "dashboard.export.options." (d/name type) ".message"))]]

              [:input {:type "radio"
                       :class (stl/css :option-input)
                       :id (str "export-" type)
                       :checked (= selected type)
                       :name "export-option"
                       :data-type (name type)
                       :on-change on-change}]]])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :cancel-button)
                    :type "button"
                    :value (tr "labels.cancel")
                    :on-click on-cancel}]

           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "labels.continue")
                    :on-click on-accept}]]]]

        (= status :exporting)
        [:*
         [:div {:class (stl/css :modal-content)}
          (for [file (:files state)]
            [:& export-entry {:file file :key (dm/str (:id file))}])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "labels.close")
                    :disabled (->> state :files (some :loading?))
                    :on-click on-cancel}]]]])]]))
