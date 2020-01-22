;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx-tests.main
  (:require [clojure.test :as t]))

(defn -main
  [& args]
  (let [{:keys [fail]} (t/run-all-tests #"^vertx-tests.*")]
    (if (pos? fail)
      (System/exit fail)
      (System/exit 0))))
