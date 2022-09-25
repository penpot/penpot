;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.import
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

(def ^:const emit-delay 1000)

(defn use-import-file
  [project-id on-finish-import]
  (mf/use-callback
   (mf/deps project-id on-finish-import)
   (fn [files]
     (when files
       (let [files (->> files
                        (mapv
                         (fn [file]
                           {:name (.-name file)
                            :uri  (wapi/create-uri file)})))]
         (st/emit! (modal/show
                    {:type :import
                     :project-id project-id
                     :files files
                     :on-finish-import on-finish-import})))))))

(mf/defc import-form
  {::mf/forward-ref true}
  [{:keys [project-id on-finish-import]} external-ref]

  (let [on-file-selected (use-import-file project-id on-finish-import)]
    [:form.import-file
     [:& file-uploader {:accept ".penpot,.zip"
                        :multi true
                        :ref external-ref
                        :on-selected on-file-selected}]]))

(defn update-file [files file-id new-name]
  (->> files
       (mapv
        (fn [file]
          (cond-> file
            (= (:file-id file) file-id)
            (assoc :name new-name))))))

(defn remove-file [files file-id]
  (->> files
       (mapv
        (fn [file]
          (cond-> file
            (= (:file-id file) file-id)
            (assoc :deleted? true))))))

(defn set-analyze-error
  [files uri]
  (->> files
       (mapv (fn [file]
               (cond-> file
                 (= uri (:uri file))
                 (assoc :status :analyze-error))))))

