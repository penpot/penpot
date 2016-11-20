;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.tempfile
  "A temporal file abstractions."
  (:require [storages.core :as st]
            [storages.util :as path])
  (:import [java.nio.file Files]))

(defn create
  "Create a temporal file."
  [& {:keys [suffix prefix]}]
  (->> (path/make-file-attrs "rwxr-xr-x")
       (Files/createTempFile prefix suffix)))
