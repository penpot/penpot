;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.import
  (:require
   [app.common.data :as d]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.icons :as i]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

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
                            :uri  (dom/create-uri file)})))]
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
     [:& file-uploader {:accept ".penpot"
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

(defn set-analyze-result [files uri data]
  (let [existing-files? (into #{} (->> files (map :file-id) (filter some?)))
        replace-file
        (fn [file]
          (if (and (= uri (:uri file) )
                   (= (:status file) :analyzing))
            (->> (:files data)
                 (remove (comp existing-files? first) )
                 (mapv (fn [[file-id file-data]]
                         (-> file-data
                             (assoc :file-id file-id
                                    :status :ready
                                    :uri uri)))))
            [file]))]
    (into [] (mapcat replace-file) files)))

(defn mark-files-importing [files]
  (->> files
       (filter #(= :ready (:status %)))
       (mapv #(assoc % :status :importing))))

(defn update-status [files file-id status]
  (->> files
       (mapv (fn [file]
               (cond-> file
                 (= file-id (:file-id file))
                 (assoc :status status))))))

(mf/defc import-entry
  [{:keys [state file editing?]}]

  (let [loading?      (or (= :analyzing (:status file))
                          (= :importing (:status file)))
        load-success? (= :import-success (:status file))
        analyze-error?   (= :analyze-error (:status file))
        import-error?   (= :import-error (:status file))
        ready?        (= :ready (:status file))
        is-shared?    (:shared file)

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
              :success  load-success?
              :error    (or import-error? analyze-error?)
              :editable (and ready? (not editing?)))}

     [:div.file-name
      [:div.file-icon
       (cond loading?       i/loader-pencil
             ready?         i/logo-icon
             load-success?  i/tick
             import-error?  i/close
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
       [:button {:on-click handle-edit-entry}   i/pencil]
       [:button {:on-click handle-remove-entry} i/trash]]]

     (when analyze-error?
       [:div.error-message
        (tr "dashboard.import.analyze-error")])

     (when import-error?
       [:div.error-message
        (tr "dashboard.import.import-error")])

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
  [{:keys [project-id files on-finish-import]}]
  (let [state (mf/use-state
               {:status :analyzing
                :editing nil
                :files (->> files
                            (mapv #(assoc % :status :analyzing)))})

        analyze-import
        (mf/use-callback
         (fn [files]
           (->> (uw/ask-many!
                 {:cmd :analyze-import
                  :files (->> files (mapv :uri))})
                (rx/delay-emit emit-delay)
                (rx/subs
                 (fn [{:keys [uri data error] :as msg}]
                   (log/debug :msg msg)
                   (if (some? error)
                     (swap! state update :files set-analyze-error uri)
                     (swap! state update :files set-analyze-result uri data)))))))

        import-files
        (mf/use-callback
         (fn [project-id files]
           (st/emit! (ptk/event ::ev/event {::ev/name "import-files"
                                            :num-files (count files)}))

           (->> (uw/ask-many!
                 {:cmd :import-files
                  :project-id project-id
                  :files files})
                (rx/delay-emit emit-delay)
                (rx/subs
                 (fn [{:keys [file-id status] :as msg}]
                   (log/debug :msg msg)
                   (swap! state update :files update-status file-id status))))))

        handle-cancel
        (mf/use-callback
         (mf/deps (:editing @state))
         (fn [event]
           (when (nil? (:editing @state))
             (dom/prevent-default event)
             (st/emit! (modal/hide)))))

        handle-continue
        (mf/use-callback
         (mf/deps project-id (:files @state))
         (fn [event]
           (dom/prevent-default event)
           (let [files (->> @state :files (filterv #(= :ready (:status %))))]
             (import-files project-id files))

           (swap! state
                  (fn [state]
                    (-> state
                        (assoc :status :importing)
                        (update :files mark-files-importing))))))

        handle-accept
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (when on-finish-import (on-finish-import))))

        success-files (->> @state :files (filter #(= (:status %) :import-success)) count)
        pending-analysis? (> (->> @state :files (filter #(= (:status %) :analyzing)) count) 0)
        pending-import? (> (->> @state :files (filter #(= (:status %) :importing)) count) 0)]

    (mf/use-effect
     (fn []
       (let [sub (analyze-import files)]
         #(rx/dispose! sub))))

    (mf/use-effect
     (fn []
       ;; dispose uris when the component is umount
       #(doseq [file files]
          (dom/revoke-uri (:uri file)))))

    [:div.modal-overlay
     [:div.modal-container.import-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.import")]]

       [:div.modal-close-button
        {:on-click handle-cancel} i/close]]

      [:div.modal-content
       (when (and (= :importing (:status @state))
                  (not pending-import?))
         [:div.feedback-banner
          [:div.icon i/checkbox-checked]
          [:div.message (tr "dashboard.import.import-message" success-files)]])

       (for [file (->> (:files @state) (filterv (comp not :deleted?)))]
         (let [editing?      (and (some? (:file-id file))
                                  (= (:file-id file) (:editing @state)))]
           [:& import-entry {:state state
                             :file file
                             :editing? editing?}]))]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click handle-cancel}]

        (when (= :analyzing (:status @state))
          [:input.accept-button
           {:class "primary"
            :type "button"
            :value (tr "labels.continue")
            :disabled pending-analysis?
            :on-click handle-continue}])

        (when (= :importing (:status @state))
          [:input.accept-button
           {:class "primary"
            :type "button"
            :value (tr "labels.accept")
            :disabled pending-import?
            :on-click handle-accept}])]]]]))
