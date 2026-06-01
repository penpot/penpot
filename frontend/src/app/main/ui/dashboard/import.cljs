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
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
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

(mf/defc import-form*
  {::mf/forward-ref true}
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

(defn- has-unresolved?
  "Return true if a file-resolution has any :pending needing user choice."
  [file-resolution]
  (some? (seq (:pending file-resolution))))

(defn- count-auto-linked
  "Count auto-linked libraries across all file resolutions."
  [resolution]
  (reduce-kv (fn [acc _ {:keys [done]}]
               (+ acc (count done)))
             0
             resolution))

(defn- analyze-entries
  [state entries]
  (let [features (get @st/state :features)]
    (->> (mw/ask-many!
          {:cmd :analyze-import
           :files entries
           :features features})
         (rx/mapcat #(rx/delay emit-delay (rx/of %)))
         (rx/filter some?)
         (rx/subs!
          (fn [message]
            (when (some? (:error message))
              (st/emit! (ev/event {::ev/name "import-files-error"
                                   :error (:error message)})))
            (swap! state update-with-analyze-result message))))))

(defn- import-files
  [state library-resolution-data* project-id entries]
  (st/emit! (ev/event {::ev/name "import-files"
                       :num-files (count entries)}))

  (let [features (get @st/state :features)]
    (->> (mw/ask-many!
          {:cmd :import-files
           :project-id project-id
           :files entries
           :features features})
         (rx/filter some?)
         (rx/subs!
          (fn [message]
            ;; Capture library-resolution data if present (same for all
            ;; entries from the same zip, so first one wins)
            (if-let [resolution  (-> (:libraries-resolution message)
                                     (not-empty))]
              (reset! library-resolution-data* resolution)
              (swap! state update-entry-status message)))))))

(mf/defc import-entry*
  {::mf/memo true
   ::mf/private true}
  [{:keys [entries entry edition can-be-deleted is-progress on-edit on-change on-delete]}]
  (let [status          (:status entry)
        ;; FIXME: rename to format
        format          (:type entry)

        loading?        (or (= :analyze status)
                            (= :import-progress status)
                            (and is-progress (= :import-ready status)))
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

(defn- link-files-to-library!
  "Call the link-file-to-library RPC for each file-id with the given
  library-id. Returns an observable that completes when all links are done."
  [file-ids library-id]
  (->> (rx/from file-ids)
       (rx/merge-map (fn [file-id]
                       (->> (rp/cmd! :link-file-to-library
                                     {:file-id file-id
                                      :library-id library-id})
                            (rx/catch (fn [cause]
                                        (log/error :hint "failed to link library"
                                                   :file-id file-id
                                                   :library-id library-id
                                                   :cause cause)
                                        (rx/of nil))))))))

(mf/defc library-resolution*
  {::mf/private true}
  [{:keys [unresolved-file selection on-select]}]
  (let [candidates (:pending unresolved-file)]

    ;; Pre-select first candidate for each library
    (mf/with-effect [candidates]
      (doseq [{:keys [id candidates]} candidates]
        (when-not (contains? selection id)
          (when-let [first-c (first candidates)]
            (on-select id (:id first-c))))))

    [:div {:class (stl/css :library-resolution)}
     [:p {:class (stl/css :library-resolution-message)}
      (tr "dashboard.import.resolve-libraries")]

     (for [{:keys [id name candidates]} candidates]
       (let [options  (mapv (fn [c]
                              {:id (str (:id c))
                               :label (str (:name c) " (" (:project-name c) ")")})
                            candidates)
             selected (get selection id)]
         [:div {:class (stl/css :library-resolution-item)
                :key (dm/str id)}
          [:div {:class (stl/css :library-resolution-item-name)}
           name]
          [:> select* {:options options
                       :default-selected (or (some-> selected str) "")
                       :on-change (partial on-select id)}]]))]))

(mf/defc library-resolution-summary-file*
  {::mf/private true}
  [{:keys [resolution-file selection]}]
  (let [done    (:done resolution-file)
        pending (:pending resolution-file)]
    [:div {:class (stl/css :summary-file)}
     [:div {:class (stl/css :summary-file-header)}
      [:> icon* {:icon-id i/library
                 :class (stl/css :summary-file-icon)
                 :size "s"}]
      [:span {:class (stl/css :summary-file-name)}
       (:name resolution-file)]]

     (when (seq done)
       [:div {:class (stl/css :summary-section)}
        [:div {:class (stl/css :summary-section-header)}
         [:> icon* {:icon-id i/status-tick
                    :class (stl/css :summary-section-icon)
                    :size "s"}]
         [:span (tr "dashboard.import.summary.auto-linked")]]
        [:ul {:class (stl/css :summary-list)}
         (for [{:keys [name]} done]
           [:li {:class (stl/css :summary-list-item)
                 :key (dm/str name)}
            [:span {:class (stl/css :summary-item-name)} name]
            [:span {:class (stl/css :summary-linked-badge)}
             [:> icon* {:icon-id i/status-tick
                        :class (stl/css :summary-badge-icon)
                        :size "s"}]
             (tr "dashboard.import.summary.linked")]])]])

     (when (seq pending)
       [:div {:class (stl/css :summary-section)}
        [:div {:class (stl/css :summary-section-header)}
         [:> icon* {:icon-id i/open-link
                    :class (stl/css :summary-section-icon)
                    :size "s"}]
         [:span (tr "dashboard.import.summary.your-selection")]]
        [:ul {:class (stl/css :summary-list)}
         (for [{:keys [id name] :as cand} pending]
           (let [selected-id (get selection id)
                 selected-c  (when selected-id
                               (d/seek #(= (:id %) selected-id) (:candidates cand)))]
             [:li {:class (stl/css :summary-list-item)
                   :key (dm/str id)}
              [:span {:class (stl/css :summary-item-name)} name]
              (if selected-c
                [:span {:class (stl/css :summary-linked-info)}
                 [:span {:class (stl/css :summary-linked-name)}
                  (:name selected-c)]
                 [:span {:class (stl/css :summary-linked-project)}
                  (:project-name selected-c)]]
                [:span {:class (stl/css :summary-no-selection)}
                 (tr "dashboard.import.summary.no-selection")])]))]])]))

(mf/defc library-resolution-summary*
  {::mf/private true}
  [{:keys [resolution selection]}]
  [:div {:class (stl/css :library-resolution)}
   [:p {:class (stl/css :library-resolution-message)}
    (tr "dashboard.import.resolve-libraries-summary")]

   (for [[file-id resolution-file] resolution]
     [:> library-resolution-summary-file*
      {:key (dm/str file-id)
       :resolution-file resolution-file
       :selection selection}])])

(mf/defc import-dialog
  {::mf/register modal/components
   ::mf/register-as :import
   ::mf/props :obj}

  [{:keys [project-id entries template on-finish-import]}]

  (mf/with-effect []
    ;; Revoke all uri's on commonent unmount
    (fn [] (run! wapi/revoke-uri (map :uri entries))))

  (let [state*      (mf/use-state (initialize-state entries))
        entries     (deref state*)

        status*     (mf/use-state :analyze)
        status      (deref status*)

        edition*    (mf/use-state nil)
        edition     (deref edition*)

        ;; Library resolution data from the backend (auto-linked + multi-match)
        resolution* (mf/use-state nil)
        resolution  (not-empty (deref resolution*))

        ;; User selection for multi-match candidates: {old-lib-id candidate-id}
        selection*  (mf/use-state {})
        selection   (deref selection*)

        ;; Wizard progression as an ordered "visited" stack of file-ids.
        ;; `current-file` is derived: the first unresolved file NOT yet in `visited`.
        ;; No numeric step counter — forward = conj, back = pop.
        visited*    (mf/use-state #(d/ordered-set))
        visited     (deref visited*)

        ;; Derived: files that need user resolution (have :candidates)
        unresolved-files
        (mf/with-memo [resolution]
          (when resolution
            (reduce-kv (fn [acc _ v]
                         (if (has-unresolved? v)
                           (conj acc v)
                           acc))
                       []
                       resolution)))

        all-visited?
        (mf/with-memo [visited unresolved-files]
          (when (seq unresolved-files)
            (every? #(contains? visited (:id %)) unresolved-files)))

        ;; Current file shown in the wizard step: first unresolved file not yet visited.
        current-unresolved-file
        (mf/with-memo [unresolved-files visited]
          (d/seek #(not (contains? visited (:id %))) unresolved-files))

        continue-entries
        (mf/use-fn
         (mf/deps entries)
         (fn []
           (let [entries (filterv has-status-ready? entries)]
             (reset! status* :import-progress)
             (import-files state* resolution* project-id entries))))

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

        on-confirm-library-links
        (mf/use-fn
         (mf/deps resolution selection on-finish-import)
         (fn [event]
           (dom/prevent-default event)
           (let [selection @selection*]
             ;; For each file with pending candidates, link it to the selected libraries
             (->> (rx/from (seq resolution))
                  (rx/merge-map
                   (fn [[file-id resolution-file]]
                     (->> (rx/from (:pending resolution-file))
                          (rx/merge-map
                           (fn [{:keys [id]}]
                             (when-let [selected-lib (get selection id)]
                               (link-files-to-library! [file-id] selected-lib)))))))
                  (rx/subs! (constantly nil)
                            (constantly nil)
                            (fn []
                              (st/emit! (modal/hide))
                              (when (fn? on-finish-import)
                                (on-finish-import))))))))

        on-wizard-next
        (mf/use-fn
         (mf/deps current-unresolved-file visited)
         (fn []
           (let [file-id (:id current-unresolved-file)]
             (swap! visited* conj file-id))))

        on-wizard-prev
        (mf/use-fn
         (mf/deps current-unresolved-file)
         (fn []
           ;; Remove the current file from visited; it becomes current again after re-render,
           ;; because it's no longer in visited.
           (let [file-id (:id current-unresolved-file)]
             (swap! visited* disj file-id))))

        on-summary-back
        (mf/use-fn
         (mf/deps visited)
         (fn []
           (let [last-id (last visited)]
             (swap! visited* disj last-id)
             (reset! status* :library-resolution))))

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
        (some has-status-analyze? entries)

        auto-linked-count
        (if (some? resolution)
          (count-auto-linked resolution)
          0)]

    (mf/with-effect [visited unresolved-files]
      (when (and (seq unresolved-files)
                 (every? #(contains? visited (:id %)) unresolved-files))
        (reset! status* :library-summary)))

    (mf/with-effect [entries resolution]
      (cond
        (some? template)
        (reset! status* :import-ready)

        (and (seq entries)
             (every? #(= :import-ready (:status %)) entries))
        (reset! status* :import-ready)

        (and (seq entries)
             (every? #(= :import-success (:status %)) entries))
        (reset! status* (if (seq resolution)
                          (if (seq (filter has-unresolved? (vals resolution)))
                            :library-resolution
                            :library-summary)
                          :import-success))
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

       (cond
         (and (= :analyze status) errors?)
         [:& context-notification
          {:level :warning
           :class (stl/css :context-notification-error)
           :content (tr "dashboard.import.import-warning")}]

         (= :import-success status)
         [:*
          [:& context-notification
           {:level (if (zero? import-success-total) :warning :success)
            :content (tr "dashboard.import.import-message" (i18n/c import-success-total))}]
          (when (pos? auto-linked-count)
            [:& context-notification
             {:level :success
              :content (tr "dashboard.import.auto-linked-libraries" (i18n/c auto-linked-count))}])]

         (= :import-error status)
         [:& context-notification
          {:level :error
           :class (stl/css :context-notification-error)
           :content (tr "dashboard.import.import-error.disclaimer")}]

         ;; :resolution — wizard step (current derived file)
         (= :library-resolution status)
         [:> library-resolution*
          {:unresolved-file current-unresolved-file
           :selection selection
           :on-select (fn [old-lib-id candidate-id]
                        (swap! selection* assoc old-lib-id candidate-id))}]

         ;; :library-summary — show all files with final resolution
         (= :library-summary status)
         [:> library-resolution-summary*
          {:resolution resolution
           :selection selection}])

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

                     :else
                     (tr "dashboard.import.import-error.unknown-error"))])]))]

          [:div (tr "dashboard.import.import-error.message2")]]

         (when-not (or (= :library-resolution status)
                       (= :library-summary status))
           (for [entry entries]
             [:> import-entry* {:edition edition
                                :key (dm/str (:uri entry) "/" (:file-id entry))
                                :entry entry
                                :entries entries
                                :is-progress (= :import-progress status)
                                :on-edit on-edit
                                :on-change on-entry-change
                                :on-delete on-entry-delete
                                :can-be-deleted (> (count entries) 1)}])))

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
        (cond
          ;; Wizard step: Next / Previous (Previous only shown if stack is non-empty)
          (= :library-resolution status)
          [:*
           (when (seq visited)
             [:input {:class (stl/css :cancel-button)
                      :type "button"
                      :value (tr "labels.previous")
                      :on-click on-wizard-prev}])
           ;; Label flips to "Review" when this is the last unvisited unresolved file.
           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (if all-visited?
                             (tr "labels.next")
                             (tr "dashboard.import.review-links"))
                    :on-click on-wizard-next}]]

          ;; Summary: Confirm / Back
          ;; Back pops the stack once and re-enters the wizard at the popped file.
          (= :library-summary status)
          [:*
           (when (seq visited)
             [:input {:class (stl/css :cancel-button)
                      :type "button"
                      :value (tr "labels.back")
                      :on-click on-summary-back}])
           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "dashboard.import.confirm-library-links")
                    :on-click on-confirm-library-links}]]

          (= :analyze status)
          [:input {:class (stl/css :cancel-button)
                   :type "button"
                   :value (tr "labels.cancel")
                   :on-click on-cancel}]

          (= status :import-ready)
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.continue")
                   :disabled pending-analysis?
                   :on-click on-continue}]

          (or (= :import-success status)
              (= :import-error status)
              (= :import-progress status))
          [:input {:class (stl/css :accept-btn)
                   :type "button"
                   :value (tr "labels.accept")
                   :disabled (= :import-progress status)
                   :on-click on-accept}])]]]]))