(defn set-analyze-result [files uri type data]
  (let [existing-files? (into #{} (->> files (map :file-id) (filter some?)))
        replace-file
        (fn [file]
          (if (and (= uri (:uri file))
                   (= (:status file) :analyzing))
            (->> (:files data)
                 (remove (comp existing-files? first))
                 (mapv (fn [[file-id file-data]]
                         (-> file-data
                             (assoc :file-id file-id
                                    :status :ready
                                    :uri uri
                                    :type type)))))
            [file]))]
    (into [] (mapcat replace-file) files)))

(defn mark-files-importing [files]
  (->> files
       (filter #(= :ready (:status %)))
       (mapv #(assoc % :status :importing))))

(defn update-status [files file-id status progress errors]
  (->> files
       (mapv (fn [file]
               (cond-> file
                 (and (= file-id (:file-id file)) (not= status :import-progress))
                 (assoc :status status)

                 (and (= file-id (:file-id file)) (= status :import-progress))
                 (assoc :progress progress)

                 (= file-id (:file-id file))
                 (assoc :errors errors))))))

(defn parse-progress-message
  [message]
  (case (:type message)
    :upload-data
    (tr "dashboard.import.progress.upload-data" (:current message) (:total message))

    :upload-media
    (tr "dashboard.import.progress.upload-media" (:file message))

    :process-page
    (tr "dashboard.import.progress.process-page" (:file message))

    :process-colors
    (tr "dashboard.import.progress.process-colors")

    :process-typographies
    (tr "dashboard.import.progress.process-typographies")

    :process-media
    (tr "dashboard.import.progress.process-media")

    :process-components
    (tr "dashboard.import.progress.process-components")

    (str message)))

(mf/defc import-entry
  [{:keys [state file editing? can-be-deleted?]}]

  (let [loading?       (or (= :analyzing (:status file))
                           (= :importing (:status file)))
        analyze-error? (= :analyze-error (:status file))
        import-finish? (= :import-finish (:status file))
        import-error?  (= :import-error (:status file))
        import-warn?   (d/not-empty? (:errors file))
        ready?         (= :ready (:status file))
        is-shared?     (:shared file)
        progress       (:progress file)

        handle-edit-key-press
        (mf/use-callback
         (fn [e]
           (when (or (kbd/enter? e) (kbd/esc? e))
             (dom/prevent-default e)
             (dom/stop-propagation e)
             (dom/blur! (dom/get-target e)))))

        handle-edit-blur
        (mf/use-callback
         (mf/deps file)
         (fn [e]
           (let [value (dom/get-target-val e)]
             (swap! state #(-> (assoc % :editing nil)
                               (update :files update-file (:file-id file) value))))))

        handle-edit-entry
        (mf/use-callback
         (mf/deps file)
         (fn []
           (swap! state assoc :editing (:file-id file))))

        handle-remove-entry
        (mf/use-callback
         (mf/deps file)
         (fn []
           (swap! state update :files remove-file (:file-id file))))]

    [:div.file-entry
     {:class (dom/classnames
              :loading  loading?
              :success  (and import-finish? (not import-warn?) (not import-error?))
              :warning  (and import-finish? import-warn? (not import-error?))
              :error    (or import-error? analyze-error?)
              :editable (and ready? (not editing?)))}

     [:div.file-name
      [:div.file-icon
       (cond loading?       i/loader-pencil
             ready?         i/logo-icon
             import-warn?   i/msg-warning
             import-error?  i/close
             import-finish? i/tick
             analyze-error? i/close)]

      (if editing?
        [:div.file-name-edit
         [:input {:type "text"
                  :auto-focus true
                  :default-value (:name file)
                  :on-key-press handle-edit-key-press
                  :on-blur handle-edit-blur}]]

        [:div.file-name-label (:name file) (when is-shared? i/library)])

        [:div.edit-entry-buttons
         (when (= "application/zip" (:type file))
           [:button {:on-click handle-edit-entry}   i/pencil])
         (when can-be-deleted?
           [:button {:on-click handle-remove-entry} i/trash])]]

     (cond
       analyze-error?
       [:div.error-message
        (tr "dashboard.import.analyze-error")]

       import-error?
       [:div.error-message
        (tr "dashboard.import.import-error")]

       (and (not import-finish?) (some? progress))
       [:div.progress-message (parse-progress-message progress)])

     [:div.linked-libraries
      (for [library-id (:libraries file)]
        (let [library-data (->> @state :files (d/seek #(= library-id (:file-id %))))
              error? (or (:deleted? library-data) (:import-error library-data))]
          (when (some? library-data)
            [:div.linked-library-tag {:class (when error? "error")}
             (if error? i/unchain i/chain) (:name library-data)])))]]))

(mf/defc import-dialog
  {::mf/register modal/components
   ::mf/register-as :import}
  [{:keys [project-id files template on-finish-import]}]
  (let [state (mf/use-state
               {:status :analyzing
                :editing nil
                :importing-templates 0
                :files (->> files
                            (mapv #(assoc % :status :analyzing)))})

        components-v2 (features/use-feature :components-v2)

        analyze-import
        (mf/use-callback
         (fn [files]
           (->> (uw/ask-many!
                 {:cmd :analyze-import
                  :files files})
                (rx/delay-emit emit-delay)
                (rx/subs
                 (fn [{:keys [uri data error type] :as msg}]
                   (log/debug :uri uri :data data :error error)
                   (if (some? error)
                     (swap! state update :files set-analyze-error uri)
                     (swap! state update :files set-analyze-result uri type data)))))))

        import-files
        (mf/use-callback
         (fn [project-id files]
           (st/emit! (ptk/event ::ev/event {::ev/name "import-files"
                                            :num-files (count files)}))
           (->> (uw/ask-many!
                 {:cmd :import-files
                  :components-v2 components-v2
                  :project-id project-id
                  :files files})
                (rx/subs
                 (fn [{:keys [file-id status message errors] :as msg}]
                   (swap! state update :files update-status file-id status message errors))))))

        handle-cancel
        (mf/use-callback
         (mf/deps (:editing @state))
         (fn [event]
           (when (nil? (:editing @state))
             (dom/prevent-default event)
             (st/emit! (modal/hide)))))

        on-template-cloned-success
        (fn []
          (swap! state
                 (fn [state]
                   (-> state
                       (assoc :status :importing :importing-templates 0))))
          (st/emit! (dd/fetch-recent-files)))

        on-template-cloned-error
        (fn []
          (st/emit!
           (modal/hide)
           (msg/error (tr "dashboard.libraries-and-templates.import-error"))))

        continue-files
        (fn []
          (let [files (->> @state :files (filterv #(and (= :ready (:status %)) (not (:deleted? %)))))]
            (import-files project-id files))

          (swap! state
                 (fn [state]
                   (-> state
                       (assoc :status :importing)
                       (update :files mark-files-importing)))))

        continue-template
        (fn []
          (let [mdata  {:on-success on-template-cloned-success :on-error on-template-cloned-error}
                params {:project-id project-id :template-id (:id template)}]
            (swap! state
                   (fn [state]
                     (-> state
                         (assoc :status :importing :importing-templates 1))))
            (st/emit! (dd/clone-template (with-meta params mdata)))))


        handle-continue
        (mf/use-callback
         (mf/deps project-id (:files @state))
         (fn [event]
           (dom/prevent-default event)
           (if (some? template)
             (continue-template)
             (continue-files))))

        handle-accept
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (when on-finish-import (on-finish-import))))

        num-importing (+
                       (->> @state :files (filter #(= (:status %) :importing)) count)
                       (:importing-templates @state))


        warning-files (->> @state :files (filter #(and (= (:status %) :import-finish) (d/not-empty? (:errors %)))) count)
        success-files (->> @state :files (filter #(and (= (:status %) :import-finish) (empty? (:errors %)))) count)
        pending-analysis? (> (->> @state :files (filter #(= (:status %) :analyzing)) count) 0)
        pending-import? (> num-importing 0)
        files (->> (:files @state) (filterv (comp not :deleted?)))
        ;; pending-import? (> (->> @state :files (filter #(= (:status %) :importing)) count) 0)
        ;; files (->> (:files @state) (filterv (comp not :deleted?)))
        valid-files? (or (some? template)
                         (> (+ (->> files (filterv (fn [x] (not= (:status x) :analyze-error))) count)) 0))]

    (mf/use-effect
     (fn []
       (let [sub (analyze-import files)]
         #(rx/dispose! sub))))

    (mf/use-effect
     (fn []
       ;; dispose uris when the component is umount
       #(doseq [file files]
          (wapi/revoke-uri (:uri file)))))

    [:div.modal-overlay
     [:div.modal-container.import-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.import")]]

       [:div.modal-close-button
        {:on-click handle-cancel} i/close]]

      [:div.modal-content
       (when (and (= :importing (:status @state)) (not pending-import?))
         (if (> warning-files 0)
           [:div.feedback-banner.warning
            [:div.icon i/msg-warning]
            [:div.message (tr "dashboard.import.import-warning" warning-files success-files)]]

           [:div.feedback-banner
            [:div.icon i/checkbox-checked]
            [:div.message (tr "dashboard.import.import-message" (if (some? template) 1 success-files))]]))

       (for [file files]
         (let [editing? (and (some? (:file-id file))
                             (= (:file-id file) (:editing @state)))]
           [:& import-entry {:state state
                             :key (dm/str (:id file))
                             :file file
                             :editing? editing?
                             :can-be-deleted? (> (count files) 1)}]))

       (when (some? template)
         [:& import-entry {:state state
                           :file (assoc template :status (if (= 1 (:importing-templates @state)) :importing :ready))
                           :editing? false
                           :can-be-deleted? false}])]

      [:div.modal-footer
       [:div.action-buttons
        (when (or (= :analyzing (:status @state)) pending-import?)
          [:input.cancel-button
           {:type "button"
            :value (tr "labels.cancel")
            :on-click handle-cancel}])

        (when (= :analyzing (:status @state))
          [:input.accept-button
           {:class "primary"
            :type "button"
            :value (tr "labels.continue")
            :disabled (or pending-analysis? (not valid-files?))
            :on-click handle-continue}])

        (when (= :importing (:status @state))
          [:input.accept-button
           {:class "primary"
            :type "button"
            :value (tr "labels.accept")
            :disabled (or pending-import? (not valid-files?))
            :on-click handle-accept}])]]]]))
