;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.import
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

(def ^:const emit-delay 1000)

(defn use-import-file
  [project-id on-finish-import]
  (mf/use-fn
   (mf/deps project-id on-finish-import)
   (fn [entries]
     (let [entries (->> entries
                        (mapv (fn [file]
                                {:name (.-name file)
                                 :uri  (wapi/create-uri file)}))
                        (not-empty))]
       (when entries
         (st/emit! (modal/show
                    {:type :import
                     :project-id project-id
                     :entries entries
                     :on-finish-import on-finish-import})))))))

(mf/defc import-form
  {::mf/forward-ref true
   ::mf/props :obj}

  [{:keys [project-id on-finish-import]} external-ref]
  (let [on-file-selected (use-import-file project-id on-finish-import)]
    [:form.import-file {:aria-hidden "true"}
     [:& file-uploader {:accept ".penpot,.zip"
                        :multi true
                        :ref external-ref
                        :on-selected on-file-selected}]]))

(defn- update-entry-name
  [entries file-id new-name]
  (mapv (fn [entry]
          (let [new-name (str/trim new-name)]
            (cond-> entry
              (and (= (:file-id entry) file-id)
                   (not= "" new-name))
              (assoc :name new-name))))
        entries))

(defn- remove-entry
  [entries file-id]
  (mapv (fn [entry]
          (cond-> entry
            (= (:file-id entry) file-id)
            (assoc :deleted true)))
        entries))

(defn- update-with-analyze-error
  [entries uri error]
  (->> entries
       (mapv (fn [entry]
               (cond-> entry
                 (= uri (:uri entry))
                 (-> (assoc :status :analyze-error)
                     (assoc :error error)))))))

