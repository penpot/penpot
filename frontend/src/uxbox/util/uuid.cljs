;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.uuid
  "Provides a UUID v4 uuid generation.

  In difference with builtin `random-uuid` function this
  implementation tries to use high quality RNG if is
  available (browser crypto object or nodejs crypto module).

  If no high qualiry RNG, switches to the default Math based
  RNG with proper waring in the console."
  (:refer-clojure :exclude [zero?])
  (:require [uxbox.util.uuid-impl :as impl]))

(def zero #uuid "00000000-0000-0000-0000-000000000000")

(defn zero?
  [v]
  (= zero v))

(defn random
  "Generate a v4 (random) UUID."
  []
  (uuid (impl/v4)))
