;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.exports.files
  "The files export dialog/modal"
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.worker :as mw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- mark-file-error
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-error? true
                  :loading false))
        files))

(defn- mark-file-success
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-success? true
                  :loading false))
        files))

(defn- initialize-state
  "Initialize export dialog state"
  [files]
  (let [files (mapv (fn [file] (assoc file :loading true)) files)]
    {:status :prepare
     :selected :all
     :files files}))

(mf/defc export-entry*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [file]}]
  [:div {:class (stl/css-case
                 :file-entry true
                 :loading  (:loading file)
                 :success  (:export-success? file)
                 :error    (:export-error? file))}

   [:div {:class (stl/css :file-name)}
    (if (:loading file)
      [:> loader*  {:width 16
                    :title (tr "labels.loading")}]
      [:span {:class (stl/css :file-icon)}
       (cond (:export-success? file) deprecated-icon/tick
             (:export-error? file)   deprecated-icon/close)])

    [:div {:class (stl/css :file-name-label)}
     (:name file)]]])

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as ::fexp/export-files
   ::mf/props :obj}
  [{:keys [team-id files format]}]
  (let [state*       (mf/use-state (partial initialize-state files))
        has-libs?    (some :has-libraries files)

        state        (deref state*)
        selected     (:selected state)
        status       (:status state)

        binary?      (not= format :legacy-zip)

        ;; We've deprecated the merge option on non-binary files
        ;; because it wasn't working and we're planning to remove this
        ;; export in future releases.
        export-types (if binary? fexp/valid-types [:all :detach])

        start-export
        (mf/use-fn
         (mf/deps team-id selected files)
         (fn []
           (swap! state* assoc :status :exporting)
           (->> (mw/ask-many!
                 {:cmd :export-files
                  :format format
                  :team-id team-id
                  :type selected
                  :files files})
                (rx/mapcat #(->> (rx/of %)
                                 (rx/delay 1000)))
                (rx/subs!
                 (fn [msg]
                   (cond
                     (= :error (:type msg))
                     (swap! state* update :files mark-file-error (:file-id msg))

                     (= :finish (:type msg))
                     (let [mtype (if (contains? cf/flags :export-file-v3)
                                   "application/penpot"
                                   (:mtype msg))
                           fname (:filename msg)
                           uri   (:uri msg)]
                       (swap! state* update :files mark-file-success (:file-id msg))
                       (dom/trigger-download-uri fname mtype uri))))))))

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

    (mf/with-effect [has-libs?]
      ;; Start download automatically when no libraries
      (when-not has-libs?
        (start-export)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)}
        (tr "dashboard.export.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} deprecated-icon/close]]

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
              ;;   (tr "dashboard.export.options.all.message")
              ;;   (tr "dashboard.export.options.all.title")
              ;;   (tr "dashboard.export.options.detach.message")
              ;;   (tr "dashboard.export.options.detach.title")
              ;;   (tr "dashboard.export.options.merge.message")
              ;;   (tr "dashboard.export.options.merge.title")
              [:span {:class (stl/css-case :global/checked (= selected type))}
               (when (= selected type)
                 deprecated-icon/status-tick)]
              [:div {:class (stl/css :option-content)}
               [:h3 {:class (stl/css :modal-subtitle)}
                (tr (dm/str "dashboard.export.options." (d/name type) ".title"))]
               [:p  {:class (stl/css :modal-msg)}
                (tr (dm/str "dashboard.export.options." (d/name type) ".message"))]]

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
            [:> export-entry* {:file file :key (dm/str (:id file))}])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "labels.close")
                    :disabled (->> state :files (some :loading))
                    :on-click on-cancel}]]]])]]))
