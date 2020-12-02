;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main
  (:require
   [mount.core :as mount]))

(defn- enable-asserts
  [_]
  (let [m (System/getProperty "app.enable-asserts")]
    (or (nil? m) (= "true" m))))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* enable-asserts)

;; Set value for current thread binding.
(set! *assert* (enable-asserts nil))

;; --- Entry point

(defn run
  [_params]
  (require 'app.config
           'app.srepl.server
           'app.migrations
           'app.worker
           'app.media
           'app.http)
  (mount/start))



(defn -main
  [& _args]
  (run {}))
