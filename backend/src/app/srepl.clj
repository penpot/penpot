;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   [integrant.core :as ig]))

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

;; --- State initialization

(defmethod ig/assert-key ::server
  [_ params]
  (assert (int? (::port params)) "expected valid port")
  (assert (string? (::host params)) "expected valid host"))

(defmethod ig/expand-key ::server
  [[type :as k] v]
  {k (assoc v ::flag (keyword (str (name type) "-server")))})

(defmethod ig/init-key ::server
  [[type _] {:keys [::flag ::port ::host] :as cfg}]
  (when (contains? cf/flags flag)

    (l/inf :hint "initializing repl server"
           :name (name type)
           :port port
           :host host)

    (let [accept (case type
                   ::prepl 'app.srepl/json-repl
                   ::urepl 'app.srepl/user-repl)
          params {:address host
                  :port port
                  :name (name type)
                  :accept accept}]

      (ccs/start-server params)
      (assoc params :type type))))

(defmethod ig/halt-key! ::server
  [_ params]
  (some-> params :name ccs/stop-server))
