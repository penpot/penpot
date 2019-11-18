;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tests.main
  (:require [clojure.test :as test]))

(defn -main
  [& args]
  ;; (require 'uxbox.tests.test-projects)
  ;; (require 'uxbox.tests.test-pages)
  ;; (require 'uxbox.tests.test-images)
  ;; (require 'uxbox.tests.test-icons)
  (require 'uxbox.tests.test-users)
  (require 'uxbox.tests.test-auth)
  ;; (require 'uxbox.tests.test-kvstore)
  (let [{:keys [fail]} (test/run-all-tests #"^uxbox.tests.*")]
    (if (pos? fail)
      (System/exit fail)
      (System/exit 0))))


