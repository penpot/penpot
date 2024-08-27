;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.version
  "A version parsing helper."
  (:require
   [cuerdas.core :as str]))

(def version-re #"^(([A-Za-z]+)\-?)?((\d+)\.(\d+)\.(\d+))(\-?((RC|DEV)(\d+)?))?(\-?(\d+))?(\-?g(\w+))?$")

(defn parse
  [data]
  (cond
    (str/starts-with? data "%")
    {:full "develop"
     :branch "develop"
     :base "0.0.0"
     :main "0.0"
     :major "0"
     :minor "0"
     :patch "0"
     :modifier nil
     :commit nil
     :commit-hash nil}

    (string? data)
    (let [result (re-find version-re data)
          major  (get result 4)
          minor  (get result 5)
          patch  (get result 6)
          base   (get result 3)
          main   (str/fmt "%s.%s" major minor)
          branch (get result 2)]

      {:full data
       :base base
       :main main
       :major major
       :minor minor
       :patch patch
       :branch branch
       :modifier (get result 8)
       :commit   (get result 12)
       :commit-hash (get result 14)})

    :else nil))

