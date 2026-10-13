;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence.outbox
  "Durable copy of the save queue.

  Every commit appended to the persistence queue is also written here and
  deleted once the backend acknowledges it, so edits that never reached the
  backend survive a closed tab, a reload or a crash. The next workspace that
  opens the file sends them again under their original commit ids, which the
  backend applies only once.

  Rows are kept in IndexedDB, or in memory where IndexedDB does not exist
  (tests, very old browsers). Each row belongs to the browsing session that
  wrote it; a session holds a Web Lock for its lifetime, so another tab can
  tell an abandoned row from one whose owner is still running."
  (:require
   [app.common.logging :as log]
   [app.common.transit :as t]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(log/set-level! :warn)

(def ^:private db-name "penpot-outbox")
(def ^:private db-version 1)
(def ^:private store-name "commits")
(def ^:private profile-index "profile-id")

(def ^:private session-lock-prefix "penpot-outbox-session:")

(def commit-keys
  "The part of a queued commit needed to send it again."
  [:id :file-id :file-revn :file-vern :changes :features :origin :created-at])

(defn commit->row
  [commit profile-id session-id]
  {:id (:id commit)
   :profile-id profile-id
   :file-id (:file-id commit)
   :session-id session-id
   :created-at (inst-ms (:created-at commit))
   :commit (select-keys commit commit-keys)})

;; --- Backends

(defprotocol IOutboxBackend
  (-put! [_ row])
  (-delete! [_ ids])
  (-rows [_ profile-id])
  (-update-session! [_ ids session-id]))

(defn- row->js
  [{:keys [id profile-id file-id session-id created-at commit]}]
  #js {:id (str id)
       :profileId (str profile-id)
       :fileId (str file-id)
       :sessionId (str session-id)
       :createdAt created-at
       :commit (t/encode-str commit)})

(defn- js->row
  [^js obj]
  (let [commit (t/decode-str (.-commit obj))]
    {:id (:id commit)
     :profile-id (uuid (.-profileId obj))
     :file-id (:file-id commit)
     :session-id (uuid (.-sessionId obj))
     :created-at (.-createdAt obj)
     :commit commit}))

