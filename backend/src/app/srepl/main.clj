;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.main
  "A collection of adhoc fixes scripts."
  #_:clj-kondo/ignore
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.pprint :as p]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [app.srepl.fixes :as f]
   [app.srepl.helpers :as h]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.pprint :refer [pprint]]
   [cuerdas.core :as str]))

(defn print-available-tasks
  [system]
  (let [tasks (:app.worker/registry system)]
    (p/pprint (keys tasks) :level 200)))

(defn run-task!
  ([system name]
   (run-task! system name {}))
  ([system name params]
   (let [tasks (:app.worker/registry system)]
     (if-let [task-fn (get tasks name)]
       (task-fn params)
       (println (format "no task '%s' found" name))))))

(defn schedule-task!
  ([system name]
   (schedule-task! system name {}))
  ([system name props]
   (let [pool (:app.db/pool system)]
     (wrk/submit!
      ::wrk/conn pool
      ::wrk/task name
      ::wrk/props props))))

(defn send-test-email!
  [system destination]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (us/verify!
   :expr (string? destination)
   :hint "destination should be provided")

  (let [handler (:app.email/sendmail system)]
    (handler {:body "test email"
              :subject "test email"
              :to [destination]})))

(defn resend-email-verification-email!
  [system email]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (let [sprops  (:app.setup/props system)
        pool    (:app.db/pool system)
        profile (profile/get-profile-by-email pool email)]

    (auth/send-email-verification! pool sprops profile)
    :email-sent))

(defn mark-profile-as-active!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [system email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-active true} {:id (:id profile)})
        :activated))))

(defn mark-profile-as-blocked!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [system email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
        (db/delete! conn :http-session {:profile-id (:id profile)})
        :blocked))))


(defn enable-objects-map-feature-on-file!
  [system & {:keys [save? id]}]
  (letfn [(update-file [{:keys [features] :as file}]
            (if (contains? features "storage/objects-map")
              file
              (-> file
                  (update :data migrate)
                  (update :features conj "storage/objects-map"))))

          (migrate [data]
            (-> data
                (update :pages-index update-vals #(update % :objects omap/wrap))
                (update :components update-vals #(update % :objects omap/wrap))))]

    (h/update-file! system
                    :id id
                    :update-fn update-file
                    :save? save?)))

(defn enable-pointer-map-feature-on-file!
  [system & {:keys [save? id]}]
  (letfn [(update-file [{:keys [features] :as file}]
            (if (contains? features "storage/pointer-map")
              file
              (-> file
                  (update :data migrate)
                  (update :features conj "storage/pointer-map"))))

          (migrate [data]
            (-> data
                (update :pages-index update-vals pmap/wrap)
                (update :components pmap/wrap)))]

    (h/update-file! system
                    :id id
                    :update-fn update-file
                    :save? save?)))

(defn enable-storage-features-on-file!
  [system & {:as params}]
  (enable-objects-map-feature-on-file! system params)
  (enable-pointer-map-feature-on-file! system params))

(defn instrument-var
  [var]
  (alter-var-root var (fn [f]
                        (let [mf (meta f)]
                          (if (::original mf)
                            f
                            (with-meta
                              (fn [& params]
                                (tap> params)
                                (let [result (apply f params)]
                                  (tap> result)
                                  result))
                              {::original f}))))))

(defn uninstrument-var
  [var]
  (alter-var-root var (fn [f]
                        (or (::original (meta f)) f))))

(defn notify!
  [{:keys [::mbus/msgbus ::db/pool]} & {:keys [dest code message level]
                                        :or {code :generic level :info}
                                        :as params}]
  (dm/verify!
   ["invalid level %" level]
   (contains? #{:success :error :info :warning} level))

  (dm/verify!
   ["invalid code: %" code]
   (contains? #{:generic :upgrade-version} code))

  (letfn [(send [dest]
            (l/inf :hint "sending notification" :dest (str dest))
            (let [message {:type :notification
                           :code code
                           :level level
                           :version (:full cf/version)
                           :subs-id dest
                           :message message}
                  message (->> (dissoc params :dest :code :message :level)
                               (merge message))]
              (mbus/pub! msgbus
                         :topic (str dest)
                         :message message)))

          (resolve-profile [email]
            (some-> (db/get* pool :profile {:email (str/lower email)} {:columns [:id]}) :id vector))

          (resolve-team [team-id]
            (->> (db/query pool :team-profile-rel
                           {:team-id team-id}
                           {:columns [:profile-id]})
                 (map :profile-id)))

          (parse-uuid [v]
            (if (uuid? v)
              v
              (d/parse-uuid v)))

          (resolve-dest [dest]
            (cond
              (uuid? dest)
              [dest]

              (string? dest)
              (some-> dest parse-uuid resolve-dest)

              (nil? dest)
              (resolve-dest uuid/zero)

              (map? dest)
              (sequence (comp
                         (map vec)
                         (mapcat resolve-dest))
                        dest)

              (and (coll? dest)
                   (every? coll? dest))
              (sequence (comp
                         (map vec)
                         (mapcat resolve-dest))
                        dest)

              (vector? dest)
              (let [[op param] dest]
                (cond
                  (= op :email)
                  (cond
                    (and (coll? param)
                         (every? string? param))
                    (sequence (comp
                               (keep resolve-profile)
                               (mapcat identity))
                              param)

                    (string? param)
                    (resolve-profile param))

                  (= op :team-id)
                  (cond
                    (coll? param)
                    (sequence (comp
                               (mapcat resolve-team)
                               (keep parse-uuid))
                              param)

                    (uuid? param)
                    (resolve-team param)

                    (string? param)
                    (some-> param parse-uuid resolve-team))

                  (= op :profile-id)
                  (if (coll? param)
                    (sequence (keep parse-uuid) param)
                    (resolve-dest param))))))
          ]

    (->> (resolve-dest dest)
         (filter some?)
         (into #{})
         (run! send))))
