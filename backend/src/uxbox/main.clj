;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main
  (:require
   [mount.core :as mount]))

(defn- enable-asserts
  [_]
  (let [m (System/getProperty "uxbox.enable-asserts")]
    (or (nil? m) (= "true" m))))

;; Set value for current thread binding.
(set! *assert* (enable-asserts nil))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* enable-asserts)

;; --- Entry point

(defn -main
  [& args]
  (load "uxbox/config"
        "uxbox/migrations"
        "uxbox/http")
  (mount/start))
