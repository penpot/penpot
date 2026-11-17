;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.setup
  "Initial data setup of instance."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.setup.keys :as keys]
   [app.setup.templates]
   [buddy.core.codecs :as bc]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn- get-all-props
  [conn]
  (->> (db/query conn :server-prop {:preload true})
       (filter #(not= "secret-key" (:id %)))
       (map (fn [row]
              [(keyword (:id row))
               (db/decode-transit-pgobject (:content row))]))
       (into {})))

(defn- handle-instance-id
  [instance-id conn]
  (or instance-id
      (let [instance-id (uuid/random)]
        (try
          (db/insert! conn :server-prop
                      {:id "instance-id"
                       :preload true
                       :content (db/tjson instance-id)})
          (catch Throwable cause
            (l/warn :hint "unable to persist instance-id"
                    :instance-id instance-id
                    :cause cause)))
        instance-id)))

(def sql:add-prop
  "INSERT INTO server_prop (id, content, preload)
   VALUES (?, ?, ?)
       ON CONFLICT (id)
       DO UPDATE SET content=?, preload=?")

(defn get-prop
  ([system prop] (get-prop system prop nil))
  ([system prop default]
   (let [prop (d/name prop)]
     (db/run! system (fn [{:keys [::db/conn]}]
                       (or (db/get* conn :server-prop {:id prop})
                           default))))))

(defn set-prop!
  [system prop value]
  (let [value (db/tjson value)
        prop  (d/name prop)]
    (db/run! system (fn [{:keys [::db/conn]}]
                      (db/exec-one! conn [sql:add-prop prop value false value false])))))

(defn- require-secret-key
  [key]
  (when (or (nil? key) (str/blank? key))
    (ex/raise :type :validation
              :code :missing-secret-key
              :hint "PENPOT_SECRET_KEY or PENPOT_SECRET_KEY_FILE is required"))
  key)

(defmethod ig/assert-key ::props
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid database pool"))

(defmethod ig/init-key ::props
  [_ {:keys [::key] :as cfg}]
  (require-secret-key key)

  (audit/submit cfg {:type "trigger"
                     :name "instance-start"
                     :props {:version (:full cf/version)
                             :flags (mapv name cf/flags)
                             :public-uri (str (cf/get :public-uri))}})

  (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                    (db/xact-lock! conn 0)
                    (-> (get-all-props conn)
                        (assoc :secret-key key)
                        (assoc :tokens-key (keys/derive key :salt "tokens"))
                        (update :instance-id handle-instance-id conn)))))

(defmethod ig/init-key ::shared-keys
  [_ {:keys [::props] :as cfg}]
  (let [secret (get props :secret-key)]
    (reduce (fn [keys id]
              (let [key (or (get cfg id)
                            (-> (keys/derive secret :salt (name id))
                                (bc/bytes->b64-str true)))]
                (if (or (str/empty? key)
                        (str/blank? key))
                  (do
                    (l/wrn :id (name id) :hint "key is disabled because empty string found")
                    keys)
                  (do
                    (l/inf :id (name id) :hint "key initialized" :key (d/obfuscate-string key))
                    (assoc keys id key)))))
            {}
            [:exporter
             :admin-console
             :nexus
             :media-processor])))

(sm/register! ::props [:map-of :keyword ::sm/any])
(sm/register! ::shared-keys [:map-of :keyword ::sm/text])

