;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.common.version
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

(defn parse
  [version]
  (cond
    (= version "%version%")
    {:full "develop"
     :base "develop"
     :build 0
     :commit nil}

    (string? version)
    (let [[base build commit] (str/split version #"-" 3)]
      {:full version
       :base base
       :build (d/parse-integer build)
       :commit commit})

    :else nil))
