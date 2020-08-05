;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main
  (:require
   [mount.core :as mount]))

(defn- enable-asserts
  [_]
  (let [m (System/getProperty "uxbox.enable-asserts")]
    (or (nil? m) (= "true" m))))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* enable-asserts)

;; Set value for current thread binding.
(set! *assert* (enable-asserts nil))

;; --- Entry point

(defn -main
  [& args]
  (require 'uxbox.config
           'uxbox.migrations
           'uxbox.media
           'uxbox.http
           'uxbox.tasks)
  (mount/start))
