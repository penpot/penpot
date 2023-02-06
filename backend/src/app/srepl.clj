;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl
  "Server Repl."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.srepl.ext]
   [app.srepl.main]
   [app.util.json :as json]
   [app.util.locks :as locks]
   [clojure.core.server :as ccs]
   [clojure.main :as cm]
   [clojure.spec.alpha :as s]
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

(defn json-repl
  []
  (let [out  *out*
        lock (locks/create)]
    (ccs/prepl *in*
               (fn [m]
                 (binding [*out* out, *flush-on-newline* true, *print-readably* true]
                   (locks/locking lock
                     (println (json/encode-str m))))))))

;; --- State initialization

(s/def ::port ::us/integer)
(s/def ::host ::us/not-empty-string)
(s/def ::flag #{:urepl-server :prepl-server})
(s/def ::type #{::prepl ::urepl})
(s/def ::key (s/tuple ::type ::us/keyword))

(defmethod ig/pre-init-spec ::server
  [_]
  (s/keys :req [::flag ::host ::port]))

(defmethod ig/prep-key ::server
  [[type _] cfg]
  (assoc cfg ::flag (keyword (str (name type) "-server"))))

(defmethod ig/init-key ::server
  [[type _] {:keys [::flag ::port ::host] :as cfg}]
  (when (contains? cf/flags flag)
    (let [accept (case type
                   ::prepl 'app.srepl/json-repl
                   ::urepl 'app.srepl/user-repl)
          params {:address host
                  :port port
                  :name (name type)
                  :accept accept}]

      (l/info :msg "initializing repl server"
              :name (name type)
              :port port
              :host host)

      (ccs/start-server params)

      params)))

(defmethod ig/halt-key! ::server
  [_ params]
  (some-> params :name ccs/stop-server))
