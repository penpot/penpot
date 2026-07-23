;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.srepl
  "Server Repl."
  (:refer-clojure :exclude [read-line])
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.srepl.cli :as cli]
   [app.srepl.main]
   [app.util.locks :as locks]
   [clojure.core :as c]
   [clojure.core.server :as ccs]
   [clojure.main :as cm]
   [integrant.core :as ig]
   [nrepl.server :as nrepl]))

(defn- repl-init
  []
  (ccs/repl-init)
  (in-ns 'app.srepl.main))

(defn user-repl
  []
  (cm/repl
   :init repl-init
   :read ccs/repl-read))

(defn- ex->data
  [cause phase]
  (let [data (ex-data cause)
        explain (ex/explain data)]
    (cond-> {:phase phase
             :code (get data :code :unknown)
             :type (get data :type :unknown)
             :hint (or (get data :hint) (ex-message cause))}
      (some? explain)
      (assoc :explain explain))))

(defn read-line
  []
  (if-let [line (c/read-line)]
    (try
      (l/dbg :hint "decode" :data line)
      (json/decode line :key-fn json/read-kebab-key)
      (catch Throwable _cause
        (l/warn :hint "unable to decode data" :data line)
        nil))
    ::eof))

(defn json-repl
  []
  (let [lock (locks/create)
        out  *out*

        out-fn
        (fn [m]
          (locks/locking lock
            (binding [*out* out]
              (l/warn :hint "write" :data m)
              (println (json/encode m :key-fn json/write-camel-key)))))

        tapfn
        (fn [val]
          (out-fn {:tag :tap :val val}))]

    (binding [*out* (PrintWriter-on #(out-fn {:tag :out :val %1}) nil true)
              *err* (PrintWriter-on #(out-fn {:tag :err :val %1}) nil true)]
      (try
        (add-tap tapfn)
        (loop []
          (when (try
                  (let [data   (read-line)
                        tpoint (ct/tpoint)]

                    (l/dbg :hint "received" :data (if (= data ::eof) "EOF" data))

                    (try
                      (when-not (= data ::eof)
                        (when-not (nil? data)
                          (let [result  (cli/exec data)
                                elapsed (tpoint)]
                            (l/warn :hint "result" :data result)
                            (out-fn {:tag :ret
                                     :val (if (instance? Throwable result)
                                            (Throwable->map result)
                                            result)
                                     :elapsed (inst-ms elapsed)})))
                        true)
                      (catch Throwable cause
                        (let [elapsed (tpoint)]
                          (out-fn {:tag :ret
                                   :err (ex->data cause :eval)
                                   :elapsed (inst-ms elapsed)})
                          true))))
                  (catch Throwable cause
                    (out-fn {:tag :ret
                             :err (ex->data cause :read)})
                    true))
            (recur)))
        (finally
          (remove-tap tapfn))))))

;; --- UREPL

(defmethod ig/assert-key ::urepl
  [_ params]
  (assert (int? (:port params)) "expected valid port")
  (assert (string? (:host params)) "expected valid host"))

(defmethod ig/init-key ::urepl
  [_ {:keys [:port :host] :as cfg}]
  (when (contains? cf/flags :urepl-server)

    (l/inf :hint "init urepl server" :host host :port port)
    (let [accept 'app.srepl/user-repl
          params {:address host
                  :port port
                  :name "urepl"
                  :accept accept}]

      (ccs/start-server params)
      "urepl")))

(defmethod ig/halt-key! ::urepl
  [_ name]
  (some-> name ccs/stop-server))

(defmethod ig/resume-key ::urepl
  [key opts _ old-name]
  (if old-name
    (do
      (l/inf :hint "keep urepl server")
      old-name)
    (ig/init-key key opts)))

(defmethod ig/suspend-key! ::urepl
  [_ _]
  (l/inf :hint "keep urepl server"))

;; --- PREPL

(defmethod ig/assert-key ::prepl
  [_ params]
  (assert (int? (:port params)) "expected valid port")
  (assert (string? (:host params)) "expected valid host"))

(defmethod ig/init-key ::prepl
  [_ {:keys [:port :host] :as cfg}]
  (when (contains? cf/flags :prepl-server)

    (l/inf :hint "init prepl server" :host host :port port)
    (let [accept 'app.srepl/json-repl
          params {:address host
                  :port port
                  :name "prepl"
                  :accept accept}]

      (ccs/start-server params)
      "prepl")))


(defmethod ig/halt-key! ::prepl
  [_ name]
  (some-> name ccs/stop-server))

(defmethod ig/resume-key ::prepl
  [key opts _ old-name]
  (if old-name
    (do
      (l/inf :hint "keep prepl server")
      old-name)
    (ig/init-key key opts)))

(defmethod ig/suspend-key! ::prepl
  [_ _]
  (l/inf :hint "keep prepl server"))

;; --- NREPL

(defmethod ig/assert-key ::nrepl
  [_ params]
  (assert (int? (:port params)) "expected valid port")
  (assert (string? (:host params)) "expected valid host"))

(defmethod ig/init-key ::nrepl
  [_ {:keys [:port :host] :as cfg}]
  (when (contains? cf/flags :nrepl-server)
    (l/inf :hint "init nrepl server" :host host :port port)
    (nrepl/start-server :bind host :port port)))

(defmethod ig/halt-key! ::nrepl
  [_ server]
  (some-> server nrepl/stop-server))

(defmethod ig/resume-key ::nrepl
  [key opts _ old-server]
  (if old-server
    (do
      (l/inf :hint "keep nrepl server")
      old-server)
    (ig/init-key key opts)))

(defmethod ig/suspend-key! ::nrepl
  [_ _]
  (l/inf :hint "keep nrepl server"))
