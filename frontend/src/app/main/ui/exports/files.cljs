;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.exports.files
  "The files export dialog/modal"
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.exports.files :as fexp]
   [app.main.data.imports :as imp]
   [app.main.data.jobs :as dj]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.jobs.progress :as jp]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- same-file?
  "Whether an id from a message names a row of the dialog. The ids cross
  serialization boundaries, so a message can carry the id as a string
  while the row keeps the uuid (or the other way round): both sides are
  canonicalized instead of assuming one type."
  [a b]
  (= (str a) (str b)))

(defn- error-hint
  "What a failed row reports: the message of the failure when it says
  something useful, or nil. A string error already is the message; a map
  carries it under `:hint`; anything else is unwrapped with `ex-data`
  first, which itself can answer a string."
  [error]
  (let [data (cond
               (string? error) error
               (map? error)    error
               :else           (ex-data error))]
    (some-> (if (string? data) data (:hint data))
            (str/trim)
            (not-empty))))

(defn- mark-file-error
  "The export of a file failed: a terminal state that replaces any
  other, so a row never reports success and failure at once."
  [files file-id error]
  (mapv (fn [file]
          (if (same-file? file-id (:id file))
            (-> file
                (assoc :export-error true
                       :error error
                       :loading false)
                (dissoc :export-success :size :progress :queued))
            file))
        files))

(defn- mark-file-cancelled
  "The job of a file was cancelled elsewhere: no artifact is coming,
  a neutral terminal state, replacing any other."
  [files file-id]
  (mapv (fn [file]
          (if (same-file? file-id (:id file))
            (-> file
                (assoc :export-cancelled true
                       :loading false)
                (dissoc :export-success :size :export-error :error
                        :progress :queued))
            file))
        files))

(defn- mark-all-cancelled
  "Every file still loading is over: its job was just cancelled, or it
  was never created because the exports run one after another."
  [files]
  (mapv (fn [file]
          (if (:loading file)
            (-> file
                (assoc :export-cancelled true
                       :loading false)
                (dissoc :progress :queued))
            file))
        files))

(defn- mark-file-success
  "The export of a file produced its artifact: a terminal state that
  replaces any other, so a row never reports success and failure at
  once."
  [files file-id size]
  (mapv (fn [file]
          (if (same-file? file-id (:id file))
            (-> file
                (assoc :export-success true
                       :size size
                       :loading false)
                (dissoc :export-error :error :progress :queued))
            file))
        files))

(defn- mark-file-progress
  "The milestone the job of a file is in, to show it while it runs."
  [files file-id progress]
  (mapv #(cond-> %
           (same-file? file-id (:id %))
           (assoc :progress progress
                  :queued false))
        files))

(defn- mark-file-queued
  "The job of a file exists but no worker picked it up yet."
  [files file-id]
  (mapv #(cond-> %
           (same-file? file-id (:id %))
           (assoc :queued true))
        files))

(defn- initialize-state
  "Initialize export dialog state"
  [files]
  (let [files (mapv (fn [file] (assoc file :loading true)) files)]
    {:status :prepare
     :selected :include-libraries
     :files files}))

