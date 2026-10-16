;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.exports.files
  "The file exportation API and events"
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dps]
   [app.main.repo :as rp]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def valid-types
  (d/ordered-set :all :merge :detach))

(def valid-formats
  #{:binfile-v1 :binfile-v3 :legacy-zip})

(def ^:private schema:export-files
  [:sequential {:title "Files"}
   [:map {:title "FileParam"}
    [:id ::sm/uuid]
    [:name :string]
    [:project-id ::sm/uuid]
    [:is-shared ::sm/boolean]]])

(def check-export-files
  (sm/check-fn schema:export-files))

;; Longest wait for the pending changes of the downloaded files to be saved.
(def save-wait-timeout-ms 10000)

(defn- show-export-dialog
  "Opens the download dialog, flagging whether the files have changes not
  saved yet."
  [team-id files]
  (ptk/reify ::show-export-dialog
    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (modal/show {:type ::export-files
                          :team-id team-id
                          :files files
                          :unsaved (dps/unsaved-changes? state (into #{} (map :id) files))})))))

(defn open-export-dialog
  [files]
  (let [files    (check-export-files files)
        file-ids (into #{} (map :id) files)]
    (ptk/reify ::export-files
      ptk/WatchEvent
      (watch [_ state _]
        (let [team-id (get state :current-team-id)]
          (rx/merge
           (rx/of (ev/event {::ev/name "export-binary-files"
                             ::ev/origin "dashboard"
                             :format "binfile-v3"
                             :num-files (count files)}))
           ;; The download is built from the server copy: send the pending
           ;; changes first.
           (rx/concat
            (dps/force-persist-files-and-wait file-ids save-wait-timeout-ms)
            (->> (rx/from files)
                 (rx/mapcat
                  (fn [file]
                    (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                         (rx/map #(assoc file :has-libraries %)))))
                 (rx/reduce conj [])
                 (rx/map (partial show-export-dialog team-id))))))))))

(defn export-files
  [& {:keys [type files]}]
  (->> (rx/from files)
       (rx/mapcat
        (fn [file]
          (->> (rp/cmd! ::sse/export-binfile {:file-id (:id file)
                                              :version 3
                                              :include-libraries (= type :all)
                                              :embed-assets (= type :merge)})
               (rx/filter sse/end-of-stream?)
               (rx/map sse/get-payload)
               (rx/map (fn [uri]
                         {:file-id (:id file)
                          :uri uri
                          :filename (:name file)}))
               (rx/catch (fn [cause]
                           (let [error (ex-data cause)]
                             (rx/of {:file-id (:id file)
                                     :error error})))))))))

;;;;;;;;;;;;;;;;;;;;;;
;; Team Request
;;;;;;;;;;;;;;;;;;;;;;

(defn create-team-access-request
  [params]
  (ptk/reify ::create-team-access-request
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-team-access-request params)
             (rx/tap on-success)
             (rx/catch on-error))))))
