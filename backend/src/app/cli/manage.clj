;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.cli.manage
  "A manage cli api."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.main :as main]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [integrant.core :as ig])
  (:import
   java.io.Console))

;; --- IMPL

(defn init-system
  []
  (let [data (-> main/system-config
                 (select-keys [:app.db/pool :app.metrics/metrics])
                 (assoc :app.migrations/all {}))]
    (-> data ig/prep ig/init)))

(defn- read-from-console
  [{:keys [label type] :or {type :text}}]
  (let [^Console console (System/console)]
    (when-not console
      (l/error :hint "no console found, can proceed")
      (System/exit 1))

    (binding [*out* (.writer console)]
      (print label " ")
      (.flush *out*))

    (case type
      :text (.readLine console)
      :password (String. (.readPassword console)))))

(defn create-profile
  [options]
  (let [system   (init-system)
        email    (or (:email options)
                     (read-from-console {:label "Email:"}))
        fullname (or (:fullname options)
                     (read-from-console {:label "Full Name:"}))
        password (or (:password options)
                     (read-from-console {:label "Password:"
                                         :type :password}))]
    (try
      (db/with-atomic [conn (:app.db/pool system)]
        (->> (auth/create-profile! conn
                                  {:fullname fullname
                                   :email email
                                   :password password
                                   :is-active true
                                   :is-demo false})
             (auth/create-profile-rels! conn)))

      (when (pos? (:verbosity options))
        (println "User created successfully."))

      (System/exit 0)

      (catch Exception _e
        (when (pos? (:verbosity options))
          (println "Unable to create user, already exists."))
        (System/exit 1)))))

(defn reset-password
  [options]
  (let [system (init-system)]
    (try
      (db/with-atomic [conn (:app.db/pool system)]
        (let [email    (or (:email options)
                           (read-from-console {:label "Email:"}))
              profile  (profile/get-profile-by-email conn email)]
          (when-not profile
            (when (pos? (:verbosity options))
              (println "Profile does not exists."))
            (System/exit 1))

          (let [password (or (:password options)
                             (read-from-console {:label "Password:"
                                                 :type :password}))]
            (profile/update-profile-password! conn (assoc profile :password password))
            (when (pos? (:verbosity options))
              (println "Password changed successfully.")))))
      (System/exit 0)
      (catch Exception e
        (when (pos? (:verbosity options))
          (println "Unable to change password."))
        (when (= 2 (:verbosity options))
          (.printStackTrace e))
        (System/exit 1)))))

;; --- CLI PARSE

(def cli-options
  ;; An option with a required argument
  [["-u" "--email EMAIL" "Email Address"]
   ["-p" "--password PASSWORD" "Password"]
   ["-n" "--name FULLNAME" "Full Name"
    :id :fullname]
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 1
    :update-fn inc]
   ["-q" nil "Don't print to console"
    :id :verbosity
    :update-fn (constantly 0)]
   ["-h" "--help"]])

(defn usage
  [options-summary]
  (->> ["Penpot CLI management."
        ""
        "Usage: manage [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  create-profile    Create new profile."
        "  reset-password    Reset profile password."
        ""]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary] :as opts} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; custom validation on arguments
      :else
      (let [action (first arguments)]
        (if (#{"create-profile" "reset-password"} action)
          {:action (first arguments) :options options}
          {:exit-message (usage summary)})))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "create-profile" (create-profile options)
        "reset-password" (reset-password options)))))