(mf/defc export-entry*
  {::mf/private true}
  [{:keys [file]}]
  (let [success?   (:export-success file)
        error?     (:export-error file)
        cancelled? (:export-cancelled file)
        progress   (:progress file)
        running?   (and (:loading file) (some? progress))
        ;; the job of the file exists but no worker picked it up yet: its
        ;; queue only ends with a milestone, so until then the row waits
        waiting?   (and (:loading file) (nil? progress))
        size       (imp/format-file-size (:size file))
        ;; the size of the artifact only exists once the job produced it:
        ;; until then the subtitle only says what it will be
        subtitle   (if (some? size) (dm/str size " .penpot") ".penpot")]
    [:div {:class (stl/css-case
                   :file-entry true
                   :success success?
                   :error error?)}

     [:div {:class (stl/css :file-main)}
      [:div {:class (stl/css :file-icon)
             :aria-hidden "true"}
       [:> icon* {:icon-id (if error? i/close i/folder) :size "m"}]]

      [:div {:class (stl/css :file-meta)}
       [:> text* {:class (stl/css :file-name-label)
                  :as "span"
                  :typography t/body-medium}
        (:name file)]
       [:> text* {:class (stl/css :file-subtitle)
                  :as "span"
                  :typography t/body-small}
        subtitle]]

      ;; a row carries its name and what its artifact will weigh, and only
      ;; the state that is its own: while the file runs, the milestone and
      ;; the bar live under it
      (cond
        success?
        [:div {:class (stl/css :file-status)}
         [:> icon* {:icon-id i/status-tick :size "s"}]
         [:> text* {:as "span"
                    :typography t/body-medium}
          (tr "files-export-modal.status.exported")]]

        waiting?
        [:div {:class (stl/css :file-status)}
         [:> icon* {:icon-id i/clock :size "s"}]
         [:> text* {:as "span"
                    :typography t/body-medium}
          (tr "files-export-modal.status.waiting")]])]

     ;; the milestone of the job, with an animated bar: the stages of an
     ;; export are not comparable one with another, so the client cannot
     ;; know a percentage of the file, only that it moves
     (when running?
       [:div {:class (stl/css :file-progress)}
        [:> text* {:class (stl/css :progress-message)
                   :as "span"
                   :typography t/body-small
                   :role "status"
                   :aria-live "polite"}
         (jp/milestone-text progress)]
        [:div {:class (stl/css :progress-bar)
               :role "progressbar"}
         [:div {:class (stl/css :progress-fill)}]]])

     (when error?
       [:div {:class (stl/css :file-error)}
        [:> text* {:class (stl/css :error-message)
                   :as "span"
                   :typography t/body-small}
         ;; the reason is user-facing text already (the hint of the job),
         ;; never a key
         (tr "files-export-modal.export-error"
             (or (error-hint (:error file))
                 (tr "labels.error")))]])

     ;; a cancelled file is a neutral terminal state, but the label is
     ;; what tells the user why no artifact came out
     (when cancelled?
       [:div {:class (stl/css :file-error)}
        [:> text* {:class (stl/css :status-message)
                   :as "span"
                   :typography t/body-small
                   :role "status"
                   :aria-live "polite"}
         (tr "jobs.export-cancelled")]])]))

