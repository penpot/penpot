;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.exports.files
  "The file exportation API and events"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
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

(defn export-files
  [files format]
  (dm/assert!
   "expected valid files param"
   (check-export-files files))

  (dm/assert!
   "expected valid format"
   (contains? valid-formats format))

  (ptk/reify ::export-files
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (get state :features)
            team-id  (:current-team-id state)
            evname   (if (= format :legacy-zip)
                       "export-standard-files"
                       "export-binary-files")]

        (rx/merge
         (rx/of (ptk/event ::ev/event {::ev/name evname
                                       ::ev/origin "dashboard"
                                       :format format
                                       :num-files (count files)}))
         (->> (rx/from files)
              (rx/mapcat
               (fn [file]
                 (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                      (rx/map #(assoc file :has-libraries %)))))
              (rx/reduce conj [])
              (rx/map (fn [files]
                        (modal/show
                         {:type ::export-files
                          :features features
                          :team-id team-id
                          :files files
                          :format format})))))))))

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
