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
   [app.main.data.jobs :as dj]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.jobs.progress :as jp]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
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

(defn- mark-file-cancelled
  "The job of a file was cancelled elsewhere: a neutral terminal state,
  neither success nor error."
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-cancelled? true
                  :loading false))
        files))

(defn- mark-file-success
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-success? true
                  :loading false))
        files))

(defn- mark-file-progress
  "The milestone the job of a file is in, to show it while it runs."
  [files file-id progress]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :progress progress
                  :queued false))
        files))

(defn- mark-file-queued
  "The job of a file exists but no worker picked it up yet."
  [files file-id queued?]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :queued queued?))
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
  (let [level (cond
                (:export-success? file) :success
                (:export-error? file)   :error
                :else                   :info)]
    [:div {:class (stl/css-case
                   :file-entry true
                   :loading  (:loading file)
                   :success  (:export-success? file)
                   :error    (:export-error? file))}

     (if (:loading file)
       [:*
        [:div {:class (stl/css :file-name)}
         [:> loader*  {:width 26
                       :title (tr "labels.loading")}]
         [:> text* {:class (stl/css :file-name-label)
                    :as "span"
                    :typography t/body-large}
          (:name file)]]

        (when (or (some? (:progress file)) (:queued file) (:export-cancelled? file))
          [:> text* {:class (stl/css :status-message)
                     :as "span"
                     :typography t/body-large
                     :role "status"
                     :aria-live "polite"}
           (cond (:export-cancelled? file) (tr "labels.export-cancelled")
                 (some? (:progress file))  (jp/milestone-text (:progress file))
                 :else                      (tr "labels.queued"))])]

       [:> context-notification {:level level
                                 :content (:name file)}])]))

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as ::fexp/export-files
   ::mf/props :obj}
  [{:keys [team-id files]}]
  (let [state*       (mf/use-state (partial initialize-state files))
        has-libs?    (some :has-libraries files)
        export-types (cond-> fexp/valid-types
                       (not (contains? cf/flags :export-link-later))
                       (disj :link-later))

        state        (deref state*)
        selected     (:selected state)
        status       (:status state)

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
                              (fn [{:keys [file-id error filename uri progress queued started
                                           cancelled]}]
                                (cond
                                  (some? progress)
                                  (swap! state* update :files mark-file-progress file-id progress)

                                  (true? queued)
                                  (swap! state* update :files mark-file-queued file-id true)

                                  (true? started)
                                  (swap! state* update :files mark-file-queued file-id false)

                                  (some? error)
                                  (swap! jobs* dissoc file-id)
                                  (swap! state* update :files mark-file-error file-id)

                                  (true? cancelled)
                                  (swap! jobs* dissoc file-id)
                                  (swap! state* update :files mark-file-cancelled file-id)

                                  ;; only a message carrying the artifact
                                  ;; downloads: anything else is ignored
                                  (some? uri)
                                  (do
                                    (swap! jobs* dissoc file-id)
                                    (swap! state* update :files mark-file-success file-id)
                                    (dom/trigger-download-uri filename "application/penpot" uri)))))))))

        on-cancel-export
        (mf/use-fn
         (mf/deps jobs sub)
         (fn [event]
           (dom/prevent-default event)
           ;; stop listening first, so no late message repaints the
           ;; entries of a dialog that is going away
           (when (some? sub)
             (rx/dispose! sub))
           ;; only the jobs still in flight are cancelled: finished ones
           ;; already left the map, and failures are ignored because a job may have just finished on its own
           (run! dj/cancel-job (vals jobs))
           (reset! jobs* {})
           (reset! sub* nil)
           (st/emit! (modal/hide))))

        on-cancel
        (mf/use-fn
         (mf/deps status on-cancel-export)
         (fn [event]
           (if (= :exporting status)
             ;; closing mid-export stops the jobs, like Cancel
             (on-cancel-export event)
             (do
               (dom/prevent-default event)
               (st/emit! (modal/hide))))))

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
        (tr "files-download-modal.title")]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-cancel
                         :class (stl/css :modal-close-btn)
                         :icon i/close}]]
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
                        :on-click on-cancel}
            (tr "labels.cancel")]

           [:> button* {:variant "primary"
                        :type "button"
                        :on-click on-accept}
            (tr "labels.continue")]]]]

        (= status :exporting)
        (let [in-progress? (->> state :files (some :loading))]
          [:*
           [:div {:class (stl/css :modal-content)}
            (for [file (:files state)]
              [:> export-entry* {:file file :key (dm/str (:id file))}])]

           [:div {:class (stl/css :modal-footer)}
            [:div {:class (stl/css :action-buttons)}
             (when in-progress?
               [:> button* {:variant "secondary"
                            :type "button"
                            :on-click on-cancel-export}
                (tr "labels.cancel")])
             [:> button* {:variant "primary"
                          :type "button"
                          :disabled in-progress?
                          :on-click on-cancel}
              (tr "labels.close")]]]]))]]))