(mf/defc export-phase-header*
  {::mf/private true}
  [{:keys [files]}]
  (let [total    (count files)
        done     (count (filterv :export-success files))
        ;; every file is its own job, so the header only counts them: the
        ;; progress of each one lives in its row, there is no global bar
        running? (boolean (some :loading files))]
    [:div {:class (stl/css :phase-header)}
     [:> heading* {:level 3
                   :typography t/headline-medium
                   :class (stl/css :phase-title)}
      (if running?
        (tr "files-export-modal.exporting.title" (i18n/c total))
        (tr "files-export-modal.exported.title" (i18n/c done)))]
     [:> text* {:as "p"
                :typography t/body-medium
                :class (stl/css :phase-message)}
      (if running?
        (tr "files-export-modal.exporting.message")
        (tr "files-export-modal.exported.message"))]]))

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as ::fexp/export-files
   ::mf/props :obj}
  [{:keys [team-id files unsaved]}]
  (let [state*       (mf/use-state (partial initialize-state files))
        has-libs?    (some :has-libraries files)
        export-types (cond-> fexp/valid-types
                       (not (contains? cf/flags :export-link-later))
                       (disj :link-later))

        state        (deref state*)
        selected     (:selected state)
        status       (:status state)
        ;; files still in flight: the dialog status never leaves
        ;; :exporting, so loading rows are the signal of a running
        ;; export, not the status
        in-progress? (boolean (->> state :files (some :loading)))

        ;; Jobs still running, as file-id -> job-id, to cancel them on
        ;; demand; a file that reaches its outcome leaves the map, so
        ;; closing a finished export cancels nothing. And the
        ;; subscription to their messages, to stop listening.
        jobs*        (mf/use-state {})
        jobs         (deref jobs*)
        sub*         (mf/use-state nil)
        sub          (deref sub*)

        start-export
        (mf/use-fn
         (mf/deps team-id selected files)
         (fn []
           (swap! state* assoc :status :exporting)
           (reset! jobs* {})
           (reset! sub* (->> (fexp/export-files :files files :type selected
                                                :on-job #(swap! jobs* assoc (:file-id %) (:job-id %)))
                             (rx/subs!
                              (fn [{:keys [file-id error filename uri size progress queued
                                           cancelled]}]
                                (cond
                                  (some? progress)
                                  (swap! state* update :files mark-file-progress file-id progress)

                                  (true? queued)
                                  (swap! state* update :files mark-file-queued file-id)

                                  (some? error)
                                  (swap! jobs* dissoc file-id)
                                  (swap! state* update :files mark-file-error file-id error)

                                  (true? cancelled)
                                  (swap! jobs* dissoc file-id)
                                  (swap! state* update :files mark-file-cancelled file-id)

                                  ;; only a message carrying the artifact
                                  ;; downloads: anything else is ignored
                                  (some? uri)
                                  (do
                                    (swap! jobs* dissoc file-id)
                                    (swap! state* update :files mark-file-success file-id size)
                                    (dom/trigger-download-uri filename "application/penpot" uri)))))))))

        on-stop-jobs
        (mf/use-fn
         (mf/deps jobs sub)
         (fn []
           ;; stop listening first, so no late message repaints the rows
           (when (some? sub)
             (rx/dispose! sub))
           ;; only the jobs still in flight are cancelled: finished ones
           ;; already left the map, and failures are ignored because a job may have just finished on its own
           (run! dj/cancel-job (vals jobs))
           (reset! jobs* {})
           (reset! sub* nil)))

        on-cancel-export
        (mf/use-fn
         (mf/deps on-stop-jobs state*)
         (fn [event]
           (dom/prevent-default event)
           (on-stop-jobs)
           ;; the dialog stays open with the terminal states: closing it
           ;; is what dismisses it, cancelling whatever is still pending
           (swap! state* update :files mark-all-cancelled)))

        on-close
        (mf/use-fn
         (mf/deps on-stop-jobs)
         (fn [event]
           (dom/prevent-default event)
           ;; closing dismisses the dialog, cancelling whatever is still
           ;; pending, so an export never outlives its dialog
           (on-stop-jobs)
           (st/emit! (modal/hide))))

        on-cancel
        (mf/use-fn
         (mf/deps status in-progress? on-cancel-export on-close)
         (fn [event]
           ;; the X cancels like Cancel Export while files are still in
           ;; flight, and dismisses like Done once everything reached a
           ;; terminal state
           (if (and (= :exporting status) in-progress?)
             (on-cancel-export event)
             (on-close event))))

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
       [:> heading* {:level 2
                     :typography t/headline-large
                     :class (stl/css :modal-title)}
        (tr "files-export-modal.title")]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-cancel
                         :class (stl/css :modal-close-btn)
                         :icon i/close}]]

      (when unsaved
        [:> context-notification* {:level :warning
                                   :appearance :ghost
                                   :class (stl/css :unsaved-warning)}
         (tr "files-download-modal.unsaved-changes")])

      (cond
        (= status :prepare)
        [:*
         [:div {:class (stl/css :modal-content)}
          ;; TODO: Add translation
          [:> text* {:as "p" :typography t/body-large :class (stl/css :modal-msg)}
           "What do you want to do with linked libraries?"]

          (for [type export-types]
            [:div {:class (stl/css :export-option true)
                   :key (name type)}
             [:label {:for (str "export-" type)
                      :class (stl/css :export-option-label)}
              [:span {:class (stl/css-case
                              :option-icon-wrapper true
                              :checked (= selected type))}
               (when (= selected type)
                 [:svg {:class (stl/css :option-icon)
                        :viewBox "0 0 8 8"
                        :width 8
                        :height 8
                        :aria-hidden true}
                  [:circle {:cx 4 :cy 4 :r 4}]])]

              [:div {:class (stl/css :option-content)}
               [:> heading* {:level 3
                             :typography t/body-large
                             :class (stl/css :option-title)}
                (case type
                  :include-libraries (tr "files-export-modal.options.include-libraries.title")
                  :merge-libraries (tr "files-export-modal.options.merge-libraries.title")
                  :detach-libraries (tr "files-export-modal.options.detach-libraries.title")
                  :link-later (tr "files-export-modal.options.link-later.title"))]
               [:> text* {:as "p" :typography t/body-large :class (stl/css :modal-msg)}
                (case type
                  :include-libraries (tr "files-export-modal.options.include-libraries.message")
                  :merge-libraries (tr "files-export-modal.options.merge-libraries.message")
                  :detach-libraries (tr "files-export-modal.options.detach-libraries.message")
                  :link-later (tr "files-export-modal.options.link-later.message"))]]

              [:input {:type "radio"
                       :class (stl/css :option-input)
                       :id (str "export-" type)
                       :checked (= selected type)
                       :name "export-option"
                       :data-type (name type)
                       :on-change on-change}]]])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:> button* {:variant "secondary"
                        :type "button"
                        :on-click on-close}
            (tr "labels.cancel")]

           [:> button* {:variant "primary"
                        :type "button"
                        :on-click on-accept}
            (tr "labels.continue")]]]]

        (= status :exporting)
        [:*
         [:div {:class (stl/css :modal-content)}
          [:> export-phase-header* {:files (:files state)}]
          (for [file (:files state)]
            [:> export-entry* {:file file :key (dm/str (:id file))}])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           (if in-progress?
             [:> button* {:variant "secondary"
                          :type "button"
                          :on-click on-cancel-export}
              (tr "files-export-modal.cancel-export")]
             [:> button* {:variant "primary"
                          :type "button"
                          :on-click on-close}
              (tr "files-export-modal.done")])]]])]]))
