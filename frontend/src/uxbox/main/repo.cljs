;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo
  "A main interface for access to remote resources."
  (:require [uxbox.util.http :as http]
            [uxbox.main.repo.auth]
            [uxbox.main.repo.users]
            [uxbox.main.repo.projects]
            [uxbox.main.repo.pages]
            [uxbox.main.repo.images]
            [uxbox.main.repo.icons]
            [uxbox.main.repo.kvstore]
            [uxbox.main.repo.impl :as impl]))

(defn req
  "Perform a side effectfull action accesing
  remote resources."
  ([type]
   (impl/request type nil))
  ([type data]
   (impl/request type data)))

(def client-error? http/client-error?)
(def server-error? http/server-error?)
