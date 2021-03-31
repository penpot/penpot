;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.spec
  (:require
   [clojure.spec.alpha :as s]))

;; SCHEMAS

(s/def ::command #{:move-to
                   :line-to
                   :line-to-horizontal
                   :line-to-vertical
                   :curve-to
                   :smooth-curve-to
                   :quadratic-bezier-curve-to
                   :smooth-quadratic-bezier-curve-to
                   :elliptical-arc
                   :close-path})

(s/def :paths.params/x number?)
(s/def :paths.params/y number?)
(s/def :paths.params/c1x number?)
(s/def :paths.params/c1y number?)
(s/def :paths.params/c2x number?)
(s/def :paths.params/c2y number?)

(s/def ::relative? boolean?)

(s/def ::params
  (s/keys :req-un [:path.params/x
                   :path.params/y]
          :opt-un [:path.params/c1x
                   :path.params/c1y
                   :path.params/c2x
                   :path.params/c2y]))

(s/def ::content-entry
  (s/keys :req-un [::command]
          :req-opt [::params
                    ::relative?]))
(s/def ::content
  (s/coll-of ::content-entry :kind vector?))



