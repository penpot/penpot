;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.email.whitelist
  "Email whitelist provider"
  (:refer-clojure :exclude [contains?])
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.email :as-alias email]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [integrant.core :as ig]))

(defn- read-whitelist
  [path]
  (when (and path (fs/exists? path))
    (try
      (with-open [reader (io/reader path)]
        (reduce (fn [result line]
                  (if (str/starts-with? line "#")
                    result
                    (conj result (-> line str/trim str/lower))))
                #{}
                (line-seq reader)))

      (catch Throwable cause
        (l/wrn :hint "unexpected exception on reading email whitelist"
               :cause cause)))))

(defmethod ig/init-key ::email/whitelist
  [_ _]
  (let [whitelist (or (cf/get :registration-domain-whitelist) #{})
        whitelist (if (c/contains? cf/flags :email-whitelist)
                    (into whitelist (read-whitelist (cf/get :email-domain-whitelist)))
                    whitelist)
        whitelist (not-empty whitelist)]


    (when whitelist
      (l/inf :hint "initializing email whitelist" :domains (count whitelist)))

    whitelist))

(defn contains?
  "Check if email is in the whitelist."
  [{:keys [::email/whitelist]} email]
  (let [[_ domain] (str/split email "@" 2)]
    (c/contains? whitelist (str/lower domain))))

(defn enabled?
  "Check if the whitelist is enabled"
  [{:keys [::email/whitelist]}]
  (some? whitelist))
