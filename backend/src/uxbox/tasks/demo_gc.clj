;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.demo-gc
  "Demo accounts garbage collector."
  (:require
   [clojure.tools.logging :as log]
   [uxbox.common.exceptions :as ex]))

(defn handler
  {:uxbox.tasks/name "demo-gc"}
  [{:keys [props] :as task}]
  (Thread/sleep 500)
  (prn (.getName (Thread/currentThread)) "demo-gc" (:id task) (:props task)))
