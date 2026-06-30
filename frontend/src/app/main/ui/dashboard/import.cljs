;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.import
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as log]
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.html-import :as dhi]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.main.worker :as mw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

(def ^:const emit-delay 200)

(defn use-import-file
  [project-id on-finish-import]
  (mf/use-fn
   (mf/deps project-id on-finish-import)
   (fn [entries]
     (let [css-files (->> entries
                          (filter dhi/css-file?)
                          (mapv (fn [file]
                                  {:name (.-name file)
                                   :file file})))
           entries   (->> entries
                          (remove dhi/css-file?)
                          (mapv
                           (fn [file]
                             {:name (.-name file)
                              :uri  (wapi/create-uri file)
                              :file file
                              :type (when (dhi/html-file? file) :html-subset)}))
                          (not-empty))]
       (when entries
         (st/emit! (modal/show
                    {:type :import
                     :project-id project-id
                     :css-files css-files
                     :entries entries
                     :on-finish-import on-finish-import})))))))

(mf/defc import-form*
  {::mf/forward-ref true}
  [{:keys [project-id on-finish-import]} external-ref]
  (let [on-file-selected (use-import-file project-id on-finish-import)]
    [:form.import-file {:aria-hidden "true"}
     [:& file-uploader {:accept ".penpot,.zip,.html,.htm,.css"
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

(defn- update-with-analyze-result
  [entries {:keys [file-id status] :as updated}]
  (let [entries (filterv (comp uuid? :file-id) entries)
        status  (case status
                  :success :import-ready
                  :error :analyze-error)
        updated (assoc updated :status status)]
    (if (some #(= file-id (:file-id %)) entries)
      (mapv (fn [entry]
              (if (= (:file-id entry) file-id)
                (merge entry updated)
                entry))
            entries)
      (conj entries updated))))

(defn- update-entry-status
  [entries message]
  (mapv (fn [entry]
          (if (= (:file-id entry) (:file-id message))
            (let [status (case (:status message)
                           :progress :import-progress
                           :finish :import-success
                           :error :import-error)]
              (-> entry
                  (assoc :progress (:progress message))
                  (assoc :status status)
                  (assoc :error (:error message))
                  (cond-> (:imported-file-id message)
                    (assoc :imported-file-id (:imported-file-id message)))
                  (d/without-nils)))
            entry))
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

    :process-deleted-components
    (tr "dashboard.import.progress.process-components")

    ""))

(defn- has-status-analyze?
  [item]
  (= (:status item) :analyze))

(defn- has-status-import-success?
  [item]
  (= (:status item) :import-success))

(defn- has-status-error?
  [item]
  (or (= (:status item) :import-error)
      (= (:status item) :analyze-error)))

(defn- has-status-ready?
  [item]
  (and (= :import-ready (:status item))
       (not (:deleted item))))

(defn- analyze-entries
  [state entries]
  (let [features      (get @st/state :features)
        [html-entries other-entries] ((juxt filterv remove) dhi/html-entry? entries)
        streams       (cond-> []
                        (seq other-entries)
                        (conj
                         (->> (mw/ask-many!
                               {:cmd :analyze-import
                                :files other-entries
                                :features features})
                              (rx/mapcat #(rx/delay emit-delay (rx/of %)))))

                        (seq html-entries)
                        (conj
                         (->> (rx/from html-entries)
                              (rx/mapcat dhi/analyze-entry)
                              (rx/mapcat #(rx/delay emit-delay (rx/of %))))))]
    (->> (if (seq streams) (apply rx/merge streams) (rx/empty))
         (rx/filter some?)
         (rx/subs!
          (fn [message]
            (when (and (some? (:error message))
                       (not (dhi/html-entry? message)))
              (st/emit! (ev/event {::ev/name "import-files-error"
                                   :error (:error message)})))
            (swap! state update-with-analyze-result message))))))

(defn- import-files
  [state project-id entries css-files]
  (st/emit! (ev/event {::ev/name "import-files"
                       :num-files (count entries)}))

  (let [features      (get @st/state :features)
        [html-entries other-entries] ((juxt filterv remove) dhi/html-entry? entries)
        streams       (cond-> []
                        (seq other-entries)
                        (conj
                         (->> (mw/ask-many!
                               {:cmd :import-files
                                :project-id project-id
                                :files other-entries
                                :features features})
                              (rx/filter (comp uuid? :file-id))))

                        (seq html-entries)
                        (conj
                         (->> (rx/from html-entries)
                              (rx/mapcat #(dhi/import-entry project-id css-files %))
                              (rx/filter some?))))]
    (->> (if (seq streams) (apply rx/merge streams) (rx/empty))
         (rx/subs!
          (fn [message]
            (swap! state update-entry-status message))))))

(mf/defc import-entry*
  {::mf/memo true
   ::mf/private true}
  [{:keys [entries entry edition can-be-deleted importing? on-edit on-change on-delete]}]
  (let [status          (:status entry)
        ;; FIXME: rename to format
        format          (:type entry)

        loading?        (or (= :analyze status)
                            (= :import-progress status)
                            (and importing? (= :import-ready status)))
        analyze-error?  (= :analyze-error status)
        import-success? (= :import-success status)
        import-error?   (= :import-error status)
        import-ready?   (= :import-ready status)

        is-shared?      (:shared entry)
        progress        (:progress entry)

        file-id         (:file-id entry)
        editing?        (and (some? file-id) (= edition file-id))

        editable?       (and (= :legacy-zip format)
                             (= status :import-ready))

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
                   :success  import-success?
                   :error    (or import-error? analyze-error?)
                   :editable (and import-ready? (not editing?)))}

     [:div {:class (stl/css :file-name)}
      (if loading?
        [:> loader* {:width 16 :title (tr "labels.loading")}]
        [:div {:class (stl/css-case
                       :file-icon true
                       :icon-fill import-ready?)}
         (cond
           import-ready?   deprecated-icon/logo-icon
           import-error?   deprecated-icon/close
           import-success? deprecated-icon/tick
           analyze-error?  deprecated-icon/close)])

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
            deprecated-icon/library])])

      [:div {:class (stl/css :edit-entry-buttons)}
       (when ^boolean editable?
         [:button {:on-click on-edit'} deprecated-icon/curve])
       (when ^boolean can-be-deleted
         [:button {:on-click on-delete'} deprecated-icon/delete])]]

     (cond
       analyze-error?
       [:div {:class (stl/css :error-message)}
        (if (some? (:error entry))
          (tr (:error entry))
          (tr "dashboard.import.analyze-error"))]

       import-error?
       [:div {:class (stl/css :error-message)}
        (if (some? (:error entry))
          (tr (:error entry))
          (tr "labels.error"))]

       (and (not import-success?) (some? progress))
       [:div {:class (stl/css :progress-message)} (parse-progress-message progress)])

     ;; This is legacy code, will be removed when legacy-zip format is removed
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
              deprecated-icon/detach]])))]]))

(defn initialize-state
  [entries]
  (fn []
    (mapv #(assoc % :status :analyze) entries)))

(mf/defc import-dialog
  {::mf/register modal/components
   ::mf/register-as :import
   ::mf/props :obj}

  [{:keys [project-id entries css-files template on-finish-import]}]

  (mf/with-effect []
    ;; Revoke all uri's on commonent unmount
    (fn [] (run! wapi/revoke-uri (map :uri entries))))

  (let [state*   (mf/use-state (initialize-state entries))
        entries  (deref state*)

        status*  (mf/use-state :analyze)
        status   (deref status*)

        edition* (mf/use-state nil)
        edition  (deref edition*)

        continue-entries
        (mf/use-fn
         (mf/deps entries css-files)
         (fn []
           (let [entries (filterv has-status-ready? entries)]
             (reset! status* :import-progress)
             (import-files state* project-id entries css-files))))

        continue-template
        (mf/use-fn
         (mf/deps on-finish-import)
         (fn [template]
           (let [on-success
                 (fn [_event]
                   (reset! status* :import-success)
                   (when (fn? on-finish-import)
                     (on-finish-import)))

                 on-error
                 (fn [cause]
                   (reset! status* :error)
                   (ex/print-throwable cause)
                   (rx/of (modal/hide)
                          (ntf/error (tr "dashboard.libraries-and-templates.import-error"))))

                 params
                 {:project-id project-id
                  :template-id (:id template)}]

             (reset! status* :import-progress)
             (st/emit! (dd/clone-template
                        (with-meta params
                          {:on-success on-success
                           :on-error on-error}))))))

        on-edit
        (mf/use-fn
         (fn [file-id _event]
           (reset! edition* file-id)))

        on-entry-change
        (mf/use-fn
         (fn [file-id value]
           (swap! edition* (constantly nil))
           (swap! state* update-entry-name file-id value)))

        on-entry-delete
        (mf/use-fn
         (fn [file-id]
           (swap! state* remove-entry file-id)))

        on-cancel
        (mf/use-fn
         (mf/deps edition)
         (fn [event]
           (when (nil? edition)
             (dom/prevent-default event)
             (st/emit! (modal/hide)))))

        on-continue
        (mf/use-fn
         (mf/deps continue-template
                  continue-entries)
         (fn [event]
           (dom/prevent-default event)
           (if (some? template)
             (continue-template template)
             (continue-entries))))

        on-accept
        (mf/use-fn
         (mf/deps on-finish-import)
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (when (fn? on-finish-import)
             (on-finish-import))))

        entries
        (mf/with-memo [entries]
          (filterv (complement :deleted) entries))

        import-success-total
        (if (some? template)
          1
          (count (filterv has-status-import-success? entries)))

        errors?
        (if (some? template)
          (= status :error)
          (or (some has-status-error? entries)
              (zero? (count entries))))

        pending-analysis?
        (some has-status-analyze? entries)]

    (mf/with-effect [entries]
      (cond
        (some? template)
        (reset! status* :import-ready)

        (and (seq entries)
             (every? #(= :import-ready (:status %)) entries))
        (reset! status* :import-ready)

        (and (seq entries)
             (every? #(= :import-success (:status %)) entries))
        (reset! status* :import-success)

        (and (seq entries)
             (and (every? #(not= :import-ready (:status %)) entries)
                  (some #(= :import-error (:status %)) entries)))
        (reset! status* :import-error)))

    ;; Run analyze operation on component mount
    (mf/with-effect []
      (let [sub (analyze-entries state* entries)]
        (partial rx/dispose! sub)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2  {:class (stl/css :modal-title)} (tr "dashboard.import")]

       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       (when (and (= :analyze status) errors?)
         [:& context-notification
          {:level :warning
           :class (stl/css :context-notification-error)
           :content (tr "dashboard.import.import-warning")}])

       (when (= :import-success status)
         [:& context-notification
          {:level (if (zero? import-success-total) :warning :success)
           :content (tr "dashboard.import.import-message" (i18n/c import-success-total))}])

       (when (= :import-error status)
         [:& context-notification
          {:level :error
           :class (stl/css :context-notification-error)
           :content (tr "dashboard.import.import-error.disclaimer")}])

       (if (or (= :import-error status) (and (= :analyze status) errors?))
         [:div {:class (stl/css :import-error-disclaimer)}
          [:div (tr "dashboard.import.import-error.message1")]
          [:ul {:class (stl/css :import-error-list)}
           (for [entry entries]
             (when (contains? #{:import-error :analyze-error} (:status entry))
               [:li {:class (stl/css :import-error-list-enry)
                     :key (dm/str (or (:file-id entry) (:uri entry) (:name entry)))}
                [:div (:name entry)]
                (when-let [err (:error entry)]
                  [:div {:class (stl/css :import-error-detail)}
                   ;; Temporary frontend-side error translations to provide more meaningful
                   ;; messages until backend error handling is improved and standardized.
                   ;; These mappings are only a short-term workaround and should be removed
                   ;; once the error handling enhancement is implemented.
                   ;; https://github.com/penpot/penpot/issues/9884
                   (cond
                     (and (string? err)
                          (str/includes? (str/lower err) "check error"))
                     (tr "dashboard.import.import-error.check-error")

                     (and (string? err)
                          (str/includes? (str/lower err) "corrupt"))
                     (tr "dashboard.import.import-error.corrupt-file")

                     (and (= :html-subset (:type entry))
                          (string? err))
                     err

                     :else
                     (tr "dashboard.import.import-error.unknown-error"))])]))]
          [:div (tr "dashboard.import.import-error.message2")]]

         (for [entry entries]
           [:> import-entry* {:edition edition
                              :key (dm/str (:uri entry) "/" (:file-id entry))
                              :entry entry
                              :entries entries
                              :importing? (= :import-progress status)
                              :on-edit on-edit
                              :on-change on-entry-change
                              :on-delete on-entry-delete
                              :can-be-deleted (> (count entries) 1)}]))

       (when (some? template)
         [:> import-entry* {:entry (assoc template :status status)
                            :can-be-deleted false}])

       (when (= :import-progress status)
         [:div {:class (stl/css :status-message)
                :role "status"
                :aria-live "polite"}
          (tr "labels.uploading-file")])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        (when (= :analyze status)
          [:input {:class (stl/css :cancel-button)
                   :type "button"
                   :value (tr "labels.cancel")
                   :on-click on-cancel}])

        (when (= status :import-ready)
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.continue")
                   :disabled pending-analysis?
                   :on-click on-continue}])

        (when (or (= :import-success status)
                  (= :import-error status)
                  (= :import-progress status))
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.accept")
                   :disabled (= :import-progress status)
                   :on-click on-accept}])]]]]))