(defn- request->promise
  [^js request]
  (p/create
   (fn [resolve reject]
     (set! (.-onsuccess request) #(resolve (.-result request)))
     (set! (.-onerror request) #(reject (.-error request))))))

(defn- tx->promise
  [^js tx]
  (p/create
   (fn [resolve reject]
     (set! (.-oncomplete tx) #(resolve nil))
     (set! (.-onabort tx) #(reject (.-error tx)))
     (set! (.-onerror tx) #(reject (.-error tx))))))

(defn- open-db
  []
  (let [request (.open js/indexedDB db-name db-version)]
    (set! (.-onupgradeneeded request)
          (fn [_]
            (let [^js db (.-result request)]
              (when-not (.contains (.-objectStoreNames db) store-name)
                (let [store (.createObjectStore db store-name #js {:keyPath "id"})]
                  (.createIndex store profile-index "profileId" #js {:unique false}))))))
    (request->promise request)))

(deftype IndexedDBBackend [db*]
  IOutboxBackend
  (-put! [_ row]
    (p/let [^js db @db*
            tx (.transaction db store-name "readwrite")]
      (.put (.objectStore tx store-name) (row->js row))
      (tx->promise tx)))

  (-delete! [_ ids]
    (p/let [^js db @db*
            tx    (.transaction db store-name "readwrite")
            store (.objectStore tx store-name)]
      (doseq [id ids]
        (.delete store (str id)))
      (tx->promise tx)))

  (-rows [_ profile-id]
    (p/let [^js db @db*
            tx      (.transaction db store-name "readonly")
            index   (.index (.objectStore tx store-name) profile-index)
            results (request->promise (.getAll index (str profile-id)))]
      (->> results
           (keep (fn [obj]
                   ;; An undecodable row cannot be sent again; skip it rather
                   ;; than failing the whole recovery.
                   (try
                     (js->row obj)
                     (catch :default cause
                       (log/wrn :hint "unreadable outbox row" :cause cause)
                       nil))))
           (vec))))

  (-update-session! [_ ids session-id]
    (p/let [^js db @db*
            tx    (.transaction db store-name "readwrite")
            store (.objectStore tx store-name)]
      (doseq [id ids]
        (let [request (.get store (str id))]
          (set! (.-onsuccess request)
                (fn [_]
                  (when-let [^js obj (.-result request)]
                    (set! (.-sessionId obj) (str session-id))
                    (.put store obj))))))
      (tx->promise tx))))

(deftype MemoryBackend [rows*]
  IOutboxBackend
  (-put! [_ row]
    (swap! rows* assoc (:id row) row)
    (p/resolved nil))

  (-delete! [_ ids]
    (swap! rows* #(apply dissoc % ids))
    (p/resolved nil))

  (-rows [_ profile-id]
    (p/resolved (into [] (filter #(= profile-id (:profile-id %))) (vals @rows*))))

  (-update-session! [_ ids session-id]
    (swap! rows* (fn [rows]
                   (reduce (fn [rows id]
                             (cond-> rows
                               (contains? rows id)
                               (assoc-in [id :session-id] session-id)))
                           rows
                           ids)))
    (p/resolved nil)))

(defn memory-backend
  []
  (MemoryBackend. (atom {})))

(defn- create-backend
  []
  (if (exists? js/indexedDB)
    (IndexedDBBackend. (delay (open-db)))
    (memory-backend)))

(defonce backend (create-backend))

(defn- guard
  "Outbox failures never break saving: the queue in memory still works, it is
  only no longer durable."
  [promise hint]
  (p/catch promise (fn [cause]
                     (log/err :hint hint :cause cause)
                     nil)))

;; --- Public API

(defn put!
  [row]
  (guard (-put! backend row) "outbox write failed"))

(defn delete!
  [ids]
  (guard (-delete! backend ids) "outbox delete failed"))

(defn rows
  "The rows of `profile-id`, oldest first."
  [profile-id]
  (p/then (-rows backend profile-id)
          #(vec (sort-by :created-at %))))

(defn claim!
  "Makes `session-id` the owner of the rows with `ids`."
  [ids session-id]
  (-update-session! backend ids session-id))

(defn delete-profile!
  [profile-id]
  (-> (p/let [rows (-rows backend profile-id)]
        (-delete! backend (map :id rows)))
      (guard "outbox cleanup failed")))

;; --- Locks

(defn- locks
  []
  (when (exists? js/navigator)
    (unchecked-get js/navigator "locks")))

(defn locks-available?
  []
  (some? (locks)))

(defonce ^:private held-session (atom nil))

(defn hold-session-lock!
  "Holds the lock of `session-id` until the page goes away. Idempotent."
  [session-id]
  (when-let [^js locks (and (some? session-id) (locks))]
    (when (compare-and-set! held-session nil session-id)
      (.request locks (str session-lock-prefix session-id)
                (fn [_] (p/deferred)))))
  nil)

(defn live-sessions
  "Resolves to the set of session ids whose page is still open, or nil when
  the browser cannot tell."
  []
  (if-let [^js locks (locks)]
    (p/let [^js snapshot (.query locks)]
      (into #{}
            (keep (fn [^js lock]
                    (let [name (.-name lock)]
                      (when (str/starts-with? name session-lock-prefix)
                        (uuid (subs name (count session-lock-prefix)))))))
            (.-held snapshot)))
    (p/resolved nil)))

(defn with-lock
  "Runs `f` (returning a promise) while holding the exclusive lock `name`."
  [name f]
  (if-let [^js locks (locks)]
    (.request locks name (fn [_] (f)))
    (f)))
