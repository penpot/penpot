;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.version
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

(def version-re #"^(([A-Za-z]+)\-?)?((\d+)\.(\d+)\.(\d+))(\-?((RC|DEV)(\d+)?))?(\-?(\d+))?(\-?g(\w+))?$")

(defn parse
  [data]
  (cond
    (or (str/starts-with? data "%")
        (= data "develop"))
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

(defn- version-components
  [version]
  (let [{:keys [major minor patch]} (or (parse version) {})]
    [(d/parse-integer major 0)
     (d/parse-integer minor 0)
     (d/parse-integer patch 0)]))

(defn compare-versions
  "Compare two X.Y.Z base versions. Returns negative if a < b, zero if
  equal, positive if a > b."
  [version-a version-b]
  (let [[major-a minor-a patch-a] (version-components version-a)
        [major-b minor-b patch-b] (version-components version-b)]
    (or (when (not= major-a major-b) (- major-a major-b))
        (when (not= minor-a minor-b) (- minor-a minor-b))
        (- patch-a patch-b))))

(defn newer?
  [version-a version-b]
  (pos? (compare-versions version-a version-b)))

