;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.identity
  "Build identity shared by the isolated Node catalog and browser bundle."
  (:require-macros [benches.render-wasm.identity :refer [compiled-profile-marks]]))

(goog-define build-id "unprepared")

(defn data
  []
  {:build-id build-id :cljs-profile-marks (compiled-profile-marks)})

(defn check
  "Checks a loaded bundle's identity against the selected build."
  [expected-id actual]
  (when-not (and (map? actual)
                 (string? (:build-id actual))
                 (= expected-id (:build-id actual)))
    (throw (ex-info "renderer build identity mismatch"
                    {:type ::mismatch
                     :expected-id expected-id
                     :actual-id (:build-id actual)})))
  actual)