(defn- update-with-analyze-result
  [entries uri type result]
  (let [existing-entries? (into #{} (keep :file-id) entries)
        replace-entry
        (fn [entry]
          (if (and (= uri (:uri entry))
                   (= (:status entry) :analyzing))
            (->> (:files result)
                 (remove (comp existing-entries? first))
                 (map (fn [[file-id file-data]]
                        (-> file-data
                            (assoc :file-id file-id)
                            (assoc :status :ready)
                            (assoc :uri uri)
                            (assoc :type type)))))
            [entry]))]
    (into [] (mapcat replace-entry) entries)))

(defn- mark-entries-importing
  [entries]
  (->> entries
       (filter #(= :ready (:status %)))
       (mapv #(assoc % :status :importing))))

(defn- update-entry-status
  [entries file-id status progress errors]
  (mapv (fn [entry]
          (cond-> entry
            (and (= file-id (:file-id entry)) (not= status :import-progress))
            (assoc :status status)

            (and (= file-id (:file-id entry)) (= status :import-progress))
            (assoc :progress progress)

            (= file-id (:file-id entry))
            (assoc :errors errors)))
        entries))

(defn- parse-progress-message
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

(defn- has-status-importing?
  [item]
  (= (:status item) :importing))

(defn- has-status-analyzing?
  [item]
  (= (:status item) :analyzing))

(defn- has-status-analyze-error?
  [item]
  (= (:status item) :analyzing))

(defn- has-status-success?
  [item]
  (and (= (:status item) :import-finish)
       (empty? (:errors item))))

(defn- has-status-error?
  [item]
  (and (= (:status item) :import-finish)
       (d/not-empty? (:errors item))))

(defn- has-status-ready?
  [item]
  (and (= :ready (:status item))
       (not (:deleted item))))

(defn- analyze-entries
  [state entries]
  (->> (uw/ask-many!
        {:cmd :analyze-import
         :files entries
         :features @features/features-ref})
       (rx/mapcat #(rx/delay emit-delay (rx/of %)))
       (rx/filter some?)
       (rx/subs!
        (fn [{:keys [uri data error type] :as msg}]
          (if (some? error)
            (swap! state update-with-analyze-error uri error)
            (swap! state update-with-analyze-result uri type data))))))

(defn- import-files!
  [state project-id entries]
  (st/emit! (ptk/data-event ::ev/event {::ev/name "import-files"
                                        :num-files (count entries)}))
  (->> (uw/ask-many!
        {:cmd :import-files
         :project-id project-id
         :files entries
         :features @features/features-ref})
       (rx/subs!
        (fn [{:keys [file-id status message errors] :as msg}]
          (swap! state update-entry-status file-id status message errors)))))

(mf/defc import-entry
  {::mf/props :obj
   ::mf/memo true
   ::mf/private true}
  [{:keys [entries entry edition can-be-deleted on-edit on-change on-delete]}]
  (let [status         (:status entry)
        loading?       (or (= :analyzing status)
                           (= :importing status))
        analyze-error? (= :analyze-error status)
        import-finish? (= :import-finish status)
        import-error?  (= :import-error status)
        import-warn?   (d/not-empty? (:errors entry))
        ready?         (= :ready status)
        is-shared?     (:shared entry)
        progress       (:progress entry)

        file-id        (:file-id entry)
        editing?       (and (some? file-id) (= edition file-id))

        on-edit-key-press
        (mf/use-fn
         (fn [event]
           (when (or (kbd/enter? event)
                     (kbd/esc? event))
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (dom/blur! (dom/get-target event)))))

        on-edit-blur
        (mf/use-fn
         (mf/deps file-id on-change)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (on-change file-id value event))))

        on-edit'
        (mf/use-fn
         (mf/deps file-id on-change)
         (fn [event]
           (when (fn? on-edit)
             (on-edit file-id event))))

        on-delete'
        (mf/use-fn
         (mf/deps file-id on-delete)
         (fn [event]
           (when (fn? on-delete)
             (on-delete file-id event))))]

    [:div {:class (stl/css-case
                   :file-entry true
                   :loading  loading?
                   :success  (and import-finish? (not import-warn?) (not import-error?))
                   :warning  (and import-finish? import-warn? (not import-error?))
                   :error    (or import-error? analyze-error?)
                   :editable (and ready? (not editing?)))}

     [:div {:class (stl/css :file-name)}
      (if loading?
        [:> loader*  {:width 16
                      :title (tr "labels.loading")}]
        [:div {:class (stl/css-case :file-icon true
                                    :icon-fill ready?)}
         (cond ready?         i/logo-icon
               import-warn?   i/msg-warning
               import-error?  i/close
               import-finish? i/tick
               analyze-error? i/close)])


      (if editing?
        [:div {:class (stl/css :file-name-edit)}
         [:input {:type "text"
                  :auto-focus true
                  :default-value (:name entry)
                  :on-key-press on-edit-key-press
                  :on-blur on-edit-blur}]]

        [:div {:class (stl/css :file-name-label)}
         (:name entry)
         (when ^boolean is-shared?
           [:span {:class (stl/css :icon)}
            i/library])])

      [:div {:class (stl/css :edit-entry-buttons)}
       (when (and (= "application/zip" (:type entry))
                  (= status :ready))
         [:button {:on-click on-edit'} i/curve])
       (when can-be-deleted
         [:button {:on-click on-delete'} i/delete])]]

     (cond
       analyze-error?
       [:div {:class (stl/css :error-message)}
        (if (some? (:error entry))
          (tr (:error entry))
          (tr "dashboard.import.analyze-error"))]

       import-error?
       [:div {:class (stl/css :error-message)}
        (tr "dashboard.import.import-error")]

       (and (not import-finish?) (some? progress))
       [:div {:class (stl/css :progress-message)} (parse-progress-message progress)])

     [:div {:class (stl/css :linked-libraries)}
      (for [library-id (:libraries entry)]
        (let [library-data (d/seek #(= library-id (:file-id %)) entries)
              error?       (or (:deleted library-data)
                               (:import-error library-data))]
          (when (some? library-data)
            [:div {:class (stl/css :linked-library)
                   :key (dm/str library-id)}
             (:name library-data)
             [:span {:class (stl/css-case
                             :linked-library-tag true
                             :error error?)}
              i/detach]])))]]))

(mf/defc import-dialog
  {::mf/register modal/components
   ::mf/register-as :import
   ::mf/props :obj}

  [{:keys [project-id entries template on-finish-import]}]

  (mf/with-effect []
    ;; dispose uris when the component is umount
    (fn [] (run! wapi/revoke-uri (map :uri entries))))

  (let [entries* (mf/use-state
                  (fn [] (mapv #(assoc % :status :analyzing) entries)))
        entries  (deref entries*)

        status*  (mf/use-state :analyzing)
        status   (deref status*)

        edition* (mf/use-state nil)
        edition  (deref edition*)

        template-finished* (mf/use-state nil)
        template-finished (deref template-finished*)

        on-template-cloned-success
        (mf/use-fn
         (fn []
           (reset! status* :importing)
           (reset! template-finished* true)
           (st/emit! (dd/fetch-recent-files))))

        on-template-cloned-error
        (mf/use-fn
         (fn [cause]
           (reset! status* :error)
           (reset! template-finished* true)
           (errors/print-error! cause)
           (rx/of (modal/hide)
                  (ntf/error (tr "dashboard.libraries-and-templates.import-error")))))

        continue-entries
        (mf/use-fn
         (mf/deps entries)
         (fn []
           (let [entries (filterv has-status-ready? entries)]
             (swap! status* (constantly :importing))
             (swap! entries* mark-entries-importing)
             (import-files! entries* project-id entries))))

        continue-template
        (mf/use-fn
         (mf/deps on-template-cloned-success
                  on-template-cloned-error
                  template)
         (fn []
           (let [mdata  {:on-success on-template-cloned-success
                         :on-error on-template-cloned-error}
                 params {:project-id project-id :template-id (:id template)}]
             (swap! status* (constantly :importing))
             (st/emit! (dd/clone-template (with-meta params mdata))))))

        on-edit
        (mf/use-fn
         (fn [file-id _event]
           (swap! edition* (constantly file-id))))

        on-entry-change
        (mf/use-fn
         (fn [file-id value]
           (swap! edition* (constantly nil))
           (swap! entries* update-entry-name file-id value)))

        on-entry-delete
        (mf/use-fn
         (fn [file-id]
           (swap! entries* remove-entry file-id)))

        on-cancel
        (mf/use-fn
         (mf/deps edition)
         (fn [event]
           (when (nil? edition)
             (dom/prevent-default event)
             (st/emit! (modal/hide)))))

        on-continue
        (mf/use-fn
         (mf/deps template
                  continue-template
                  continue-entries)
         (fn [event]
           (dom/prevent-default event)
           (if (some? template)
             (continue-template)
             (continue-entries))))

        on-accept
        (mf/use-fn
         (mf/deps on-finish-import)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (when (fn? on-finish-import)
             (on-finish-import))))

        entries            (filterv (comp not :deleted) entries)
        num-importing      (+ (count (filterv has-status-importing? entries))
                              (if (some? template) 1 0))

        success-num        (if (some? template)
                             1
                             (count (filterv has-status-success? entries)))

        errors?            (if (some? template)
                             (= status :error)
                             (or (some has-status-error? entries)
                                 (zero? (count entries))))

        pending-analysis?  (some has-status-analyzing? entries)
        pending-import?    (and (or (nil? template)
                                    (not template-finished))
                                (pos? num-importing))

        valid-all-entries? (or (some? template)
                               (not (some has-status-analyze-error? entries)))

        template-status
        (cond
          (and (= :importing status) pending-import?)
          :importing

          (and (= :importing status) (not ^boolean pending-import?))
          :import-finish

          :else
          :ready)]

    ;; Run analyze operation on component mount
    (mf/with-effect []
      (let [sub (analyze-entries entries* entries)]
        (partial rx/dispose! sub)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2  {:class (stl/css :modal-title)} (tr "dashboard.import")]

       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} i/close]]

      [:div {:class (stl/css :modal-content)}
       (when (and (= :analyzing status) errors?)
         [:& context-notification
          {:level :warning
           :content (tr "dashboard.import.import-warning")}])

       (when (and (= :importing status) (not ^boolean pending-import?))
         (cond
           errors?
           [:& context-notification
            {:level :warning
             :content (tr "dashboard.import.import-warning")}]

           :else
           [:& context-notification
            {:level (if (zero? success-num) :warning :success)
             :content (tr "dashboard.import.import-message" (i18n/c success-num))}]))

       (for [entry entries]
         [:& import-entry {:edition edition
                           :key (dm/str (:uri entry))
                           :entry entry
                           :entries entries
                           :on-edit on-edit
                           :on-change on-entry-change
                           :on-delete on-entry-delete
                           :can-be-deleted (> (count entries) 1)}])

       (when (some? template)
         [:& import-entry {:entry (assoc template :status template-status)
                           :can-be-deleted false}])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        (when (= :analyzing status)
          [:input {:class (stl/css :cancel-button)
                   :type "button"
                   :value (tr "labels.cancel")
                   :on-click on-cancel}])

        (when (and (= :analyzing status) (not errors?))
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.continue")
                   :disabled (or pending-analysis? (not valid-all-entries?))
                   :on-click on-continue}])

        (when (and (= :importing status) (not errors?))
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.accept")
                   :disabled (or pending-import? (not valid-all-entries?))
                   :on-click on-accept}])]]]]))
